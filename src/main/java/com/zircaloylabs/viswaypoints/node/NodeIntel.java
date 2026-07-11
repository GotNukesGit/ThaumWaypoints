package com.zircaloylabs.viswaypoints.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.dyonovan.tcnodetracker.TCNodeTracker;
import com.dyonovan.tcnodetracker.lib.NodeList;

import cpw.mods.fml.common.Loader;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.nodes.INode;
import thaumcraft.api.nodes.NodeModifier;

/**
 * Everything we know about aura nodes, assembled from the best source available for each one.
 *
 * There are two sources, and they are used in strict priority order:
 *
 * 1. The live TileNode, when the node's chunk happens to be loaded. This is exact -- current vis
 * and each aspect's base (maximum) come straight off the tile entity. Reading it also refreshes
 * our memory of that node, which is how "how much I left it at" gets captured: after you drain a
 * node you are standing next to it, so we record what you left behind.
 *
 * 2. TCNodeTracker's scanned-node database (TCNodeTracker.nodelist), which stores the aspects, node
 * type, modifier and a scan timestamp for every node you've hit with a Thaumometer. For nodes
 * that are far away / unloaded, we take the last known amounts and project them forward with
 * RegenModel to estimate what's there now.
 *
 * A note on why there is no VisualProspecting source: VisualProspecting tracks ore veins and wells,
 * and has no concept of Thaumcraft aura nodes at all. TCNodeTracker is the only node database in the
 * pack, so it (plus live reads) is what we use.
 */
public final class NodeIntel {

    private static final String TCNODETRACKER_MODID = "tcnodetracker";

    private NodeIntel() {}

    public static boolean isNodeTrackerAvailable() {
        return Loader.isModLoaded(TCNODETRACKER_MODID);
    }

    /**
     * All nodes we know about in the player's current dimension, each carrying our best estimate of
     * its present contents.
     */
    public static List<KnownNode> knownNodesInCurrentDimension() {
        final List<KnownNode> out = new ArrayList<>();

        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) return out;

        final World world = mc.theWorld;
        final int playerDim = world.provider.dimensionId;

        if (!isNodeTrackerAvailable() || TCNodeTracker.nodelist == null) return out;

        // Copy defensively: TCNodeTracker mutates this list when the player scans a node.
        final List<NodeList> tracked = new ArrayList<>(TCNodeTracker.nodelist);

        for (NodeList tracker : tracked) {
            if (tracker == null || tracker.dim != playerDim) continue;
            if (tracker.aspect == null || tracker.aspect.isEmpty()) continue;

            final KnownNode node = fromTracker(tracker);
            refineWithLiveTileEntity(world, node);
            NodeMemory.remember(node);
            out.add(node);
        }

        return out;
    }

    /** Builds a node from the tracker snapshot, projecting regeneration forward from the scan time. */
    private static KnownNode fromTracker(NodeList tracker) {
        final String modifier = tracker.mod;
        final KnownNode node = new KnownNode(tracker.dim, tracker.x, tracker.y, tracker.z, tracker.type, modifier);

        // Start from whatever we most recently observed: our own memory (which may be newer than the
        // Thaumometer scan, e.g. we watched you drain it) or the tracker snapshot.
        final NodeMemory.Entry remembered = NodeMemory.recall(node.key());

        final Map<String, Integer> lastKnown = new HashMap<>();
        long observedAt;

        if (remembered != null && remembered.observedAtMillis >= toMillis(tracker)) {
            lastKnown.putAll(remembered.amounts);
            node.base.putAll(remembered.base);
            observedAt = remembered.observedAtMillis;
        } else {
            lastKnown.putAll(tracker.aspect);
            observedAt = toMillis(tracker);
            if (remembered != null) node.base.putAll(remembered.base);
        }

        node.observedAtMillis = observedAt;

        // Absent a live reading, assume the amounts we first saw represent the node's base. A node is
        // normally scanned while full, so this is a reasonable ceiling; a live read will correct it.
        for (Map.Entry<String, Integer> e : lastKnown.entrySet()) {
            node.base.merge(e.getKey(), e.getValue(), Math::max);
        }

        if (RegenModel.neverRegenerates(modifier)) {
            node.aspects.putAll(lastKnown);
            node.freshness = KnownNode.Freshness.STALE_NO_REGEN;
            return node;
        }

        final long elapsed = System.currentTimeMillis() - observedAt;
        final int pool = RegenModel.visRegainedOver(elapsed, modifier);

        node.aspects.putAll(distributeRegen(lastKnown, node.base, pool));
        node.freshness = KnownNode.Freshness.ESTIMATED;
        return node;
    }

    /**
     * Spreads a pool of regenerated vis across the aspects that sit below their base value.
     *
     * Thaumcraft awards each point to a random below-base aspect, so we approximate that by filling
     * the deficient aspects as evenly as we can, never exceeding base.
     */
    private static Map<String, Integer> distributeRegen(Map<String, Integer> current, Map<String, Integer> base,
        int pool) {
        final Map<String, Integer> result = new HashMap<>(current);
        if (pool <= 0) return result;

        int remaining = pool;

        // Round-robin a point at a time into whichever aspects are still short. Bounded by total
        // headroom, so this terminates even for a very old snapshot.
        int headroom = 0;
        for (Map.Entry<String, Integer> e : result.entrySet()) {
            final int cap = base.getOrDefault(e.getKey(), e.getValue());
            headroom += Math.max(0, cap - e.getValue());
        }
        remaining = Math.min(remaining, headroom);

        while (remaining > 0) {
            boolean progressed = false;

            for (Map.Entry<String, Integer> e : result.entrySet()) {
                if (remaining <= 0) break;

                final int cap = base.getOrDefault(e.getKey(), e.getValue());
                if (e.getValue() < cap) {
                    e.setValue(e.getValue() + 1);
                    remaining--;
                    progressed = true;
                }
            }

            if (!progressed) break;
        }

        return result;
    }

    /**
     * If the node's chunk is loaded, replace our estimate with the exact truth from the tile entity
     * and record it. This is what makes nearby nodes read live, and what captures the state you left
     * a node in immediately after draining it.
     */
    private static void refineWithLiveTileEntity(World world, KnownNode node) {
        if (!world.blockExists(node.x, node.y, node.z)) return;

        final TileEntity tile = world.getTileEntity(node.x, node.y, node.z);
        if (!(tile instanceof INode)) return;

        final INode live = (INode) tile;

        node.aspects.clear();
        node.base.clear();

        final Aspect[] present = live.getAspects() == null ? new Aspect[0]
            : live.getAspects()
                .getAspects();
        for (Aspect aspect : present) {
            if (aspect == null) continue;
            node.aspects.put(
                aspect.getTag(),
                live.getAspects()
                    .getAmount(aspect));
            node.base.put(aspect.getTag(), live.getNodeVisBase(aspect));
        }

        node.freshness = KnownNode.Freshness.LIVE;
        node.observedAtMillis = System.currentTimeMillis();
    }

    /** TCNodeTracker stores an Instant; guard against a null/oddly-parsed date. */
    private static long toMillis(NodeList tracker) {
        try {
            return tracker.date == null ? 0L : tracker.date.toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** Reads the live modifier off a tile entity, for callers that have one in hand. */
    public static String modifierNameOf(INode live) {
        final NodeModifier modifier = live.getNodeModifier();
        return modifier == null ? "BLANK" : modifier.name();
    }
}

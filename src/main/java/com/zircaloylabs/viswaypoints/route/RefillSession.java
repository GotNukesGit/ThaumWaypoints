package com.zircaloylabs.viswaypoints.route;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.zircaloylabs.viswaypoints.config.VWConfig;
import com.zircaloylabs.viswaypoints.node.KnownNode;
import com.zircaloylabs.viswaypoints.node.NodeIntel;
import com.zircaloylabs.viswaypoints.render.ActiveTarget;
import com.zircaloylabs.viswaypoints.wand.WandVis;
import com.zircaloylabs.viswaypoints.waypoint.WaypointService;
import com.zircaloylabs.viswaypoints.waypoint.WaypointSuppressor;

import thaumcraft.api.aspects.Aspect;

/**
 * One "refill my wand" run, walked one node at a time.
 *
 * Rather than dumping the whole route on the map, this shows only the node you're going to next.
 * When you get there and drain it, that waypoint is deleted and the next appears. Two reasons:
 *
 * - JourneyMap lists waypoints by distance, not by the order we planned them, so a whole route on
 * the map gets walked out of sequence -- and since each node hands over everything it can, an
 * out-of-order walk makes every "take N of X" figure wrong.
 *
 * - Standing at a node means its chunk is loaded, so we can read its *exact* contents instead of an
 * estimate. Re-planning from there means an over- or under-estimated node self-corrects on the
 * next hop rather than poisoning the whole route.
 *
 * A stop counts as done when you're inside the arrival radius and the node has nothing left that you
 * still need -- either you drained it, or (if the estimate was wrong) it never had anything to give,
 * in which case we simply move on.
 */
public class RefillSession {

    /** Aspect tags this run set out to refill, and how short we were at the time. */
    private final Map<String, Integer> targetDeficit = new HashMap<>();

    private KnownNode target;
    private int stopNumber = 0;
    private boolean finished = false;
    private boolean exhausted = false;

    public RefillSession(Map<Aspect, Integer> deficit) {
        for (Map.Entry<Aspect, Integer> e : deficit.entrySet()) {
            targetDeficit.put(
                e.getKey()
                    .getTag(),
                e.getValue());
        }
    }

    public boolean isFinished() {
        return finished;
    }

    /** True when the known nodes ran out before the wand was full. */
    public boolean isExhausted() {
        return exhausted;
    }

    public int stopNumber() {
        return stopNumber;
    }

    public KnownNode target() {
        return target;
    }

    /** What the player will find at the next stop, for chat feedback. */
    private Router.Stop pendingStop;

    public Router.Stop pendingStop() {
        return pendingStop;
    }

    /** Remaining stops the current plan expects after the one being shown. */
    private int moreAfter = 0;

    public int moreAfter() {
        return moreAfter;
    }

    /**
     * Advances the run. Returns true when something changed that the caller should announce (a new
     * waypoint was placed, the run completed, or we ran out of usable nodes).
     */
    public boolean tick() {
        if (finished) return false;

        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return false;

        final EntityPlayer player = mc.thePlayer;
        final ItemStack wand = WandVis.heldWand();

        // Putting the wand away must not end or corrupt the run; just wait until it's back in hand.
        if (wand == null) return false;

        final Map<Aspect, Integer> deficit = WandVis.deficit(wand);

        if (isGoalMet(deficit)) {
            end();
            return true;
        }

        if (target == null) {
            return planNext(deficit, player);
        }

        // Still travelling.
        if (target.distanceTo(player.posX, player.posY, player.posZ) > VWConfig.arrivalRadius) {
            return false;
        }

        // We've arrived: read the node for real rather than trusting the estimate.
        final KnownNode live = NodeIntel.readLive(target.x, target.y, target.z);

        // Chunk not loaded yet, or the node is momentarily unreadable -- wait rather than skip it.
        if (live == null) return false;

        if (stillHasWhatWeNeed(live, deficit)) {
            // Node still holds vis we're short of: the player hasn't finished draining it.
            return false;
        }

        // Nothing left here for us. Move on, re-planning from where we now stand with what we now have.
        return planNext(deficit, player);
    }

    /**
     * Plans the next hop from the player's current position and current shortfall, and places a single
     * waypoint for it.
     */
    private boolean planNext(Map<Aspect, Integer> deficit, EntityPlayer player) {
        WaypointService.clearAll();

        final List<KnownNode> known = NodeIntel.knownNodesInCurrentDimension();

        final Router.Route route = Router.plan(deficit, known, player.posX, player.posY, player.posZ);

        if (route.isEmpty()) {
            pendingStop = null;
            moreAfter = 0;
            exhausted = true;
            end();
            return true;
        }

        final Router.Stop next = route.stops.get(0);

        stopNumber++;
        target = next.node;
        pendingStop = next;
        moreAfter = route.stops.size() - 1;

        ActiveTarget.set(next.node);
        WaypointService.createSingle(next, stopNumber, moreAfter);
        return true;
    }

    /**
     * Tears the run down: our waypoints go, the beacon goes, and the player's own waypoints come back.
     * Everything that ends a run funnels through here so there is exactly one place responsible for
     * giving the player their map back.
     */
    public void end() {
        WaypointService.clearAll();
        ActiveTarget.clear();

        if (VWConfig.suppressOtherWaypoints) {
            WaypointSuppressor.restore();
        }

        target = null;
        finished = true;
    }

    /** True when the node still holds any aspect we're short of. */
    private boolean stillHasWhatWeNeed(KnownNode node, Map<Aspect, Integer> deficit) {
        final int reserve = VWConfig.reservePerNode;

        for (Map.Entry<Aspect, Integer> missing : deficit.entrySet()) {
            final String tag = missing.getKey()
                .getTag();
            final int available = node.amountOf(tag) - reserve;

            if (available > 0) return true;
        }

        return false;
    }

    /**
     * The run is done when the wand is completely full, or -- if configured that way -- as soon as the
     * aspects it was created to top up are full, so spending Ordo on the walk home doesn't revive a
     * finished route.
     */
    private boolean isGoalMet(Map<Aspect, Integer> deficit) {
        if (deficit.isEmpty()) return true;
        if (!VWConfig.clearWhenTargetAspectsFull) return false;

        for (Map.Entry<Aspect, Integer> stillMissing : deficit.entrySet()) {
            final String tag = stillMissing.getKey()
                .getTag();

            if (targetDeficit.containsKey(tag)) return false;
        }

        return true;
    }
}

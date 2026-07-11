package com.zircaloylabs.viswaypoints.keys;

import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.zircaloylabs.viswaypoints.config.VWConfig;
import com.zircaloylabs.viswaypoints.node.KnownNode;
import com.zircaloylabs.viswaypoints.node.NodeIntel;
import com.zircaloylabs.viswaypoints.node.NodeMemory;
import com.zircaloylabs.viswaypoints.route.RefillSession;
import com.zircaloylabs.viswaypoints.route.Router;
import com.zircaloylabs.viswaypoints.wand.WandVis;
import com.zircaloylabs.viswaypoints.waypoint.WaypointService;
import com.zircaloylabs.viswaypoints.waypoint.WaypointSuppressor;
import com.zircaloylabs.viswaypoints.render.ActiveTarget;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import thaumcraft.api.aspects.Aspect;

/** Handles the keybinds and drives the active run. */
public class ClientEvents {

    private RefillSession session;

    /** A few checks a second is plenty; nothing here needs 20Hz. */
    private static final int POLL_INTERVAL_TICKS = 10;

    private static final int SAVE_INTERVAL_TICKS = 20 * 30;

    private int tickCounter = 0;

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (KeyBindings.route.isPressed()) {
            startRoute();
        }

        if (KeyBindings.clear.isPressed()) {
            clearRoute();
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return;

        tickCounter++;

        if (tickCounter % SAVE_INTERVAL_TICKS == 0) {
            NodeMemory.saveIfDirty();
        }

        if (session == null || tickCounter % POLL_INTERVAL_TICKS != 0) return;

        if (!session.tick()) return;

        // Something changed: the run finished, ran dry, or moved on to the next node.
        if (session.isFinished()) {
            if (session.isExhausted()) {
                say(
                    EnumChatFormatting.YELLOW
                        + "No more scanned nodes can supply what's left. Scan more, or let these regenerate.");
            } else if (VWConfig.chatFeedback) {
                say(EnumChatFormatting.GREEN + "Wand topped up \u2014 waypoints cleared.");
            }

            session = null;
            return;
        }

        announceNextStop();
    }

    /**
     * Leaving the world must never strand the player with their waypoints hidden, so tear any active
     * run down on disconnect rather than relying on them to finish it.
     */
    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (session != null) {
            session.end();
            session = null;
        } else {
            ActiveTarget.clear();
            WaypointSuppressor.restore();
        }
    }

    private void startRoute() {
        if (!WaypointService.isJourneyMapAvailable()) {
            say(EnumChatFormatting.RED + "JourneyMap isn't loaded, so waypoints can't be created.");
            return;
        }

        if (!NodeIntel.isNodeTrackerAvailable()) {
            say(EnumChatFormatting.RED + "TCNodeTracker isn't loaded, so there are no scanned nodes to route to.");
            return;
        }

        final ItemStack wand = WandVis.heldWand();
        if (wand == null) {
            say(EnumChatFormatting.YELLOW + "Hold a wand or staff to plan a refill run.");
            return;
        }

        final Map<Aspect, Integer> deficit = WandVis.deficit(wand);
        if (deficit.isEmpty()) {
            say(EnumChatFormatting.GREEN + "That wand is already full.");
            return;
        }

        WaypointService.clearAll();
        session = null;

        final Minecraft mc = Minecraft.getMinecraft();
        final List<KnownNode> known = NodeIntel.knownNodesInCurrentDimension();

        if (known.isEmpty()) {
            say(EnumChatFormatting.YELLOW + "No scanned nodes in this dimension. Scan some with a Thaumometer first.");
            return;
        }

        // Hide the player's own waypoints before we place ours, so only the node is on the map.
        if (VWConfig.suppressOtherWaypoints) {
            WaypointSuppressor.suppress();
        }

        if (VWConfig.progressiveWaypoints) {
            startProgressiveRun(deficit);
            return;
        }

        // Legacy mode: drop the whole route at once.
        final Router.Route route = Router.plan(deficit, known, mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);

        if (route.isEmpty()) {
            say(
                EnumChatFormatting.YELLOW + "None of your scanned nodes within "
                    + VWConfig.maxSearchRadius
                    + " blocks have the vis you need.");
            return;
        }

        final int created = WaypointService.createFor(route);
        session = new RefillSession(deficit);

        if (VWConfig.chatFeedback) {
            say(
                EnumChatFormatting.LIGHT_PURPLE + "Vis run: "
                    + created
                    + " stop"
                    + (created == 1 ? "" : "s")
                    + " ("
                    + Math.round(route.travelDistance)
                    + " blocks) to refill "
                    + describe(deficit)
                    + ".");

            if (!route.fullyCovers()) {
                say(
                    EnumChatFormatting.GRAY + "  Not fully covered by known nodes: "
                        + describeTags(route.uncovered)
                        + ".");
            }
        }
    }

    private void startProgressiveRun(Map<Aspect, Integer> deficit) {
        session = new RefillSession(deficit);

        if (VWConfig.chatFeedback) {
            say(EnumChatFormatting.LIGHT_PURPLE + "Vis run started \u2014 refilling " + describe(deficit) + ".");
        }

        // Plans and places the first waypoint immediately.
        if (session.tick()) {
            if (session.isFinished()) {
                if (session.isExhausted()) {
                    say(
                        EnumChatFormatting.YELLOW + "None of your scanned nodes within "
                            + VWConfig.maxSearchRadius
                            + " blocks have the vis you need.");
                }

                // session.end() already restored the map; just drop the run.
                session = null;
                return;
            }

            announceNextStop();
        }
    }

    private void announceNextStop() {
        if (!VWConfig.chatFeedback || session == null) return;

        final Router.Stop stop = session.pendingStop();
        if (stop == null) return;

        final Minecraft mc = Minecraft.getMinecraft();
        final int distance = (int) Math
            .round(stop.node.distanceTo(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ));

        final StringBuilder message = new StringBuilder(EnumChatFormatting.LIGHT_PURPLE.toString()).append("Stop #")
            .append(session.stopNumber())
            .append(": ")
            .append(describeTags(stop.take))
            .append(" \u2014 ")
            .append(distance)
            .append(" blocks away");

        if (session.moreAfter() > 0) {
            message.append(" (")
                .append(session.moreAfter())
                .append(" more after this)");
        }

        say(
            message.append('.')
                .toString());
    }

    private void clearRoute() {
        final int removed = WaypointService.clearAll();

        ActiveTarget.clear();
        if (VWConfig.suppressOtherWaypoints) {
            WaypointSuppressor.restore();
        }

        session = null;

        if (VWConfig.chatFeedback) {
            if (removed > 0) {
                say(EnumChatFormatting.GRAY + "Cleared " + removed + " vis waypoint" + (removed == 1 ? "" : "s") + ".");
            } else {
                say(EnumChatFormatting.GRAY + "No vis waypoints to clear.");
            }
        }
    }

    private static String describe(Map<Aspect, Integer> deficit) {
        final StringBuilder sb = new StringBuilder();

        boolean first = true;
        for (Map.Entry<Aspect, Integer> e : deficit.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(
                e.getKey()
                    .getName())
                .append(' ')
                .append(e.getValue());
            first = false;
        }

        return sb.toString();
    }

    private static String describeTags(Map<String, Integer> byTag) {
        final StringBuilder sb = new StringBuilder();

        boolean first = true;
        for (Map.Entry<String, Integer> e : byTag.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(capitalise(e.getKey()))
                .append(' ')
                .append(e.getValue());
            first = false;
        }

        return sb.toString();
    }

    private static String capitalise(String tag) {
        if (tag == null || tag.isEmpty()) return "";
        return Character.toUpperCase(tag.charAt(0)) + tag.substring(1);
    }

    private static void say(String message) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;

        mc.thePlayer.addChatMessage(new ChatComponentText(message));
    }
}

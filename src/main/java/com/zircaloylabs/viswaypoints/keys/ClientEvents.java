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

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import thaumcraft.api.aspects.Aspect;

/**
 * Drives the mod: handles the keybinds, and polls the wand so a finished run cleans up after itself.
 */
public class ClientEvents {

    /** The run currently in progress, or null. */
    private RefillSession session;

    /** Poll the wand a few times a second rather than every tick; nothing here needs 20Hz. */
    private static final int POLL_INTERVAL_TICKS = 10;

    /** Node memory is flushed to disk on this cadence (and only when it actually changed). */
    private static final int SAVE_INTERVAL_TICKS = 20 * 30;

    private int tickCounter = 0;

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (KeyBindings.route.isPressed()) {
            startRoute();
        }

        if (KeyBindings.clear.isPressed()) {
            clearRoute(true);
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

        final ItemStack wand = WandVis.heldWand();

        if (session.pollComplete(wand)) {
            final int removed = WaypointService.clearAll();
            session = null;

            if (VWConfig.chatFeedback) {
                say(EnumChatFormatting.GREEN + "Wand topped up \u2014 cleared " + removed + " vis waypoint"
                        + (removed == 1 ? "" : "s") + ".");
            }
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

        // Replace any previous run rather than stacking waypoints on top of each other.
        WaypointService.clearAll();

        final Minecraft mc = Minecraft.getMinecraft();
        final double px = mc.thePlayer.posX;
        final double py = mc.thePlayer.posY;
        final double pz = mc.thePlayer.posZ;

        final List<KnownNode> known = NodeIntel.knownNodesInCurrentDimension();
        if (known.isEmpty()) {
            say(EnumChatFormatting.YELLOW
                    + "No scanned nodes in this dimension. Scan some with a Thaumometer first.");
            return;
        }

        final Router.Route route = Router.plan(deficit, known, px, py, pz);

        if (route.isEmpty()) {
            say(EnumChatFormatting.YELLOW + "None of your scanned nodes within "
                    + VWConfig.maxSearchRadius
                    + " blocks have the vis you need.");
            return;
        }

        final int created = WaypointService.createFor(route);
        session = new RefillSession(deficit, created);

        if (VWConfig.chatFeedback) {
            say(EnumChatFormatting.LIGHT_PURPLE + "Vis run: " + created + " waypoint" + (created == 1 ? "" : "s")
                    + " to refill " + describe(deficit) + ".");

            if (!route.fullyCovers()) {
                say(EnumChatFormatting.GRAY + "  Not fully covered by known nodes: " + describeTags(route.uncovered)
                        + ". Scan more nodes, or wait for these to regenerate.");
            }
        }
    }

    private void clearRoute(boolean manual) {
        final int removed = WaypointService.clearAll();
        session = null;

        if (manual && VWConfig.chatFeedback) {
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
            sb.append(e.getKey().getName()).append(' ').append(e.getValue());
            first = false;
        }

        return sb.toString();
    }

    private static String describeTags(Map<String, Integer> byTag) {
        final StringBuilder sb = new StringBuilder();

        boolean first = true;
        for (Map.Entry<String, Integer> e : byTag.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append(' ').append(e.getValue());
            first = false;
        }

        return sb.toString();
    }

    private static void say(String message) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;

        mc.thePlayer.addChatMessage(new ChatComponentText(message));
    }
}

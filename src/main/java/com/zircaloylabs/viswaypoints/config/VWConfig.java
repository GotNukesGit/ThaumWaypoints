package com.zircaloylabs.viswaypoints.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * Mod settings. Exposed in-game via Mods -> Vis Waypoints -> Config, so nobody has to hand-edit a
 * config file, and changes apply immediately (nothing here is read at load time only).
 */
public final class VWConfig {

    public static final String CATEGORY_GENERAL = "general";
    public static final String CATEGORY_DISPLAY = "display";

    /** Furthest a node can be and still be routed to, in blocks. */
    public static int maxSearchRadius = 2000;

    /** Most waypoints a single run will create. */
    public static int maxWaypoints = 5;

    /**
     * Vis the router assumes it will leave behind in a node, rather than planning to drain it dry.
     *
     * This is a planning figure only -- it does not, and cannot, stop you draining a node. It just
     * makes the router slightly conservative about how much a node is worth routing you to.
     *
     * Thaumcraft already protects nodes properly via Node Preservation research (the wand refuses to
     * take an aspect's last point) and wand cap choice, so the default is a token 1 rather than a
     * large buffer. Set to 0 to let the router plan on emptying nodes completely.
     */
    public static int reservePerNode = 1;

    /** End the run once the aspects it set out to refill are full, without waiting for a full wand. */
    public static boolean clearWhenTargetAspectsFull = true;

    /**
     * Show one waypoint at a time: when you arrive and drain a node, its waypoint is deleted and the
     * next one appears. This keeps the route unambiguous (JourneyMap sorts waypoints by distance, so
     * a whole route dumped on the map gets walked out of order), and lets the remaining route be
     * re-planned at each stop from the node's exact live contents.
     */
    public static boolean progressiveWaypoints = true;

    /** How close (blocks) you must get to a node before it counts as "arrived". */
    public static int arrivalRadius = 24;

    /** Announce route creation / completion in chat. */
    public static boolean chatFeedback = true;

    /** RGB color of the created waypoints. */
    public static int waypointColor = 0xB44DFF;

    /** Draw a pulsing beam of light at the node you're being routed to, visible through terrain. */
    public static boolean showBeacon = true;

    /** RGB color of that beam. */
    public static int beaconColor = 0xB44DFF;

    /**
     * Hide all your other JourneyMap waypoints while a run is active, so the node is the only thing on
     * the map. They are restored when the run ends -- including after a crash, on the next startup.
     */
    public static boolean suppressOtherWaypoints = true;

    private static Configuration configuration;

    private VWConfig() {}

    public static Configuration configuration() {
        return configuration;
    }

    public static void init(File configFile) {
        configuration = new Configuration(configFile);
        configuration.load();
        load();
        if (configuration.hasChanged()) configuration.save();
    }

    /** Re-reads values; called after the in-game config screen is closed. */
    public static void load() {
        if (configuration == null) return;

        maxSearchRadius = configuration.getInt(
            "maxSearchRadius",
            CATEGORY_GENERAL,
            2000,
            64,
            30000,
            "How far away (in blocks) a node can be and still get a waypoint.");

        maxWaypoints = configuration.getInt(
            "maxWaypoints",
            CATEGORY_GENERAL,
            5,
            1,
            20,
            "Maximum number of waypoints a single refill run will create.");

        reservePerNode = configuration.getInt(
            "reservePerNode",
            CATEGORY_GENERAL,
            1,
            0,
            50,
            "How much vis the router assumes it leaves behind in a node when planning a run.\n"
                + "This is a planning figure only: it does not stop you draining a node.\n"
                + "Thaumcraft's own Node Preservation research already prevents a wand from\n"
                + "taking an aspect's last point, so this defaults to 1. Set to 0 to have the\n"
                + "router plan on emptying nodes completely.");

        clearWhenTargetAspectsFull = configuration
            .get(
                CATEGORY_GENERAL,
                "clearWhenTargetAspectsFull",
                true,
                "Delete the run's waypoints as soon as the aspects it was created to refill are full,\n"
                    + "instead of waiting for every primal on the wand to be at capacity.\n"
                    + "Waypoints are always deleted once the wand is completely full regardless.")
            .getBoolean();

        progressiveWaypoints = configuration
            .get(
                CATEGORY_GENERAL,
                "progressiveWaypoints",
                true,
                "Show only the next node's waypoint, instead of the whole route at once.\n"
                    + "When you arrive and drain that node, its waypoint is removed and the next appears,\n"
                    + "with the rest of the route re-planned from what the node actually gave you.\n"
                    + "Turn off to drop waypoints for every stop up front.")
            .getBoolean();

        arrivalRadius = configuration.getInt(
            "arrivalRadius",
            CATEGORY_GENERAL,
            24,
            4,
            128,
            "How close (in blocks) you need to get to a node for it to count as reached.");

        chatFeedback = configuration
            .get(CATEGORY_DISPLAY, "chatFeedback", true, "Print route and completion messages to chat.")
            .getBoolean();

        waypointColor = configuration.getInt(
            "waypointColor",
            CATEGORY_DISPLAY,
            0xB44DFF,
            0x000000,
            0xFFFFFF,
            "Color of the waypoints this mod creates, as a decimal RGB value.");

        showBeacon = configuration
            .get(
                CATEGORY_DISPLAY,
                "showBeacon",
                true,
                "Draw a pulsing beam of light at the node you're heading to.\n"
                    + "It renders through terrain, so you can see it from over a hill.")
            .getBoolean();

        beaconColor = configuration.getInt(
            "beaconColor",
            CATEGORY_DISPLAY,
            0xB44DFF,
            0x000000,
            0xFFFFFF,
            "Color of the beacon beam, as a decimal RGB value.");

        suppressOtherWaypoints = configuration
            .get(
                CATEGORY_DISPLAY,
                "suppressOtherWaypoints",
                true,
                "While a run is active, hide all your other JourneyMap waypoints so only the node shows.\n"
                    + "They are restored when the run finishes, when you clear it, on disconnect, and -- if\n"
                    + "the game crashed mid-run -- the next time the mod loads.")
            .getBoolean();

        if (configuration.hasChanged()) configuration.save();
    }
}

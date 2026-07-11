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

    /** Announce route creation / completion in chat. */
    public static boolean chatFeedback = true;

    /** RGB color of the created waypoints. */
    public static int waypointColor = 0x9B6DD7;

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

        clearWhenTargetAspectsFull = configuration.get(
                CATEGORY_GENERAL,
                "clearWhenTargetAspectsFull",
                true,
                "Delete the run's waypoints as soon as the aspects it was created to refill are full,\n"
                        + "instead of waiting for every primal on the wand to be at capacity.\n"
                        + "Waypoints are always deleted once the wand is completely full regardless.")
                .getBoolean();

        chatFeedback = configuration
                .get(CATEGORY_DISPLAY, "chatFeedback", true, "Print route and completion messages to chat.")
                .getBoolean();

        waypointColor = configuration.getInt(
                "waypointColor",
                CATEGORY_DISPLAY,
                0x9B6DD7,
                0x000000,
                0xFFFFFF,
                "Color of the waypoints this mod creates, as a decimal RGB value.");

        if (configuration.hasChanged()) configuration.save();
    }
}

package com.zircaloylabs.viswaypoints.waypoint;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.zircaloylabs.viswaypoints.VisWaypoints;
import com.zircaloylabs.viswaypoints.config.VWConfig;
import com.zircaloylabs.viswaypoints.node.KnownNode;
import com.zircaloylabs.viswaypoints.route.Router;

import cpw.mods.fml.common.Loader;
import journeymap.client.model.Waypoint;
import journeymap.client.waypoint.WaypointStore;

/**
 * Creates and removes the JourneyMap waypoints for a refill run.
 *
 * Every waypoint we create is named with a distinctive prefix, and deletion only ever touches
 * waypoints carrying that prefix. That matters: the alternative -- remembering the Waypoint objects
 * we created and deleting those -- silently fails across a game restart, and a bug in it would start
 * deleting the player's own hand-placed waypoints. Matching on our own prefix means the worst case is
 * that we fail to clean up one of ours, never that we destroy one of theirs.
 */
public final class WaypointService {

    /** Marks a waypoint as ours. Deletion is scoped to names starting with this. */
    public static final String PREFIX = "\u2748 Vis";

    private static final String JOURNEYMAP_MODID = "journeymap";

    private WaypointService() {}

    public static boolean isJourneyMapAvailable() {
        return Loader.isModLoaded(JOURNEYMAP_MODID);
    }

    /**
     * Drops a waypoint for every stop on the route. Returns how many were created.
     */
    public static int createFor(Router.Route route) {
        if (!isJourneyMapAvailable() || route.isEmpty()) return 0;

        int created = 0;

        for (Router.Stop stop : route.stops) {
            final KnownNode node = stop.node;

            final Waypoint waypoint = new Waypoint(
                labelFor(stop),
                node.x,
                node.y,
                node.z,
                new Color(VWConfig.waypointColor),
                Waypoint.Type.Normal,
                node.dim);

            try {
                WaypointStore.instance()
                    .save(waypoint);
                created++;
            } catch (Exception e) {
                VisWaypoints.LOG.warn("Failed to create waypoint at " + node.key(), e);
            }
        }

        return created;
    }

    /**
     * Removes every waypoint this mod created. Returns how many were removed.
     */
    public static int clearAll() {
        if (!isJourneyMapAvailable()) return 0;

        int removed = 0;

        try {
            // Copy first: removing while iterating the store's own collection is asking for trouble.
            final List<Waypoint> ours = new ArrayList<>();

            for (Waypoint waypoint : WaypointStore.instance()
                .getAll()) {
                if (waypoint == null) continue;

                final String name = waypoint.getName();
                if (name != null && name.startsWith(PREFIX)) ours.add(waypoint);
            }

            for (Waypoint waypoint : ours) {
                WaypointStore.instance()
                    .remove(waypoint);
                removed++;
            }
        } catch (Exception e) {
            VisWaypoints.LOG.warn("Failed while clearing vis waypoints", e);
        }

        return removed;
    }

    /** True if any of our waypoints currently exist. */
    public static boolean hasAny() {
        if (!isJourneyMapAvailable()) return false;

        try {
            for (Waypoint waypoint : WaypointStore.instance()
                .getAll()) {
                if (waypoint == null) continue;

                final String name = waypoint.getName();
                if (name != null && name.startsWith(PREFIX)) return true;
            }
        } catch (Exception ignored) {
            return false;
        }

        return false;
    }

    /**
     * Builds a label like "❈ Vis: Aer 12, Ignis 5 (~est)" so the map tells you why you're going there
     * and how much to trust the numbers.
     */
    private static String labelFor(Router.Stop stop) {
        final StringBuilder label = new StringBuilder(PREFIX).append(": ");

        boolean first = true;
        for (Map.Entry<String, Integer> take : stop.take.entrySet()) {
            if (!first) label.append(", ");
            label.append(capitalise(take.getKey()))
                .append(' ')
                .append(take.getValue());
            first = false;
        }

        switch (stop.node.freshness) {
            case LIVE:
                label.append(" (live)");
                break;
            case STALE_NO_REGEN:
                label.append(" (fading, ~")
                    .append(stop.node.ageMinutes())
                    .append("m old)");
                break;
            case ESTIMATED:
            default:
                label.append(" (~est)");
                break;
        }

        return label.toString();
    }

    private static String capitalise(String tag) {
        if (tag == null || tag.isEmpty()) return "";
        return Character.toUpperCase(tag.charAt(0)) + tag.substring(1);
    }
}

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
     * Creates a single waypoint for one stop -- the progressive mode, where only the node you're
     * heading to next is on the map.
     *
     * @param stopNumber 1-based count of stops made so far on this run
     * @param moreAfter  how many further stops the current plan expects after this one
     */
    public static boolean createSingle(Router.Stop stop, int stopNumber, int moreAfter) {
        if (!isJourneyMapAvailable()) return false;

        final KnownNode node = stop.node;
        final StringBuilder label = new StringBuilder(PREFIX).append(" #")
            .append(stopNumber)
            .append(": ");

        boolean first = true;
        for (Map.Entry<String, Integer> take : stop.take.entrySet()) {
            if (!first) label.append(", ");
            label.append(capitalise(take.getKey()))
                .append(' ')
                .append(take.getValue());
            first = false;
        }

        if (moreAfter > 0) {
            label.append(" (+")
                .append(moreAfter)
                .append(" more)");
        }

        switch (node.freshness) {
            case LIVE:
                label.append(" (live)");
                break;
            case STALE_NO_REGEN:
                label.append(" (fading)");
                break;
            case ESTIMATED:
            default:
                label.append(" (~est)");
                break;
        }

        try {
            final Waypoint waypoint = new Waypoint(
                label.toString(),
                node.x,
                node.y,
                node.z,
                new Color(VWConfig.waypointColor),
                Waypoint.Type.Normal,
                node.dim);

            WaypointStore.instance()
                .save(waypoint);
            return true;
        } catch (Exception e) {
            VisWaypoints.LOG.warn("Failed to create waypoint at " + node.key(), e);
            return false;
        }
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
     * Builds a label like "\u2748 Vis 2/4: Aer 12, Ordo 2 (~est)".
     *
     * The leading "2/4" is the point of this: JourneyMap lists waypoints by distance, not by the
     * order we planned, so without a visible sequence the player walks the route out of order. Since
     * each node hands over everything it can, visiting out of order shifts which stop gives what --
     * the numbers only line up if you follow the sequence.
     */
    private static String labelFor(Router.Stop stop) {
        final StringBuilder label = new StringBuilder(PREFIX);

        if (stop.total > 1) {
            label.append(' ')
                .append(stop.index)
                .append('/')
                .append(stop.total);
        }

        label.append(": ");

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

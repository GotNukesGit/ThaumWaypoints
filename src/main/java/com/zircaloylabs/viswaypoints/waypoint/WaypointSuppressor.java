package com.zircaloylabs.viswaypoints.waypoint;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zircaloylabs.viswaypoints.VisWaypoints;

import cpw.mods.fml.common.Loader;
import journeymap.client.model.Waypoint;
import journeymap.client.waypoint.WaypointStore;

/**
 * Temporarily hides every other JourneyMap waypoint for the duration of a run, so the node you're
 * being sent to is the only thing on the map, then puts them all back afterwards.
 *
 * The dangerous part of this feature is the "puts them all back". Disabling a waypoint writes to
 * disk, so if the game crashed or the player quit mid-run, their waypoints would silently stay hidden
 * forever -- which would be a genuinely bad thing to do to somebody's world. So the set of waypoints
 * we hid is written to a file the moment we hide them, and it's restored on the next startup as well
 * as on disconnect. The file existing at all means "we owe this person their waypoints back".
 *
 * We only ever touch waypoints that were *enabled* to begin with, so a waypoint the player had
 * already switched off doesn't get switched on by our restore.
 */
public final class WaypointSuppressor {

    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<ArrayList<String>>() {}.getType();

    private static final Set<String> hidden = new HashSet<>();
    private static Path storePath;

    private WaypointSuppressor() {}

    public static void init(Path configDir) {
        storePath = configDir.resolve("viswaypoints-hidden.json");

        // Crash recovery: anything still listed here was hidden by us and never given back.
        final List<String> orphaned = readStore();
        if (!orphaned.isEmpty()) {
            hidden.addAll(orphaned);
            VisWaypoints.LOG.info("Restoring " + orphaned.size() + " waypoint(s) hidden by a previous session");
            restore();
        }
    }

    /** Hides every enabled waypoint that isn't one of ours. */
    public static void suppress() {
        if (!Loader.isModLoaded("journeymap")) return;

        try {
            hidden.clear();

            for (Waypoint waypoint : new ArrayList<>(
                WaypointStore.instance()
                    .getAll())) {
                if (waypoint == null || !waypoint.isEnable()) continue;

                final String name = waypoint.getName();
                if (name != null && name.startsWith(WaypointService.PREFIX)) continue;

                waypoint.setEnable(false);
                WaypointStore.instance()
                    .save(waypoint);

                hidden.add(waypoint.getId());
            }

            // Persist immediately: from here until restore(), we owe these back.
            writeStore();

            if (!hidden.isEmpty()) {
                VisWaypoints.LOG.debug("Hid " + hidden.size() + " waypoint(s) for the duration of the run");
            }
        } catch (Exception e) {
            VisWaypoints.LOG.warn("Could not hide existing waypoints", e);
        }
    }

    /** Puts back every waypoint we hid. Safe to call when nothing is hidden. */
    public static void restore() {
        if (hidden.isEmpty()) {
            clearStore();
            return;
        }

        if (!Loader.isModLoaded("journeymap")) return;

        try {
            for (Waypoint waypoint : new ArrayList<>(
                WaypointStore.instance()
                    .getAll())) {
                if (waypoint == null) continue;
                if (!hidden.contains(waypoint.getId())) continue;

                waypoint.setEnable(true);
                WaypointStore.instance()
                    .save(waypoint);
            }
        } catch (Exception e) {
            VisWaypoints.LOG.warn("Could not restore hidden waypoints", e);
        } finally {
            hidden.clear();
            clearStore();
        }
    }

    public static boolean hasHiddenWaypoints() {
        return !hidden.isEmpty();
    }

    private static void writeStore() {
        if (storePath == null) return;

        try (FileWriter writer = new FileWriter(storePath.toFile())) {
            GSON.toJson(new ArrayList<>(hidden), writer);
        } catch (IOException e) {
            VisWaypoints.LOG.warn("Could not record hidden waypoints; they may not be restored on a crash", e);
        }
    }

    private static List<String> readStore() {
        if (storePath == null || !Files.exists(storePath)) return new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(storePath)) {
            final List<String> ids = GSON.fromJson(reader, TYPE);
            return ids == null ? new ArrayList<String>() : ids;
        } catch (Exception e) {
            VisWaypoints.LOG.warn("Could not read the hidden-waypoint record", e);
            return new ArrayList<>();
        }
    }

    private static void clearStore() {
        if (storePath == null) return;

        try {
            Files.deleteIfExists(storePath);
        } catch (IOException ignored) {
            // Not fatal: a stale file just means we try to restore already-visible waypoints next boot.
        }
    }
}

package com.zircaloylabs.viswaypoints.node;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.zircaloylabs.viswaypoints.VisWaypoints;

/**
 * Remembers what we last actually observed at each node, so a node you drained an hour ago is
 * modelled from what you left it at -- not from the full reading the Thaumometer took when you first
 * found it.
 *
 * TCNodeTracker only rewrites its snapshot when you re-scan a node with a Thaumometer, so on its own
 * it would keep insisting a node you just emptied is still full. This sidecar closes that gap: every
 * time a node's chunk is loaded we read the tile entity and record the truth here, which naturally
 * captures the "I just drained this" state because you are standing next to it when you do.
 *
 * Stored next to the mod's config as a small JSON file, keyed by dim:x:y:z.
 */
public final class NodeMemory {

    public static class Entry {

        /** Last observed vis per aspect tag. */
        public Map<String, Integer> amounts = new HashMap<>();
        /** Last observed base (max) vis per aspect tag. */
        public Map<String, Integer> base = new HashMap<>();
        /** Wall-clock millis of that observation. */
        public long observedAtMillis;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();
    private static final Type TYPE = new TypeToken<HashMap<String, Entry>>() {}.getType();

    private static final Map<String, Entry> MEMORY = new HashMap<>();
    private static Path storePath;
    private static boolean dirty = false;

    private NodeMemory() {}

    public static void init(Path configDir) {
        storePath = configDir.resolve("viswaypoints-nodes.json");
        load();
    }

    public static Entry recall(String key) {
        return MEMORY.get(key);
    }

    /**
     * Records a node observation, but only when it is authoritative (a live tile-entity read).
     * Estimates are never written back, which would compound approximation on approximation.
     */
    public static void remember(KnownNode node) {
        if (node.freshness != KnownNode.Freshness.LIVE) return;

        final Entry entry = new Entry();
        entry.amounts.putAll(node.aspects);
        entry.base.putAll(node.base);
        entry.observedAtMillis = node.observedAtMillis;

        final Entry previous = MEMORY.put(node.key(), entry);

        if (previous == null || !previous.amounts.equals(entry.amounts)) {
            dirty = true;
        }
    }

    /** Writes to disk only when something actually changed. Called on a slow cadence and on shutdown. */
    public static void saveIfDirty() {
        if (!dirty || storePath == null) return;

        try (FileWriter writer = new FileWriter(storePath.toFile())) {
            GSON.toJson(MEMORY, writer);
            dirty = false;
        } catch (IOException e) {
            VisWaypoints.LOG.warn("Could not save node memory to " + storePath, e);
        }
    }

    private static void load() {
        MEMORY.clear();
        if (storePath == null || !Files.exists(storePath)) return;

        try (BufferedReader reader = Files.newBufferedReader(storePath)) {
            final Map<String, Entry> loaded = GSON.fromJson(reader, TYPE);
            if (loaded != null) MEMORY.putAll(loaded);
        } catch (Exception e) {
            VisWaypoints.LOG.warn("Could not read node memory from " + storePath + "; starting fresh", e);
        }
    }
}

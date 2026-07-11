package com.zircaloylabs.viswaypoints.node;

/**
 * Models Thaumcraft aura-node vis regeneration.
 *
 * Derived from Thaumcraft 4.2.3.5 TileNode#handleRecharge: a node's "regeneration" value is 600 by
 * default, and is overridden by its NodeModifier -- BRIGHT = 400, PALE = 900, FADING = 0 (no regen).
 * The node then regains 1 vis per (regeneration * 75) milliseconds of real time, and that vis is
 * added to a randomly chosen aspect that is currently below its base (max) value.
 *
 * So a normal node recovers 1 vis every 45s; a bright node every 30s; a pale node every 67.5s; and
 * a fading node never recovers. Because Thaumcraft itself applies this as a "catch up" using
 * System.currentTimeMillis() against the node's lastActive stamp when the chunk reloads, wall-clock
 * elapsed time (not ticks) is the correct basis -- regen accrues even while the chunk is unloaded.
 *
 * We can't know which aspect the game will pick for each point (it's random), so when estimating we
 * spread the regenerated vis evenly across the aspects that are below base. That's an approximation
 * of a random process, which is exactly what we want for "roughly how full is this node now".
 */
public final class RegenModel {

    private static final int REGEN_DEFAULT = 600;
    private static final int REGEN_BRIGHT = 400;
    private static final int REGEN_PALE = 900;
    private static final int REGEN_FADING = 0;

    /** Thaumcraft multiplies the regeneration value by this to get milliseconds per 1 vis. */
    private static final int MS_PER_REGEN_UNIT = 75;

    private RegenModel() {}

    /**
     * Milliseconds required for a node with the given modifier to regain a single point of vis.
     * Returns 0 when the node does not regenerate at all (FADING), which callers must treat as
     * "never recovers" rather than "recovers instantly".
     *
     * @param nodeModifier the NodeModifier name as stored by TCNodeTracker ("BRIGHT", "PALE",
     *                     "FADING", or "BLANK"/null for an unmodified node)
     */
    public static long millisPerVis(String nodeModifier) {
        final int regen;

        if (nodeModifier == null) {
            regen = REGEN_DEFAULT;
        } else if ("BRIGHT".equalsIgnoreCase(nodeModifier)) {
            regen = REGEN_BRIGHT;
        } else if ("PALE".equalsIgnoreCase(nodeModifier)) {
            regen = REGEN_PALE;
        } else if ("FADING".equalsIgnoreCase(nodeModifier)) {
            regen = REGEN_FADING;
        } else {
            // "BLANK" and anything unrecognised: an ordinary node.
            regen = REGEN_DEFAULT;
        }

        return (long) regen * MS_PER_REGEN_UNIT;
    }

    /** True when this node can never regain vis, so a drained one stays drained forever. */
    public static boolean neverRegenerates(String nodeModifier) {
        return millisPerVis(nodeModifier) <= 0L;
    }

    /**
     * Total vis a node regains over the given elapsed wall-clock time. This is the pool that is then
     * distributed across whichever aspects are below their base value.
     */
    public static int visRegainedOver(long elapsedMillis, String nodeModifier) {
        final long perVis = millisPerVis(nodeModifier);
        if (perVis <= 0L || elapsedMillis <= 0L) return 0;

        final long regained = elapsedMillis / perVis;
        // Clamp: a very old snapshot can imply an enormous number; callers cap it at the node's
        // remaining headroom anyway, but keep it inside int range here.
        return (int) Math.min(regained, Integer.MAX_VALUE);
    }
}

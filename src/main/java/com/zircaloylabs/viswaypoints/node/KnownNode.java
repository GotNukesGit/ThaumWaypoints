package com.zircaloylabs.viswaypoints.node;

import java.util.HashMap;
import java.util.Map;

/**
 * A Thaumcraft aura node we know about, with our best estimate of its current contents.
 *
 * "Freshness" matters a lot here and is surfaced deliberately rather than hidden: a node whose chunk
 * is currently loaded can be read exactly from its tile entity, while a node we last saw days ago is
 * an estimate built from the last known amounts plus modelled regeneration. The UI shows which is
 * which so the numbers are never presented as more certain than they are.
 */
public class KnownNode {

    public enum Freshness {
        /** Read directly from the live TileNode this tick. Exact. */
        LIVE,
        /** Last-known amounts, projected forward with RegenModel. Approximate. */
        ESTIMATED,
        /** Last-known amounts, but the node cannot regenerate (FADING). Stale but not growing. */
        STALE_NO_REGEN
    }

    public final int dim;
    public final int x;
    public final int y;
    public final int z;

    /** NodeType name, e.g. NORMAL / UNSTABLE / DARK / TAINTED / HUNGRY / PURE. */
    public final String type;
    /** NodeModifier name, e.g. BRIGHT / PALE / FADING / BLANK. */
    public final String modifier;

    /** Estimated current vis, keyed by primal aspect tag (aer, aqua, ignis, ordo, perditio, terra). */
    public final Map<String, Integer> aspects = new HashMap<>();

    /** Our best guess of each aspect's maximum (base) value; may be absent for never-seen-live nodes. */
    public final Map<String, Integer> base = new HashMap<>();

    public Freshness freshness = Freshness.ESTIMATED;

    /** Wall-clock millis when these amounts were last actually observed (scan or live read). */
    public long observedAtMillis;

    public KnownNode(int dim, int x, int y, int z, String type, String modifier) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.modifier = modifier;
    }

    public int amountOf(String aspectTag) {
        final Integer amount = aspects.get(aspectTag);
        return amount == null ? 0 : amount;
    }

    /** Stable identity for a node position, used for waypoint naming and memory keys. */
    public String key() {
        return dim + ":" + x + ":" + y + ":" + z;
    }

    /** Squared distance to a point; squared avoids a sqrt when only ordering matters. */
    public double distanceSqTo(double px, double py, double pz) {
        final double dx = (x + 0.5) - px;
        final double dy = (y + 0.5) - py;
        final double dz = (z + 0.5) - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceTo(double px, double py, double pz) {
        return Math.sqrt(distanceSqTo(px, py, pz));
    }

    /** How old the observation is, in whole minutes. Useful for "seen 3h ago" labelling. */
    public long ageMinutes() {
        return Math.max(0L, (System.currentTimeMillis() - observedAtMillis) / 60_000L);
    }
}

package com.zircaloylabs.viswaypoints.route;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.item.ItemStack;

import com.zircaloylabs.viswaypoints.config.VWConfig;
import com.zircaloylabs.viswaypoints.wand.WandVis;

import thaumcraft.api.aspects.Aspect;

/**
 * The state of one "route me to some nodes and refill my wand" run.
 *
 * The session records which aspects the wand was short of when the run started. It then decides, each
 * time it's polled, whether the run is finished -- which happens under either of two conditions:
 *
 * - the wand is completely full (every primal at capacity), or
 * - every aspect the run was created to top up has been topped up, even if some *other* aspect has
 * since drained (you spent Ordo casting on the way home; that shouldn't resurrect the waypoints
 * you already finished with).
 *
 * Both conditions are configurable, since some players will want the waypoints to persist until the
 * wand is genuinely full.
 */
public class RefillSession {

    /** Aspect tags this run set out to refill, and how much was missing at the time. */
    private final Map<String, Integer> targetDeficit = new HashMap<>();

    private final int waypointsCreated;
    private boolean finished = false;

    public RefillSession(Map<Aspect, Integer> deficit, int waypointsCreated) {
        for (Map.Entry<Aspect, Integer> e : deficit.entrySet()) {
            targetDeficit.put(
                e.getKey()
                    .getTag(),
                e.getValue());
        }
        this.waypointsCreated = waypointsCreated;
    }

    public int waypointsCreated() {
        return waypointsCreated;
    }

    public boolean isFinished() {
        return finished;
    }

    /**
     * Re-checks the wand against this run's goal.
     *
     * @param wandStack the wand to measure, or null if the player isn't holding one (in which case we
     *                  simply don't conclude anything -- putting the wand away must not wipe the run)
     * @return true if the run has just completed
     */
    public boolean pollComplete(ItemStack wandStack) {
        if (finished) return false;
        if (wandStack == null) return false;

        final Map<Aspect, Integer> current = WandVis.deficit(wandStack);

        if (current.isEmpty()) {
            // Wand is completely full: always ends the run.
            finished = true;
            return true;
        }

        if (!VWConfig.clearWhenTargetAspectsFull) return false;

        // Otherwise: done as soon as nothing we originally needed is still missing.
        for (Map.Entry<Aspect, Integer> stillMissing : current.entrySet()) {
            if (
                targetDeficit.containsKey(
                    stillMissing.getKey()
                        .getTag())
            ) {
                return false;
            }
        }

        finished = true;
        return true;
    }
}

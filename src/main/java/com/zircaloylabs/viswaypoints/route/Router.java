package com.zircaloylabs.viswaypoints.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zircaloylabs.viswaypoints.config.VWConfig;
import com.zircaloylabs.viswaypoints.node.KnownNode;

import thaumcraft.api.aspects.Aspect;

/**
 * Chooses which nodes to send the player to in order to refill the wand.
 *
 * This is a set-cover problem (cover the wand's per-aspect deficit using the vis available in nodes)
 * with a distance penalty, and exact set cover is NP-hard -- so we use the standard greedy
 * approximation, which is both fast and produces sensible routes in practice: repeatedly take the
 * node with the best "useful vis per unit of travel" ratio, subtract what it can give, and continue
 * until the deficit is covered or we run out of candidates.
 *
 * Travel is measured from the player to the first node, then node-to-node, so a cluster of three
 * nodes near each other is correctly preferred over three nodes scattered in different directions.
 */
public final class Router {

    private Router() {}

    public static class Stop {

        public final KnownNode node;
        /** How much vis of each aspect we expect to take from this node, in whole node-vis units. */
        public final Map<String, Integer> take = new HashMap<>();

        public Stop(KnownNode node) {
            this.node = node;
        }

        public int totalTake() {
            int sum = 0;
            for (int amount : take.values()) sum += amount;
            return sum;
        }
    }

    public static class Route {

        public final List<Stop> stops = new ArrayList<>();
        /** Aspect tags the known nodes cannot satisfy, mapped to how much is still missing. */
        public final Map<String, Integer> uncovered = new HashMap<>();

        public boolean isEmpty() {
            return stops.isEmpty();
        }

        public boolean fullyCovers() {
            return uncovered.isEmpty();
        }
    }

    /**
     * Plans a route covering the given deficit.
     *
     * @param deficit    per-primal shortfall in whole node-vis units
     * @param candidates every node we know about in this dimension, with estimated contents
     * @param px,py,pz   the player's position
     */
    public static Route plan(Map<Aspect, Integer> deficit, List<KnownNode> candidates, double px, double py,
            double pz) {

        final Route route = new Route();

        // Work in aspect tags; that's how node contents are keyed.
        final Map<String, Integer> remaining = new HashMap<>();
        for (Map.Entry<Aspect, Integer> e : deficit.entrySet()) {
            remaining.put(e.getKey().getTag(), e.getValue());
        }
        if (remaining.isEmpty()) return route;

        final double maxDistance = VWConfig.maxSearchRadius;
        final int maxStops = VWConfig.maxWaypoints;
        final int reserve = VWConfig.reservePerNode;

        // Only nodes within range that hold something we actually need.
        final List<KnownNode> pool = new ArrayList<>();
        for (KnownNode node : candidates) {
            if (node.distanceTo(px, py, pz) > maxDistance) continue;
            if (usefulVis(node, remaining, reserve) > 0) pool.add(node);
        }

        double fromX = px;
        double fromY = py;
        double fromZ = pz;

        while (!remaining.isEmpty() && route.stops.size() < maxStops && !pool.isEmpty()) {
            KnownNode best = null;
            double bestScore = 0d;
            int bestUseful = 0;

            for (KnownNode node : pool) {
                final int useful = usefulVis(node, remaining, reserve);
                if (useful <= 0) continue;

                // Distance from where we'd already be, not from the player, so clusters win.
                final double distance = Math.max(1d, node.distanceTo(fromX, fromY, fromZ));

                // Useful vis per unit of travel. sqrt softens the distance penalty so a much richer
                // node isn't rejected purely for being somewhat further away.
                final double score = useful / Math.sqrt(distance);

                if (score > bestScore) {
                    bestScore = score;
                    best = node;
                    bestUseful = useful;
                }
            }

            if (best == null || bestUseful <= 0) break;

            final Stop stop = new Stop(best);

            for (Map.Entry<String, Integer> need : new HashMap<>(remaining).entrySet()) {
                final String tag = need.getKey();
                final int available = Math.max(0, best.amountOf(tag) - reserve);
                if (available <= 0) continue;

                final int taken = Math.min(available, need.getValue());
                if (taken <= 0) continue;

                stop.take.put(tag, taken);

                final int left = need.getValue() - taken;
                if (left > 0) remaining.put(tag, left);
                else remaining.remove(tag);
            }

            route.stops.add(stop);
            pool.remove(best);

            fromX = best.x + 0.5;
            fromY = best.y + 0.5;
            fromZ = best.z + 0.5;
        }

        route.uncovered.putAll(remaining);
        return route;
    }

    /**
     * How much vis in this node counts toward what we still need.
     *
     * {@code reserve} is a planning floor only -- it cannot and does not prevent you from draining a
     * node. Thaumcraft handles node protection itself (Node Preservation research stops a wand taking
     * an aspect's last point), so this defaults to a token 1 and simply keeps the router from counting
     * on the very last point of vis in a node.
     */
    private static int usefulVis(KnownNode node, Map<String, Integer> remaining, int reserve) {
        int useful = 0;

        for (Map.Entry<String, Integer> need : remaining.entrySet()) {
            final int available = Math.max(0, node.amountOf(need.getKey()) - reserve);
            useful += Math.min(available, need.getValue());
        }

        return useful;
    }
}

package com.zircaloylabs.viswaypoints.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zircaloylabs.viswaypoints.config.VWConfig;
import com.zircaloylabs.viswaypoints.node.KnownNode;

import thaumcraft.api.aspects.Aspect;

/**
 * Plans a refill run.
 *
 * The important thing to understand -- and the thing that makes a naive plan wrong -- is that
 * Thaumcraft does not let you drain a chosen aspect from a node. TileNode picks a *random* aspect
 * from the set the node holds intersected with the set your wand still has room for, and keeps doing
 * that while you hold right-click. So you cannot "take 2 Ordo from this node and 10 from that one":
 * you arrive at a node and absorb everything it can give you across every aspect you are short of,
 * until either the node runs dry or your wand is full for those aspects.
 *
 * That has two consequences this planner is built around:
 *
 * 1. A node's contribution is min(available, still-needed) for EVERY aspect at once, not a
 * per-aspect allocation we get to choose. So the plan must simulate full absorption at each
 * stop and recompute the remaining deficit before considering the next one.
 *
 * 2. Because each stop takes as much as it can, the TOTAL collected from a given set of nodes is
 * the same no matter what order you visit them in -- order only changes how far you walk, and
 * how the take is split between stops. That is what lets us separate the two problems: pick a
 * good SET first (coverage), then optimise the ORDER purely as a shortest-path problem, then
 * re-simulate to get honest per-stop numbers.
 *
 * The waypoints are numbered so the route is actually followable in JourneyMap, which otherwise just
 * lists them by distance and quietly destroys the plan.
 */
public final class Router {

    private Router() {}

    public static class Stop {

        public final KnownNode node;

        /** What you can expect to absorb here, given everything you'll have picked up before it. */
        public final Map<String, Integer> take = new HashMap<>();

        /** 1-based position in the route. */
        public int index;

        /** Total number of stops, so labels can read "2/4". */
        public int total;

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

        /** Aspect tags the known nodes can't satisfy, and how much is still missing. */
        public final Map<String, Integer> uncovered = new HashMap<>();

        /** Total path length: player -> stop 1 -> stop 2 -> ... Used only for feedback. */
        public double travelDistance;

        public boolean isEmpty() {
            return stops.isEmpty();
        }

        public boolean fullyCovers() {
            return uncovered.isEmpty();
        }
    }

    public static Route plan(Map<Aspect, Integer> deficit, List<KnownNode> candidates, double px, double py,
        double pz) {

        final Route route = new Route();

        final Map<String, Integer> need = new HashMap<>();
        for (Map.Entry<Aspect, Integer> e : deficit.entrySet()) {
            need.put(
                e.getKey()
                    .getTag(),
                e.getValue());
        }
        if (need.isEmpty()) return route;

        final int reserve = VWConfig.reservePerNode;
        final double maxDistance = VWConfig.maxSearchRadius;
        final int maxStops = VWConfig.maxWaypoints;

        final List<KnownNode> pool = new ArrayList<>();
        for (KnownNode node : candidates) {
            if (node.distanceTo(px, py, pz) > maxDistance) continue;
            if (absorbableFrom(node, need, reserve) > 0) pool.add(node);
        }

        // 1. Choose the set. Greedy: repeatedly take the node offering the most useful vis per unit
        // of travel from where we'd already be, simulating full absorption as we go.
        final List<KnownNode> chosen = new ArrayList<>();
        final Map<String, Integer> remaining = new HashMap<>(need);

        double atX = px;
        double atY = py;
        double atZ = pz;

        while (!remaining.isEmpty() && chosen.size() < maxStops && !pool.isEmpty()) {
            KnownNode best = null;
            double bestScore = 0d;

            for (KnownNode node : pool) {
                final int useful = absorbableFrom(node, remaining, reserve);
                if (useful <= 0) continue;

                final double distance = Math.max(1d, node.distanceTo(atX, atY, atZ));

                // Useful vis per unit of travel. The sqrt softens the distance penalty so a node that
                // covers far more of the deficit isn't rejected just for being somewhat further out.
                final double score = useful / Math.sqrt(distance);

                if (score > bestScore) {
                    bestScore = score;
                    best = node;
                }
            }

            if (best == null) break;

            absorb(best, remaining, reserve);
            chosen.add(best);
            pool.remove(best);

            atX = best.x + 0.5;
            atY = best.y + 0.5;
            atZ = best.z + 0.5;
        }

        if (chosen.isEmpty()) {
            route.uncovered.putAll(remaining);
            return route;
        }

        // 2. Optimise the order. Coverage is order-independent, so this is purely "walk less":
        // nearest-neighbour for a starting tour, then 2-opt to iron out the crossings.
        List<KnownNode> ordered = nearestNeighbourTour(chosen, px, py, pz);
        ordered = twoOpt(ordered, px, py, pz);

        // 3. Re-simulate absorption in the real visit order, and drop any stop that ends up
        // contributing nothing (the greedy set can include nodes made redundant by a better order).
        List<Stop> stops = simulate(ordered, need, reserve);

        boolean pruned = true;
        while (pruned && !stops.isEmpty()) {
            pruned = false;

            for (int i = 0; i < stops.size(); i++) {
                if (
                    stops.get(i)
                        .totalTake() > 0
                ) continue;

                final List<KnownNode> trimmed = new ArrayList<>();
                for (int j = 0; j < stops.size(); j++) {
                    if (j != i) trimmed.add(stops.get(j).node);
                }

                stops = simulate(trimmed, need, reserve);
                pruned = true;
                break;
            }
        }

        // 4. Number them so the route can actually be followed on the map.
        for (int i = 0; i < stops.size(); i++) {
            stops.get(i).index = i + 1;
            stops.get(i).total = stops.size();
        }

        route.stops.addAll(stops);
        route.travelDistance = pathLength(nodesOf(stops), px, py, pz);

        final Map<String, Integer> left = new HashMap<>(need);
        for (Stop stop : stops) {
            for (Map.Entry<String, Integer> taken : stop.take.entrySet()) {
                final Integer outstanding = left.get(taken.getKey());
                if (outstanding == null) continue;

                final int after = outstanding - taken.getValue();
                if (after > 0) left.put(taken.getKey(), after);
                else left.remove(taken.getKey());
            }
        }
        route.uncovered.putAll(left);

        return route;
    }

    /** Walks the node list in order, taking everything each node can give against the running deficit. */
    private static List<Stop> simulate(List<KnownNode> nodes, Map<String, Integer> need, int reserve) {
        final List<Stop> stops = new ArrayList<>();
        final Map<String, Integer> remaining = new HashMap<>(need);

        for (KnownNode node : nodes) {
            final Stop stop = new Stop(node);

            for (Map.Entry<String, Integer> outstanding : new HashMap<>(remaining).entrySet()) {
                final String tag = outstanding.getKey();
                final int available = Math.max(0, node.amountOf(tag) - reserve);
                if (available <= 0) continue;

                final int taken = Math.min(available, outstanding.getValue());
                if (taken <= 0) continue;

                stop.take.put(tag, taken);

                final int after = outstanding.getValue() - taken;
                if (after > 0) remaining.put(tag, after);
                else remaining.remove(tag);
            }

            stops.add(stop);
        }

        return stops;
    }

    /** How much of what we still need this node could hand over. */
    private static int absorbableFrom(KnownNode node, Map<String, Integer> need, int reserve) {
        int total = 0;

        for (Map.Entry<String, Integer> outstanding : need.entrySet()) {
            final int available = Math.max(0, node.amountOf(outstanding.getKey()) - reserve);
            total += Math.min(available, outstanding.getValue());
        }

        return total;
    }

    /** Applies this node's full contribution to the running deficit. */
    private static void absorb(KnownNode node, Map<String, Integer> need, int reserve) {
        for (Map.Entry<String, Integer> outstanding : new HashMap<>(need).entrySet()) {
            final String tag = outstanding.getKey();
            final int available = Math.max(0, node.amountOf(tag) - reserve);
            if (available <= 0) continue;

            final int after = outstanding.getValue() - Math.min(available, outstanding.getValue());
            if (after > 0) need.put(tag, after);
            else need.remove(tag);
        }
    }

    private static List<KnownNode> nearestNeighbourTour(List<KnownNode> nodes, double px, double py, double pz) {
        final List<KnownNode> unvisited = new ArrayList<>(nodes);
        final List<KnownNode> tour = new ArrayList<>();

        double atX = px;
        double atY = py;
        double atZ = pz;

        while (!unvisited.isEmpty()) {
            KnownNode nearest = null;
            double best = Double.MAX_VALUE;

            for (KnownNode node : unvisited) {
                final double distance = node.distanceSqTo(atX, atY, atZ);
                if (distance < best) {
                    best = distance;
                    nearest = node;
                }
            }

            tour.add(nearest);
            unvisited.remove(nearest);

            atX = nearest.x + 0.5;
            atY = nearest.y + 0.5;
            atZ = nearest.z + 0.5;
        }

        return tour;
    }

    /**
     * 2-opt: repeatedly reverse a segment of the tour if doing so shortens the total path. Cheap, and
     * it removes the obvious "walk past it, come back for it" crossings that nearest-neighbour leaves.
     */
    private static List<KnownNode> twoOpt(List<KnownNode> tour, double px, double py, double pz) {
        if (tour.size() < 3) return tour;

        List<KnownNode> best = new ArrayList<>(tour);
        double bestLength = pathLength(best, px, py, pz);

        boolean improved = true;
        int guard = 0;

        while (improved && guard++ < 50) {
            improved = false;

            for (int i = 0; i < best.size() - 1; i++) {
                for (int k = i + 1; k < best.size(); k++) {
                    final List<KnownNode> candidate = new ArrayList<>(best);

                    // Reverse the segment [i..k].
                    for (int a = i, b = k; a < b; a++, b--) {
                        final KnownNode swap = candidate.get(a);
                        candidate.set(a, candidate.get(b));
                        candidate.set(b, swap);
                    }

                    final double length = pathLength(candidate, px, py, pz);
                    if (length < bestLength - 0.001) {
                        best = candidate;
                        bestLength = length;
                        improved = true;
                    }
                }
            }
        }

        return best;
    }

    private static double pathLength(List<KnownNode> tour, double px, double py, double pz) {
        double total = 0d;

        double atX = px;
        double atY = py;
        double atZ = pz;

        for (KnownNode node : tour) {
            total += node.distanceTo(atX, atY, atZ);
            atX = node.x + 0.5;
            atY = node.y + 0.5;
            atZ = node.z + 0.5;
        }

        return total;
    }

    private static List<KnownNode> nodesOf(List<Stop> stops) {
        final List<KnownNode> nodes = new ArrayList<>();
        for (Stop stop : stops) nodes.add(stop.node);
        return nodes;
    }
}

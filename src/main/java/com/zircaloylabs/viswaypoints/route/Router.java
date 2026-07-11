package com.zircaloylabs.viswaypoints.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zircaloylabs.viswaypoints.config.VWConfig;
import com.zircaloylabs.viswaypoints.node.KnownNode;

import thaumcraft.api.aspects.Aspect;

/**
 * Plans a refill run: which nodes to visit, and in what order.
 *
 * Two things drive the design.
 *
 * First, Thaumcraft won't let you drain a chosen aspect. TileNode picks a random aspect from the set
 * the node holds intersected with the set your wand has room for, and repeats while you hold
 * right-click. So you don't get to "take 2 Ordo here and 10 there" -- you arrive and absorb
 * everything the node can give you across every aspect you're short of. Each node's contribution is
 * therefore min(available, still-needed) for every aspect at once.
 *
 * Second -- and this is what a naive planner gets badly wrong -- picking nodes one at a time is
 * myopic. A greedy "best next node" will happily send you east to one rich node and then drag you
 * back west for the rest, when two ordinary nodes to the west would have covered everything for a
 * fraction of the walking. Distance to the *next* node tells you nothing about whether it sits near
 * the others you'll still need.
 *
 * So this doesn't pick nodes one at a time. It searches over whole *combinations* of nodes, and
 * scores each complete candidate route by what it actually costs you:
 *
 * cost = total distance walked + (stopPenaltyBlocks x number of stops)
 *
 * The cheapest combination that covers the shortfall wins. That naturally prefers a tight cluster of
 * two or three nodes over one distant monster, without needing any special "clustering" rule -- the
 * cluster simply has a shorter tour. The stop penalty is what expresses "a stop costs me something
 * even if it's close", and it's configurable because that trade-off is a matter of taste.
 *
 * Combinations up to maxWaypoints over a pruned pool of the most promising candidates is a few
 * thousand evaluations at most, which is nothing for a keypress.
 */
public final class Router {

    private Router() {}

    public static class Stop {

        public final KnownNode node;

        /** What you can expect to absorb here, given everything picked up before it. */
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

        /** Total path length: player -> stop 1 -> stop 2 -> ... */
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
        final int maxStops = Math.max(1, VWConfig.maxWaypoints);

        final List<KnownNode> pool = prune(candidates, need, reserve, px, py, pz);

        if (pool.isEmpty()) {
            route.uncovered.putAll(need);
            return route;
        }

        // Search every combination up to maxStops, keeping the cheapest that covers the most.
        final Search best = new Search();
        final List<KnownNode> working = new ArrayList<>();

        for (int size = 1; size <= Math.min(maxStops, pool.size()); size++) {
            combine(pool, 0, size, working, need, reserve, px, py, pz, best);

            // Once we can fully cover the shortfall with N stops, adding more can only cost more --
            // every extra stop adds both travel and the stop penalty. So stop looking.
            if (best.fullyCovers) break;
        }

        if (best.nodes == null) {
            route.uncovered.putAll(need);
            return route;
        }

        // Order the winning set for the shortest walk, then work out what each stop actually gives.
        List<KnownNode> ordered = nearestNeighbourTour(best.nodes, px, py, pz);
        ordered = twoOpt(ordered, px, py, pz);

        List<Stop> stops = simulate(ordered, need, reserve);
        stops = pruneRedundant(stops, need, reserve);

        for (int i = 0; i < stops.size(); i++) {
            stops.get(i).index = i + 1;
            stops.get(i).total = stops.size();
        }

        route.stops.addAll(stops);
        route.travelDistance = pathLength(nodesOf(stops), px, py, pz);
        route.uncovered.putAll(outstandingAfter(stops, need));

        return route;
    }

    /** The best combination found so far. */
    private static class Search {

        List<KnownNode> nodes;
        int coverage = -1;
        double cost = Double.MAX_VALUE;
        boolean fullyCovers = false;
    }

    /**
     * Walks every combination of the given size, scoring each as a complete route.
     *
     * Better coverage always wins; among equally-covering sets, the cheaper route wins. That ordering
     * matters: a cheap route that leaves you short isn't a bargain.
     */
    private static void combine(List<KnownNode> pool, int start, int remaining, List<KnownNode> working,
        Map<String, Integer> need, int reserve, double px, double py, double pz, Search best) {

        if (remaining == 0) {
            final int coverage = coverageOf(working, need, reserve);
            if (coverage <= 0) return;

            final double cost = pathLength(nearestNeighbourTour(working, px, py, pz), px, py, pz)
                + (double) VWConfig.stopPenaltyBlocks * working.size();

            final boolean better = coverage > best.coverage || (coverage == best.coverage && cost < best.cost);

            if (better) {
                best.nodes = new ArrayList<>(working);
                best.coverage = coverage;
                best.cost = cost;
                best.fullyCovers = coverage >= totalNeeded(need);
            }

            return;
        }

        for (int i = start; i <= pool.size() - remaining; i++) {
            working.add(pool.get(i));
            combine(pool, i + 1, remaining - 1, working, need, reserve, px, py, pz, best);
            working.remove(working.size() - 1);
        }
    }

    /**
     * Narrows the field to the most promising nodes, so the combination search stays cheap.
     *
     * Ranking uses usefulness against distance, which keeps both the rich-but-far and the
     * modest-but-close in play -- the combination search is what decides between them.
     */
    private static List<KnownNode> prune(List<KnownNode> candidates, Map<String, Integer> need, int reserve, double px,
        double py, double pz) {

        final List<KnownNode> viable = new ArrayList<>();

        for (KnownNode node : candidates) {
            if (isForbiddenType(node)) continue;
            if (node.distanceTo(px, py, pz) > VWConfig.maxSearchRadius) continue;
            if (usefulVis(node, need, reserve) <= 0) continue;

            viable.add(node);
        }

        Collections.sort(viable, new Comparator<KnownNode>() {

            @Override
            public int compare(KnownNode a, KnownNode b) {
                final double sa = usefulVis(a, need, reserve) / Math.sqrt(Math.max(1d, a.distanceTo(px, py, pz)));
                final double sb = usefulVis(b, need, reserve) / Math.sqrt(Math.max(1d, b.distanceTo(px, py, pz)));
                return Double.compare(sb, sa);
            }
        });

        final int limit = Math.min(viable.size(), Math.max(4, VWConfig.candidatePoolSize));
        return new ArrayList<>(viable.subList(0, limit));
    }

    /** Hungry nodes eat blocks and pull you in; tainted ones spread taint. Neither is a place to send someone. */
    private static boolean isForbiddenType(KnownNode node) {
        if (node.type == null) return false;

        if (VWConfig.avoidHungryNodes && "HUNGRY".equalsIgnoreCase(node.type)) return true;
        if (VWConfig.avoidTaintedNodes && "TAINTED".equalsIgnoreCase(node.type)) return true;

        return false;
    }

    /** How much of the shortfall a whole set of nodes can cover between them. */
    private static int coverageOf(List<KnownNode> nodes, Map<String, Integer> need, int reserve) {
        int covered = 0;

        for (Map.Entry<String, Integer> want : need.entrySet()) {
            int available = 0;

            for (KnownNode node : nodes) {
                available += Math.max(0, node.amountOf(want.getKey()) - reserve);
            }

            covered += Math.min(available, want.getValue());
        }

        return covered;
    }

    private static int totalNeeded(Map<String, Integer> need) {
        int total = 0;
        for (int amount : need.values()) total += amount;
        return total;
    }

    /** How much of what we still need this one node could hand over. */
    private static int usefulVis(KnownNode node, Map<String, Integer> need, int reserve) {
        int useful = 0;

        for (Map.Entry<String, Integer> want : need.entrySet()) {
            final int available = Math.max(0, node.amountOf(want.getKey()) - reserve);
            useful += Math.min(available, want.getValue());
        }

        return useful;
    }

    /** Walks the nodes in order, taking everything each can give against the running shortfall. */
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

    /** Drops any stop that, in the final order, turns out to contribute nothing. */
    private static List<Stop> pruneRedundant(List<Stop> stops, Map<String, Integer> need, int reserve) {
        List<Stop> current = stops;
        boolean pruned = true;

        while (pruned && !current.isEmpty()) {
            pruned = false;

            for (int i = 0; i < current.size(); i++) {
                if (
                    current.get(i)
                        .totalTake() > 0
                ) continue;

                final List<KnownNode> trimmed = new ArrayList<>();
                for (int j = 0; j < current.size(); j++) {
                    if (j != i) trimmed.add(current.get(j).node);
                }

                current = simulate(trimmed, need, reserve);
                pruned = true;
                break;
            }
        }

        return current;
    }

    private static Map<String, Integer> outstandingAfter(List<Stop> stops, Map<String, Integer> need) {
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

        return left;
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

    /** Reverses tour segments wherever that shortens the path: kills the "walk past it, come back" crossings. */
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

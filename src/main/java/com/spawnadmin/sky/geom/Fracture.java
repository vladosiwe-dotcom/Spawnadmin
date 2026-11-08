package com.spawnadmin.sky.geom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;

/**
 * Deterministic fracture of the glass sky dome, generated from a single seed.
 *
 * <ol>
 *   <li>Seed points by dart throwing, denser around the impact point, so shards near the
 *       impact are small and the outer ones large.</li>
 *   <li>Voronoi cells by half-plane clipping of the unit disc. Every edge remembers the
 *       neighbour that produced it, which gives the shard adjacency for free.</li>
 *   <li>Crack network: the shared Voronoi edges form a graph. Dijkstra from the impact point
 *       (with noisy weights) gives the time the crack front reaches each junction, so the cracks
 *       propagate and branch naturally along the shard borders.</li>
 *   <li>Hairline branches: short random walks with forks growing from the borders into each
 *       shard, clipped to the shard outline.</li>
 *   <li>Shards: each cell is projected onto the dome as a glass prism and gets its own scripted
 *       motion (fall or hover, launch velocity, spin) and its restore window.</li>
 * </ol>
 */
public final class Fracture {
    public static final int TARGET_CELLS = 170;
    private static final int RIM_SEGMENTS = 96;

    public final long seed;
    public final V2 impact;
    /** Radius (disc units) inside which shards break free and open the rift. */
    public final double openRadius;
    public final List<ShardData> shards;
    public final List<CrackSegment> cracks;
    /** Largest crack-front distance: front = progress * maxFront. */
    public final double maxFront;
    /** Cell adjacency: neighbours[i] lists cells sharing an edge with cell i. */
    public final int[][] neighbours;

    private Fracture(long seed, V2 impact, double openRadius, List<ShardData> shards, List<CrackSegment> cracks,
                     double maxFront, int[][] neighbours) {
        this.seed = seed;
        this.impact = impact;
        this.openRadius = openRadius;
        this.shards = shards;
        this.cracks = cracks;
        this.maxFront = maxFront;
        this.neighbours = neighbours;
    }

    // ================================================================== generation

    public static Fracture generate(long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double ia = rnd.nextDouble() * Math.PI * 2;
        double ir = 0.04 + rnd.nextDouble() * 0.12;
        V2 impact = new V2(ir * StrictMath.cos(ia), ir * StrictMath.sin(ia));
        double openRadius = 0.5 + rnd.nextDouble() * 0.08;

        List<V2> sites = scatterSites(rnd, impact);
        List<Cell> cells = buildCells(sites);

        // ---- crack graph from shared edges
        Map<Long, Integer> nodeIds = new HashMap<>();
        List<V2> nodes = new ArrayList<>();
        List<int[]> edges = new ArrayList<>(); // {nodeA, nodeB, cellA, cellB}
        for (Cell cell : cells) {
            for (int k = 0; k < cell.poly.size(); k++) {
                int other = cell.labels.get(k);
                if (other <= cell.index) {
                    continue; // rim (-1) or already added from the other side
                }
                V2 a = cell.poly.get(k);
                V2 b = cell.poly.get((k + 1) % cell.poly.size());
                if (a.dist(b) < 1e-6) {
                    continue;
                }
                int na = nodeId(nodeIds, nodes, a);
                int nb = nodeId(nodeIds, nodes, b);
                if (na != nb) {
                    edges.add(new int[]{na, nb, cell.index, other});
                }
            }
        }

        // Noisy weights make the front irregular, like real glass fracture.
        double[] weight = new double[edges.size()];
        List<List<int[]>> adj = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            adj.add(new ArrayList<>());
        }
        for (int e = 0; e < edges.size(); e++) {
            int[] ed = edges.get(e);
            double len = nodes.get(ed[0]).dist(nodes.get(ed[1]));
            weight[e] = len * (0.7 + 0.6 * rnd.nextDouble());
            adj.get(ed[0]).add(new int[]{ed[1], e});
            adj.get(ed[1]).add(new int[]{ed[0], e});
        }
        double[] dist = dijkstra(nodes, adj, weight, impact);
        double maxFront = 0;
        for (double d : dist) {
            if (d < Double.MAX_VALUE) {
                maxFront = Math.max(maxFront, d);
            }
        }

        List<CrackSegment> cracks = new ArrayList<>();
        for (int e = 0; e < edges.size(); e++) {
            int[] ed = edges.get(e);
            V2 a = nodes.get(ed[0]);
            V2 b = nodes.get(ed[1]);
            double da = dist[ed[0]] == Double.MAX_VALUE ? maxFront : dist[ed[0]];
            double db = dist[ed[1]] == Double.MAX_VALUE ? maxFront : dist[ed[1]];
            V2 mid = a.lerp(b, 0.5);
            double width = 0.22 + 0.55 * Math.exp(-mid.dist(impact) / 0.22);
            cracks.add(new CrackSegment(a, b, da, db, weight[e], ed[2], ed[3], false, width));
        }

        // ---- hairline branches inside cells
        for (Cell cell : cells) {
            int branches = 1 + rnd.nextInt(3);
            for (int n = 0; n < branches; n++) {
                growHairline(rnd, cell, nodes, nodeIds, dist, maxFront, cracks);
            }
        }

        // ---- shards
        List<ShardData> shards = new ArrayList<>();
        int[][] neighbours = new int[cells.size()][];
        for (Cell cell : cells) {
            shards.add(buildShard(rnd, cell, impact, openRadius));
            neighbours[cell.index] = cell.labels.stream().filter(l -> l >= 0).distinct().mapToInt(Integer::intValue).toArray();
        }
        return new Fracture(seed, impact, openRadius, List.copyOf(shards), List.copyOf(cracks), maxFront, neighbours);
    }

    // ------------------------------------------------------------------ sites

    private static List<V2> scatterSites(SplittableRandom rnd, V2 impact) {
        List<V2> sites = new ArrayList<>();
        sites.add(impact.add(new V2(0.012, 0.004)));
        // Spider-web core: rings of sites around the impact give radial cracks and concentric
        // rings, the signature of a glass pane struck at one point.
        int spokes = 7 + rnd.nextInt(3);
        double ringR = 0.045;
        double twist = rnd.nextDouble() * Math.PI * 2;
        for (int ring = 0; ring < 6; ring++) {
            int count = spokes + ring * 2;
            for (int k = 0; k < count; k++) {
                double a = twist + Math.PI * 2 * (k + 0.5 * (ring % 2)) / count + (rnd.nextDouble() - 0.5) * 0.35;
                double r = ringR * (0.85 + rnd.nextDouble() * 0.3);
                V2 p = impact.add(new V2(r * StrictMath.cos(a), r * StrictMath.sin(a)));
                if (p.length() < 0.985) {
                    sites.add(p);
                }
            }
            ringR *= 1.42;
            twist += 0.15;
        }
        int attempts = 0;
        while (sites.size() < TARGET_CELLS && attempts < 60000) {
            attempts++;
            double r = Math.sqrt(rnd.nextDouble()) * 0.985;
            double a = rnd.nextDouble() * Math.PI * 2;
            V2 p = new V2(r * StrictMath.cos(a), r * StrictMath.sin(a));
            double spacing = 0.035 + 0.085 * Math.min(1.0, p.dist(impact) / 0.9);
            boolean ok = true;
            for (V2 s : sites) {
                if (s.dist(p) < spacing) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                sites.add(p);
            }
        }
        return sites;
    }

    // ------------------------------------------------------------------ voronoi

    private static final class Cell {
        final int index;
        final V2 site;
        List<V2> poly = new ArrayList<>();
        /** labels.get(k) = neighbour cell that produced edge poly[k] -> poly[k+1], or -1 for the rim. */
        List<Integer> labels = new ArrayList<>();

        Cell(int index, V2 site) {
            this.index = index;
            this.site = site;
        }
    }

    private static List<Cell> buildCells(List<V2> sites) {
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < sites.size(); i++) {
            Cell cell = new Cell(i, sites.get(i));
            for (int k = 0; k < RIM_SEGMENTS; k++) {
                double a = Math.PI * 2 * k / RIM_SEGMENTS;
                cell.poly.add(new V2(StrictMath.cos(a), StrictMath.sin(a)));
                cell.labels.add(-1);
            }
            Integer[] order = new Integer[sites.size()];
            for (int j = 0; j < order.length; j++) {
                order[j] = j;
            }
            V2 si = sites.get(i);
            Arrays.sort(order, (p, q) -> Double.compare(si.dist(sites.get(p)), si.dist(sites.get(q))));
            for (int j : order) {
                if (j == i) {
                    continue;
                }
                // A site farther than twice the farthest polygon vertex cannot cut the cell.
                double reach = 0;
                for (V2 v : cell.poly) {
                    reach = Math.max(reach, v.dist(si));
                }
                if (si.dist(sites.get(j)) > 2 * reach) {
                    break;
                }
                clip(cell, si, sites.get(j), j);
                if (cell.poly.size() < 3) {
                    break;
                }
            }
            if (cell.poly.size() >= 3) {
                cells.add(cell);
            }
        }
        // Re-index compactly in case degenerate cells were dropped.
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).index != i) {
                throw new IllegalStateException("degenerate voronoi cell");
            }
        }
        return cells;
    }

    /** Sutherland–Hodgman clip keeping the half-plane closer to {@code si}; labels follow edges. */
    private static void clip(Cell cell, V2 si, V2 sj, int j) {
        V2 n = sj.sub(si);
        V2 m = si.lerp(sj, 0.5);
        List<V2> out = new ArrayList<>();
        List<Integer> outLabels = new ArrayList<>();
        int size = cell.poly.size();
        for (int k = 0; k < size; k++) {
            V2 a = cell.poly.get(k);
            V2 b = cell.poly.get((k + 1) % size);
            int lab = cell.labels.get(k);
            double fa = a.sub(m).dot(n);
            double fb = b.sub(m).dot(n);
            boolean inA = fa <= 0, inB = fb <= 0;
            if (inA) {
                out.add(a);
                if (inB) {
                    outLabels.add(lab);
                } else {
                    outLabels.add(lab);
                    out.add(a.lerp(b, fa / (fa - fb)));
                    outLabels.add(j);
                }
            } else if (inB) {
                out.add(a.lerp(b, fa / (fa - fb)));
                outLabels.add(lab);
            }
        }
        cell.poly = out;
        cell.labels = outLabels;
    }

    // ------------------------------------------------------------------ crack graph

    private static int nodeId(Map<Long, Integer> ids, List<V2> nodes, V2 p) {
        long key = (Math.round(p.x() * 1e5) + 1_000_000L) * 4_000_000L + (Math.round(p.y() * 1e5) + 1_000_000L);
        Integer id = ids.get(key);
        if (id == null) {
            id = nodes.size();
            ids.put(key, id);
            nodes.add(p);
        }
        return id;
    }

    private static double[] dijkstra(List<V2> nodes, List<List<int[]>> adj, double[] weight, V2 impact) {
        double[] dist = new double[nodes.size()];
        Arrays.fill(dist, Double.MAX_VALUE);
        PriorityQueue<double[]> queue = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        // The impact sits inside a cell: seed the front at every corner of the nearest junctions.
        for (int i = 0; i < nodes.size(); i++) {
            double d = nodes.get(i).dist(impact);
            if (d < 0.06) {
                dist[i] = d;
                queue.add(new double[]{d, i});
            }
        }
        if (queue.isEmpty()) {
            int best = 0;
            for (int i = 1; i < nodes.size(); i++) {
                if (nodes.get(i).dist(impact) < nodes.get(best).dist(impact)) {
                    best = i;
                }
            }
            dist[best] = nodes.get(best).dist(impact);
            queue.add(new double[]{dist[best], best});
        }
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int u = (int) top[1];
            if (top[0] > dist[u]) {
                continue;
            }
            for (int[] e : adj.get(u)) {
                double nd = dist[u] + weight[e[1]];
                if (nd < dist[e[0]]) {
                    dist[e[0]] = nd;
                    queue.add(new double[]{nd, e[0]});
                }
            }
        }
        return dist;
    }

    private static void growHairline(SplittableRandom rnd, Cell cell, List<V2> nodes, Map<Long, Integer> nodeIds,
                                     double[] dist, double maxFront, List<CrackSegment> out) {
        int edges = cell.poly.size();
        int k = rnd.nextInt(edges);
        if (cell.labels.get(k) < 0) {
            return; // never start on the rim
        }
        V2 a = cell.poly.get(k);
        V2 b = cell.poly.get((k + 1) % edges);
        double t = 0.2 + 0.6 * rnd.nextDouble();
        V2 start = a.lerp(b, t);
        Integer ia = nodeIds.get(keyOf(a));
        Integer ib = nodeIds.get(keyOf(b));
        double da = ia == null || dist[ia] == Double.MAX_VALUE ? maxFront : dist[ia];
        double db = ib == null || dist[ib] == Double.MAX_VALUE ? maxFront : dist[ib];
        double front = Math.min(da + a.dist(start), db + b.dist(start));

        V2 edgeDir = b.sub(a).normalize();
        V2 inward = new V2(-edgeDir.y(), edgeDir.x()); // CCW polygon: left normal points inside
        if (cell.site.sub(start).dot(inward) < 0) {
            inward = inward.mul(-1);
        }
        walk(rnd, cell, start, inward.rotate((rnd.nextDouble() - 0.5) * 1.2), front, 0, out);
    }

    private static void walk(SplittableRandom rnd, Cell cell, V2 from, V2 dir, double front, int depth,
                             List<CrackSegment> out) {
        int steps = 3 + rnd.nextInt(5) - depth * 2;
        V2 p = from;
        double f = front;
        for (int s = 0; s < steps; s++) {
            double len = 0.008 + rnd.nextDouble() * 0.016;
            dir = dir.rotate((rnd.nextDouble() - 0.5) * 0.9);
            V2 q = p.add(dir.mul(len));
            if (!insideConvex(cell.poly, q, 0.004)) {
                return;
            }
            double w = len * 1.4;
            out.add(new CrackSegment(p, q, f, f + w, w, cell.index, cell.index, true, 0.09 + 0.08 * rnd.nextDouble()));
            f += w;
            p = q;
            if (depth < 2 && rnd.nextDouble() < 0.28) {
                double side = rnd.nextBoolean() ? 1 : -1;
                walk(rnd, cell, p, dir.rotate(side * (0.4 + rnd.nextDouble() * 0.5)), f, depth + 1, out);
            }
        }
    }

    private static long keyOf(V2 p) {
        return (Math.round(p.x() * 1e5) + 1_000_000L) * 4_000_000L + (Math.round(p.y() * 1e5) + 1_000_000L);
    }

    static boolean insideConvex(List<V2> poly, V2 p, double margin) {
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            V2 a = poly.get(i);
            V2 b = poly.get((i + 1) % n);
            V2 e = b.sub(a);
            double c = e.cross(p.sub(a)) / Math.max(e.length(), 1e-12);
            if (c < margin) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ shards

    private static ShardData buildShard(SplittableRandom rnd, Cell cell, V2 impact, double openRadius) {
        V2[] outline = cell.poly.toArray(new V2[0]);
        V2 centroid = polygonCentroid(outline);
        V3 origin = DomeShape.pointAt(centroid, 0);
        V3 normal = DomeShape.normalAt(centroid.x(), centroid.y());
        double h = DomeShape.THICKNESS * 0.5;
        V3[] outer = new V3[outline.length];
        V3[] inner = new V3[outline.length];
        for (int i = 0; i < outline.length; i++) {
            V2 p = outline[i];
            outer[i] = DomeShape.pointAt(p, h).sub(origin);
            inner[i] = DomeShape.pointAt(p, -h).sub(origin);
        }

        double d = centroid.dist(impact);
        double jitter = (rnd.nextDouble() - 0.5) * 0.12;
        boolean detaches = d + jitter < openRadius;
        double outerness = Math.min(1.0, d / openRadius);
        boolean hovers = detaches && rnd.nextDouble() < 0.22 + 0.3 * outerness;
        double detachDelay = 0.15 + outerness * 1.6 + rnd.nextDouble() * 0.35;

        V3 inward = normal.mul(-1);
        V3 tangent = normal.anyPerpendicular();
        V3 bitangent = normal.cross(tangent);
        double ta = rnd.nextDouble() * Math.PI * 2;
        V3 lateral = tangent.mul(Math.cos(ta)).add(bitangent.mul(Math.sin(ta)));
        V3 velocity = inward.mul(2.0 + rnd.nextDouble() * 4.0).add(lateral.mul(rnd.nextDouble() * 2.5));
        V3 spinAxis = lateral.add(normal.mul(rnd.nextDouble() - 0.5)).add(new V3(rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5).mul(0.6)).normalize();
        double spinSpeed = (0.5 + rnd.nextDouble() * 1.7) * (rnd.nextBoolean() ? 1 : -1);
        V3 hoverOffset = inward.mul(5 + rnd.nextDouble() * 16).add(lateral.mul(rnd.nextDouble() * 6));
        double hoverTilt = (0.35 + rnd.nextDouble() * 1.1) * (rnd.nextBoolean() ? 1 : -1);

        // The rift closes like an iris: outer shards return first, the impact zone last.
        double restoreStart = 0.04 + 0.42 * (1.0 - outerness) + rnd.nextDouble() * 0.06;
        double restoreLength = 0.36 + rnd.nextDouble() * 0.08;
        return new ShardData(cell.index, outline, centroid, origin, normal, outer, inner, d, detaches, hovers,
                detachDelay, velocity, spinAxis, spinSpeed, hoverOffset, hoverTilt, restoreStart, restoreLength,
                rnd.nextDouble());
    }

    static V2 polygonCentroid(V2[] poly) {
        double a = 0, cx = 0, cy = 0;
        for (int i = 0; i < poly.length; i++) {
            V2 p = poly[i], q = poly[(i + 1) % poly.length];
            double c = p.cross(q);
            a += c;
            cx += (p.x() + q.x()) * c;
            cy += (p.y() + q.y()) * c;
        }
        if (Math.abs(a) < 1e-12) {
            return poly[0];
        }
        return new V2(cx / (3 * a), cy / (3 * a));
    }
}

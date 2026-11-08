package com.spawnadmin.sky.geom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;

/**
 * Deterministic fracture of the glass sky dome, generated from a single seed.
 *
 * <p>Real broken glass is not a honeycomb: a struck pane splits along long, almost straight
 * radial cracks running out from the impact, joined by irregular cross cracks, and breaks into
 * sharp, elongated splinters of very different sizes. The generator mimics that:</p>
 * <ol>
 *   <li>Coarse Voronoi cells (denser around the impact) give the irregular cross cracks.</li>
 *   <li>Straight radial crack lines through (almost) the impact point split every cell they cross.</li>
 *   <li>Large pieces are split again by random cuts, mostly along the radial direction, which
 *       produces long sharp slivers and triangles.</li>
 *   <li>Vertices are welded and T-junctions resolved, so the pieces form one watertight mesh; its
 *       shared edges are the crack network. Dijkstra from the impact (noisy weights) times the
 *       crack front, hairline branches grow inside pieces.</li>
 *   <li>Every piece becomes a glass shard with its own thickness, motion and restore window; the
 *       pieces that break free also shed tiny debris splinters.</li>
 * </ol>
 */
public final class Fracture {
    public static final int TARGET_CELLS = 95;
    public static final int MAX_SHARDS = 520;
    private static final int RIM_SEGMENTS = 96;

    public final long seed;
    public final V2 impact;
    /** Radius (disc units) inside which shards break free and open the rift. */
    public final double openRadius;
    public final List<ShardData> shards;
    public final List<DebrisData> debris;
    public final List<CrackSegment> cracks;
    /** Largest crack-front distance: front = progress * maxFront. */
    public final double maxFront;
    /** Shard adjacency: neighbours[i] lists shards sharing an edge with shard i. */
    public final int[][] neighbours;

    private Fracture(long seed, V2 impact, double openRadius, List<ShardData> shards, List<DebrisData> debris,
                     List<CrackSegment> cracks, double maxFront, int[][] neighbours) {
        this.seed = seed;
        this.impact = impact;
        this.openRadius = openRadius;
        this.shards = shards;
        this.debris = debris;
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

        // 1. coarse cells, welded into one mesh (Voronoi cells share their edges exactly)
        TopoMesh mesh = TopoMesh.fromCells(voronoi(scatterSites(rnd, impact)));

        // 2. radial crack lines through the impact zone, cutting every piece they cross
        int radial = 9 + rnd.nextInt(5);
        double base = rnd.nextDouble() * Math.PI;
        for (int k = 0; k < radial; k++) {
            double a = base + Math.PI * k / radial + (rnd.nextDouble() - 0.5) * 0.22;
            V2 dir = new V2(StrictMath.cos(a), StrictMath.sin(a));
            V2 normal = new V2(-dir.y(), dir.x());
            V2 anchor = impact.add(normal.mul((rnd.nextDouble() - 0.5) * 0.03));
            int count = mesh.polys.size();
            for (int pi = 0; pi < count; pi++) {
                mesh.split(pi, anchor, normal, 0.0);
            }
        }

        // 3. splinter cuts: big pieces split again, mostly along the radial direction
        Deque<int[]> queue = new ArrayDeque<>(); // {poly, depth}
        for (int pi = 0; pi < mesh.polys.size(); pi++) {
            queue.add(new int[]{pi, 0});
        }
        while (!queue.isEmpty() && mesh.polys.size() < MAX_SHARDS) {
            int[] item = queue.poll();
            int pi = item[0];
            List<V2> piece = mesh.outline(pi);
            V2 c = centroid(piece);
            double maxArea = 0.0016 + 0.0075 * Math.min(1.0, c.dist(impact) / 0.8);
            if (item[1] >= 3 || area(piece) < maxArea) {
                continue;
            }
            V2 radialDir = c.sub(impact).normalize();
            double radialAngle = StrictMath.atan2(radialDir.y(), radialDir.x());
            double a = rnd.nextDouble() < 0.72
                    ? radialAngle + gaussian(rnd) * 0.35
                    : radialAngle + Math.PI / 2 + gaussian(rnd) * 0.5;
            V2 dir = new V2(StrictMath.cos(a), StrictMath.sin(a));
            V2 normal = new V2(-dir.y(), dir.x());
            if (mesh.split(pi, randomInside(rnd, piece), normal, 0.08)) {
                queue.add(new int[]{pi, item[1] + 1});
                queue.add(new int[]{mesh.polys.size() - 1, item[1] + 1});
            }
        }

        // 4. the shared edges of the mesh are the crack network
        Map<Long, int[]> edgeOwners = mesh.owners;
        List<int[]> edges = new ArrayList<>(); // {vA, vB, pieceA, pieceB}
        List<List<Integer>> nb = new ArrayList<>();
        for (int i = 0; i < mesh.polys.size(); i++) {
            nb.add(new ArrayList<>());
        }
        for (Map.Entry<Long, int[]> e : sortedEntries(edgeOwners)) {
            int[] owners = e.getValue();
            if (owners[1] < 0) {
                continue; // rim
            }
            long key = e.getKey();
            edges.add(new int[]{(int) (key >>> 32), (int) key, owners[0], owners[1]});
            nb.get(owners[0]).add(owners[1]);
            nb.get(owners[1]).add(owners[0]);
        }

        List<V2> nodes = mesh.verts;
        double[] weight = new double[edges.size()];
        List<List<int[]>> adj = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            adj.add(new ArrayList<>());
        }
        for (int e = 0; e < edges.size(); e++) {
            int[] ed = edges.get(e);
            double len = nodes.get(ed[0]).dist(nodes.get(ed[1]));
            // Radial cracks run fast, cross cracks lag behind: the front races outwards first.
            V2 mid = nodes.get(ed[0]).lerp(nodes.get(ed[1]), 0.5);
            V2 along = nodes.get(ed[1]).sub(nodes.get(ed[0])).normalize();
            double radialness = Math.abs(along.dot(mid.sub(impact).normalize()));
            weight[e] = len * (0.55 + 0.8 * (1 - radialness)) * (0.8 + 0.4 * rnd.nextDouble());
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
            double width = 0.18 + 0.5 * Math.exp(-mid.dist(impact) / 0.2);
            cracks.add(new CrackSegment(a, b, da, db, weight[e], ed[2], ed[3], false, width));
        }

        // hairlines
        for (int p = 0; p < mesh.polys.size(); p++) {
            if (rnd.nextDouble() < 0.4) {
                growHairline(rnd, p, mesh, dist, maxFront, cracks);
            }
        }

        // 5. shards and debris
        List<ShardData> shards = new ArrayList<>();
        List<DebrisData> debris = new ArrayList<>();
        int[][] neighbours = new int[mesh.polys.size()][];
        for (int p = 0; p < mesh.polys.size(); p++) {
            int[] poly = mesh.polys.get(p);
            V2[] outline = new V2[poly.length];
            for (int k = 0; k < poly.length; k++) {
                outline[k] = nodes.get(poly[k]);
            }
            ShardData shard = buildShard(rnd, p, outline, impact, openRadius);
            shards.add(shard);
            neighbours[p] = nb.get(p).stream().distinct().mapToInt(Integer::intValue).toArray();
            if (shard.detaches) {
                addDebris(rnd, shard, debris);
            }
        }
        return new Fracture(seed, impact, openRadius, List.copyOf(shards), List.copyOf(debris), List.copyOf(cracks),
                maxFront, neighbours);
    }

    private static double gaussian(SplittableRandom rnd) {
        double u = Math.max(1e-12, rnd.nextDouble()), v = rnd.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * StrictMath.cos(2 * Math.PI * v);
    }

    private static List<Map.Entry<Long, int[]>> sortedEntries(Map<Long, int[]> map) {
        List<Map.Entry<Long, int[]>> list = new ArrayList<>(map.entrySet());
        list.sort(Map.Entry.comparingByKey());
        return list;
    }

    // ------------------------------------------------------------------ sites + voronoi

    private static List<V2> scatterSites(SplittableRandom rnd, V2 impact) {
        List<V2> sites = new ArrayList<>();
        sites.add(impact.add(new V2(0.012, 0.004)));
        int spokes = 6 + rnd.nextInt(3);
        double ringR = 0.05;
        double twist = rnd.nextDouble() * Math.PI * 2;
        for (int ring = 0; ring < 4; ring++) {
            int count = spokes + ring * 2;
            for (int k = 0; k < count; k++) {
                double a = twist + Math.PI * 2 * (k + 0.5 * (ring % 2)) / count + (rnd.nextDouble() - 0.5) * 0.5;
                double r = ringR * (0.75 + rnd.nextDouble() * 0.5);
                V2 p = impact.add(new V2(r * StrictMath.cos(a), r * StrictMath.sin(a)));
                if (p.length() < 0.985) {
                    sites.add(p);
                }
            }
            ringR *= 1.55;
            twist += 0.4;
        }
        int attempts = 0;
        while (sites.size() < TARGET_CELLS && attempts < 60000) {
            attempts++;
            double r = Math.sqrt(rnd.nextDouble()) * 0.985;
            double a = rnd.nextDouble() * Math.PI * 2;
            V2 p = new V2(r * StrictMath.cos(a), r * StrictMath.sin(a));
            double spacing = 0.05 + 0.13 * Math.min(1.0, p.dist(impact) / 0.9);
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

    private static List<List<V2>> voronoi(List<V2> sites) {
        List<List<V2>> cells = new ArrayList<>();
        for (int i = 0; i < sites.size(); i++) {
            List<V2> poly = new ArrayList<>();
            for (int k = 0; k < RIM_SEGMENTS; k++) {
                double a = Math.PI * 2 * k / RIM_SEGMENTS;
                poly.add(new V2(StrictMath.cos(a), StrictMath.sin(a)));
            }
            V2 si = sites.get(i);
            Integer[] order = new Integer[sites.size()];
            for (int j = 0; j < order.length; j++) {
                order[j] = j;
            }
            Arrays.sort(order, (p, q) -> Double.compare(si.dist(sites.get(p)), si.dist(sites.get(q))));
            for (int j : order) {
                if (j == i) {
                    continue;
                }
                double reach = 0;
                for (V2 v : poly) {
                    reach = Math.max(reach, v.dist(si));
                }
                if (si.dist(sites.get(j)) > 2 * reach) {
                    break;
                }
                V2 sj = sites.get(j);
                poly = clipHalf(poly, si.lerp(sj, 0.5), sj.sub(si));
                if (poly.size() < 3) {
                    break;
                }
            }
            if (poly.size() >= 3) {
                cells.add(poly);
            }
        }
        return cells;
    }

    /** Keeps the part of a convex polygon where (p - m)·n <= 0. */
    private static List<V2> clipHalf(List<V2> poly, V2 m, V2 n) {
        List<V2> out = new ArrayList<>();
        int size = poly.size();
        for (int k = 0; k < size; k++) {
            V2 a = poly.get(k);
            V2 b = poly.get((k + 1) % size);
            double fa = a.sub(m).dot(n);
            double fb = b.sub(m).dot(n);
            if (fa <= 0) {
                out.add(a);
                if (fb > 0) {
                    out.add(a.lerp(b, fa / (fa - fb)));
                }
            } else if (fb <= 0) {
                out.add(a.lerp(b, fa / (fa - fb)));
            }
        }
        return out;
    }

    private static V2 randomInside(SplittableRandom rnd, List<V2> poly) {
        V2 c = centroid(poly);
        V2 v = poly.get(rnd.nextInt(poly.size()));
        return c.lerp(v, rnd.nextDouble() * 0.6);
    }

    static double area(List<V2> poly) {
        double a = 0;
        for (int i = 0; i < poly.size(); i++) {
            a += poly.get(i).cross(poly.get((i + 1) % poly.size()));
        }
        return a * 0.5;
    }

    static V2 centroid(List<V2> poly) {
        return polygonCentroid(poly.toArray(new V2[0]));
    }

    // ------------------------------------------------------------------ topological mesh

    /**
     * Convex polygons sharing vertex ids. Every cut inserts its new vertices into the neighbour
     * across the cut edge as well, so the mesh stays watertight by construction (no T-junctions).
     */
    private static final class TopoMesh {
        final List<V2> verts = new ArrayList<>();
        final List<int[]> polys = new ArrayList<>();
        /** Undirected edge -> {poly, poly}; -1 where there is no second owner (the rim). */
        final Map<Long, int[]> owners = new HashMap<>();

        static TopoMesh fromCells(List<List<V2>> cells) {
            TopoMesh m = new TopoMesh();
            Map<Long, List<Integer>> grid = new HashMap<>();
            for (List<V2> cell : cells) {
                List<Integer> ids = new ArrayList<>();
                for (V2 v : cell) {
                    int id = m.findOrAdd(grid, v);
                    if (ids.isEmpty() || ids.get(ids.size() - 1) != id) {
                        ids.add(id);
                    }
                }
                if (ids.size() > 1 && ids.get(0).equals(ids.get(ids.size() - 1))) {
                    ids.remove(ids.size() - 1);
                }
                if (ids.size() >= 3) {
                    m.polys.add(ids.stream().mapToInt(Integer::intValue).toArray());
                    m.registerAll(m.polys.size() - 1);
                }
            }
            return m;
        }

        private int findOrAdd(Map<Long, List<Integer>> grid, V2 v) {
            double cell = 1e-6;
            long cx = (long) Math.floor(v.x() / cell), cy = (long) Math.floor(v.y() / cell);
            for (long dx = -1; dx <= 1; dx++) {
                for (long dy = -1; dy <= 1; dy++) {
                    List<Integer> bucket = grid.get(gridKey(cx + dx, cy + dy));
                    if (bucket == null) continue;
                    for (int id : bucket) {
                        if (verts.get(id).dist(v) < 1e-6) {
                            return id;
                        }
                    }
                }
            }
            int id = verts.size();
            verts.add(v);
            grid.computeIfAbsent(gridKey(cx, cy), k -> new ArrayList<>()).add(id);
            return id;
        }

        List<V2> outline(int pi) {
            List<V2> out = new ArrayList<>();
            for (int id : polys.get(pi)) {
                out.add(verts.get(id));
            }
            return out;
        }

        private void register(long key, int p) {
            int[] o = owners.computeIfAbsent(key, k -> new int[]{-1, -1});
            if (o[0] == -1) {
                o[0] = p;
            } else if (o[1] == -1) {
                o[1] = p;
            }
        }

        private void unregister(long key, int p) {
            int[] o = owners.get(key);
            if (o == null) return;
            if (o[0] == p) {
                o[0] = o[1];
                o[1] = -1;
            } else if (o[1] == p) {
                o[1] = -1;
            }
            if (o[0] == -1) owners.remove(key);
        }

        private void registerAll(int pi) {
            int[] poly = polys.get(pi);
            for (int k = 0; k < poly.length; k++) register(edgeKey(poly[k], poly[(k + 1) % poly.length]), pi);
        }

        private void unregisterAll(int pi) {
            int[] poly = polys.get(pi);
            for (int k = 0; k < poly.length; k++) unregister(edgeKey(poly[k], poly[(k + 1) % poly.length]), pi);
        }

        /**
         * Splits polygon {@code pi} by the line through {@code p} with normal {@code n}.
         * Refuses (returns false) if either half would be smaller than {@code minFraction} of it.
         * Half A keeps index {@code pi}, half B is appended.
         */
        boolean split(int pi, V2 p, V2 n, double minFraction) {
            int[] poly = polys.get(pi);
            int m = poly.length;
            double eps = 1e-12;
            int[] side = new int[m];
            double[] f = new double[m];
            boolean neg = false, pos = false;
            for (int i = 0; i < m; i++) {
                f[i] = verts.get(poly[i]).sub(p).dot(n);
                side[i] = f[i] < -eps ? -1 : (f[i] > eps ? 1 : 0);
                neg |= side[i] < 0;
                pos |= side[i] > 0;
            }
            if (!neg || !pos) {
                return false;
            }
            List<Integer> a = new ArrayList<>(), b = new ArrayList<>();
            List<V2> pa = new ArrayList<>(), pb = new ArrayList<>();
            List<int[]> crossings = new ArrayList<>(); // {va, vb}
            List<V2> crossPos = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                V2 vi = verts.get(poly[i]);
                if (side[i] <= 0) { a.add(poly[i]); pa.add(vi); }
                if (side[i] >= 0) { b.add(poly[i]); pb.add(vi); }
                if (side[i] * side[j] < 0) {
                    V2 x = vi.lerp(verts.get(poly[j]), f[i] / (f[i] - f[j]));
                    int placeholder = -(crossings.size() + 1);
                    crossings.add(new int[]{poly[i], poly[j]});
                    crossPos.add(x);
                    a.add(placeholder); pa.add(x);
                    b.add(placeholder); pb.add(x);
                }
            }
            double whole = area(outline(pi));
            double areaA = area(pa), areaB = area(pb);
            if (a.size() < 3 || b.size() < 3 || areaA < 1e-9 || areaB < 1e-9
                    || Math.min(areaA, areaB) < whole * minFraction) {
                return false;
            }
            int[] ids = new int[crossings.size()];
            for (int c = 0; c < crossings.size(); c++) {
                ids[c] = verts.size();
                verts.add(crossPos.get(c));
                int va = crossings.get(c)[0], vb = crossings.get(c)[1];
                long key = edgeKey(va, vb);
                int[] o = owners.get(key);
                int q = o == null ? -1 : (o[0] == pi ? o[1] : o[0]);
                if (q >= 0) {
                    insertBetween(q, va, vb, ids[c]);
                    unregister(key, q);
                    register(edgeKey(va, ids[c]), q);
                    register(edgeKey(ids[c], vb), q);
                }
            }
            unregisterAll(pi);
            polys.set(pi, resolve(a, ids));
            polys.add(resolve(b, ids));
            registerAll(pi);
            registerAll(polys.size() - 1);
            return true;
        }

        private static int[] resolve(List<Integer> list, int[] ids) {
            int[] out = new int[list.size()];
            for (int i = 0; i < out.length; i++) {
                int v = list.get(i);
                out[i] = v < 0 ? ids[-v - 1] : v;
            }
            return out;
        }

        private void insertBetween(int q, int va, int vb, int v) {
            int[] poly = polys.get(q);
            for (int k = 0; k < poly.length; k++) {
                int x = poly[k], y = poly[(k + 1) % poly.length];
                if ((x == va && y == vb) || (x == vb && y == va)) {
                    int[] out = new int[poly.length + 1];
                    System.arraycopy(poly, 0, out, 0, k + 1);
                    out[k + 1] = v;
                    System.arraycopy(poly, k + 1, out, k + 2, poly.length - k - 1);
                    polys.set(q, out);
                    return;
                }
            }
        }
    }

    private static long gridKey(long x, long y) {
        return (x + 4_000_000L) * 16_000_000L + (y + 4_000_000L);
    }

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    // ------------------------------------------------------------------ crack front

    private static double[] dijkstra(List<V2> nodes, List<List<int[]>> adj, double[] weight, V2 impact) {
        double[] dist = new double[nodes.size()];
        Arrays.fill(dist, Double.MAX_VALUE);
        PriorityQueue<double[]> queue = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        for (int i = 0; i < nodes.size(); i++) {
            double d = nodes.get(i).dist(impact);
            if (d < 0.05 && !adj.get(i).isEmpty()) {
                dist[i] = d;
                queue.add(new double[]{d, i});
            }
        }
        if (queue.isEmpty()) {
            int best = -1;
            for (int i = 0; i < nodes.size(); i++) {
                if (!adj.get(i).isEmpty() && (best < 0 || nodes.get(i).dist(impact) < nodes.get(best).dist(impact))) {
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

    private static void growHairline(SplittableRandom rnd, int piece, TopoMesh mesh, double[] dist, double maxFront,
                                     List<CrackSegment> out) {
        int[] poly = mesh.polys.get(piece);
        List<V2> outline = new ArrayList<>();
        for (int id : poly) {
            outline.add(mesh.verts.get(id));
        }
        int k = rnd.nextInt(poly.length);
        V2 a = outline.get(k);
        V2 b = outline.get((k + 1) % poly.length);
        if (a.length() > 0.97 && b.length() > 0.97) {
            return; // never on the rim
        }
        V2 start = a.lerp(b, 0.2 + 0.6 * rnd.nextDouble());
        double da = dist[poly[k]] == Double.MAX_VALUE ? maxFront : dist[poly[k]];
        double db = dist[poly[(k + 1) % poly.length]] == Double.MAX_VALUE ? maxFront : dist[poly[(k + 1) % poly.length]];
        double front = Math.min(da + a.dist(start), db + b.dist(start));
        V2 edgeDir = b.sub(a).normalize();
        V2 inward = new V2(-edgeDir.y(), edgeDir.x());
        if (centroid(outline).sub(start).dot(inward) < 0) {
            inward = inward.mul(-1);
        }
        walk(rnd, piece, outline, start, inward.rotate((rnd.nextDouble() - 0.5) * 1.2), front, 0, out);
    }

    private static void walk(SplittableRandom rnd, int piece, List<V2> outline, V2 from, V2 dir, double front, int depth,
                             List<CrackSegment> out) {
        int steps = 2 + rnd.nextInt(4) - depth;
        V2 p = from;
        double f = front;
        for (int s = 0; s < steps; s++) {
            double len = 0.006 + rnd.nextDouble() * 0.012;
            dir = dir.rotate((rnd.nextDouble() - 0.5) * 0.7);
            V2 q = p.add(dir.mul(len));
            if (!insideConvex(outline, q, 0.003)) {
                return;
            }
            double w = len * 1.4;
            out.add(new CrackSegment(p, q, f, f + w, w, piece, piece, true, 0.08 + 0.06 * rnd.nextDouble()));
            f += w;
            p = q;
            if (depth < 1 && rnd.nextDouble() < 0.3) {
                double side = rnd.nextBoolean() ? 1 : -1;
                walk(rnd, piece, outline, p, dir.rotate(side * (0.4 + rnd.nextDouble() * 0.5)), f, depth + 1, out);
            }
        }
    }

    static boolean insideConvex(List<V2> poly, V2 p, double margin) {
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            V2 a = poly.get(i);
            V2 b = poly.get((i + 1) % n);
            V2 e = b.sub(a);
            double len = e.length();
            if (len < 1e-12) continue;
            if (e.cross(p.sub(a)) / len < margin) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ shards

    private static ShardData buildShard(SplittableRandom rnd, int index, V2[] outline, V2 impact, double openRadius) {
        V2 centroid = polygonCentroid(outline);
        V3 origin = DomeShape.pointAt(centroid, 0);
        V3 normal = DomeShape.normalAt(centroid.x(), centroid.y());
        double thickness = DomeShape.THICKNESS * (0.65 + rnd.nextDouble() * 0.7);
        double h = thickness * 0.5;
        V3[] outer = new V3[outline.length];
        V3[] inner = new V3[outline.length];
        for (int i = 0; i < outline.length; i++) {
            outer[i] = DomeShape.pointAt(outline[i], h).sub(origin);
            inner[i] = DomeShape.pointAt(outline[i], -h).sub(origin);
        }

        double d = centroid.dist(impact);
        double jitter = (rnd.nextDouble() - 0.5) * 0.14;
        boolean detaches = d + jitter < openRadius;
        double outerness = Math.min(1.0, d / openRadius);
        boolean hovers = detaches && rnd.nextDouble() < 0.18 + 0.3 * outerness;
        // Breaking spreads outward over ~4 s of the 6 s fall.
        double detachDelay = 0.1 + outerness * 3.2 + rnd.nextDouble() * 0.5;

        V3 inward = normal.mul(-1);
        V3 tangent = normal.anyPerpendicular();
        V3 bitangent = normal.cross(tangent);
        double ta = rnd.nextDouble() * Math.PI * 2;
        V3 lateral = tangent.mul(Math.cos(ta)).add(bitangent.mul(Math.sin(ta)));
        V3 velocity = inward.mul(1.5 + rnd.nextDouble() * 3.5).add(lateral.mul(rnd.nextDouble() * 2.5));
        V3 spinAxis = lateral.add(normal.mul(rnd.nextDouble() - 0.5))
                .add(new V3(rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5).mul(0.8)).normalize();
        double spinSpeed = (0.4 + rnd.nextDouble() * 1.8) * (rnd.nextBoolean() ? 1 : -1);
        V3 hoverOffset = inward.mul(5 + rnd.nextDouble() * 18).add(lateral.mul(rnd.nextDouble() * 7));
        double hoverTilt = (0.35 + rnd.nextDouble() * 1.2) * (rnd.nextBoolean() ? 1 : -1);

        double restoreStart = 0.04 + 0.42 * (1.0 - outerness) + rnd.nextDouble() * 0.06;
        double restoreLength = 0.36 + rnd.nextDouble() * 0.08;
        return new ShardData(index, outline, centroid, origin, normal, outer, inner, d, detaches, hovers,
                detachDelay, velocity, spinAxis, spinSpeed, hoverOffset, hoverTilt, restoreStart, restoreLength,
                rnd.nextDouble(), thickness);
    }

    /** Small splinters chipped off the rim of a breaking shard. */
    private static void addDebris(SplittableRandom rnd, ShardData s, List<DebrisData> out) {
        int count = 3 + rnd.nextInt(5);
        for (int i = 0; i < count; i++) {
            int k = rnd.nextInt(s.outline.length);
            V2 disc = s.outline[k].lerp(s.outline[(k + 1) % s.outline.length], rnd.nextDouble())
                    .lerp(s.centroid2, 0.05 + rnd.nextDouble() * 0.15);
            V3 origin = DomeShape.pointAt(disc, 0);
            V3 normal = DomeShape.normalAt(disc.x(), disc.y());
            V3 t = normal.anyPerpendicular();
            V3 b = normal.cross(t);
            double size = 0.25 + rnd.nextDouble() * 0.9;
            V3[] tri = new V3[3];
            double a0 = rnd.nextDouble() * Math.PI * 2;
            for (int c = 0; c < 3; c++) {
                double a = a0 + c * 2.1 + (rnd.nextDouble() - 0.5) * 0.9;
                double r = size * (0.5 + rnd.nextDouble() * 0.7) * (c == 0 ? 1.8 : 1.0); // one long sharp tip
                tri[c] = t.mul(Math.cos(a) * r).add(b.mul(Math.sin(a) * r));
            }
            V3 spread = new V3(rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5).mul(5.0);
            V3 velocity = s.velocity.mul(0.7 + rnd.nextDouble() * 0.6).add(spread).add(normal.mul(-2.0 * rnd.nextDouble()));
            V3 axis = new V3(rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5).normalize();
            double spin = (3 + rnd.nextDouble() * 7) * (rnd.nextBoolean() ? 1 : -1);
            double delay = Math.max(0, s.detachDelay - 0.05 + rnd.nextDouble() * 0.15);
            double rs = Math.max(0, s.restoreStart - 0.05 * rnd.nextDouble());
            out.add(new DebrisData(s.index, origin, disc, tri, normal, delay, velocity, axis, spin, rs,
                    s.restoreLength * (0.8 + 0.2 * rnd.nextDouble())));
        }
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

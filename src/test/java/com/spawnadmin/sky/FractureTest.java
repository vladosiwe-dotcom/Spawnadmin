package com.spawnadmin.sky;

import com.spawnadmin.sky.geom.CrackSegment;
import com.spawnadmin.sky.geom.Fracture;
import com.spawnadmin.sky.geom.Quat;
import com.spawnadmin.sky.geom.ShardData;
import com.spawnadmin.sky.geom.V2;
import com.spawnadmin.sky.geom.V3;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FractureTest {
    private static final long[] SEEDS = {1L, 42L, -7L, 20261108L, Long.MAX_VALUE, 123456789L};

    @Test
    void generationIsDeterministic() {
        for (long seed : SEEDS) {
            Fracture a = Fracture.generate(seed);
            Fracture b = Fracture.generate(seed);
            assertEquals(a.shards.size(), b.shards.size());
            assertEquals(a.cracks.size(), b.cracks.size());
            for (int i = 0; i < a.shards.size(); i++) {
                assertArrayEquals(a.shards.get(i).outline, b.shards.get(i).outline);
                assertEquals(a.shards.get(i).velocity, b.shards.get(i).velocity);
            }
        }
    }

    @Test
    void shardsTileTheWholeDome() {
        double rim = 0.5 * 96 * Math.sin(2 * Math.PI / 96);
        for (long seed : SEEDS) {
            Fracture f = Fracture.generate(seed);
            double area = f.shards.stream().mapToDouble(ShardData::area2).sum();
            assertEquals(rim, area, 2e-4, "cells must cover the disc exactly, seed " + seed);
            assertTrue(f.shards.size() >= 200 && f.shards.size() <= Fracture.MAX_SHARDS + 20,
                    "shard count " + f.shards.size());
            for (ShardData s : f.shards) {
                assertTrue(s.area2() > 0, "every shard is non-degenerate and CCW");
            }
        }
    }

    @Test
    void shardSizesVary() {
        Fracture f = Fracture.generate(99L);
        double[] areas = f.shards.stream().mapToDouble(ShardData::area2).sorted().toArray();
        assertTrue(areas[areas.length - 1] > 6 * areas[0], "largest shard must be much larger than the smallest");
        long shapes = f.shards.stream().mapToInt(s -> s.outline.length).distinct().count();
        assertTrue(shapes >= 3, "outlines must differ in vertex count");
    }

    @Test
    void shardsAreSharpSplintersNotHoneycomb() {
        Fracture f = Fracture.generate(20261108L);
        int slivers = 0, triangles = 0;
        for (ShardData s : f.shards) {
            double maxD = 0;
            for (V2 a : s.outline) for (V2 b : s.outline) maxD = Math.max(maxD, a.dist(b));
            double aspect = maxD * maxD / Math.max(s.area2(), 1e-12);
            if (aspect > 5.0) slivers++;
            if (s.coarseCorners == 3) triangles++;
        }
        assertTrue(slivers > f.shards.size() / 4, "many long splinters: " + slivers + "/" + f.shards.size());
        assertTrue(triangles > f.shards.size() / 10, "many sharp triangles: " + triangles + "/" + f.shards.size());
    }

    @Test
    void crackNetworkIsWatertight() {
        for (long seed : SEEDS) {
            Fracture f = Fracture.generate(seed);
            // Every non-rim edge of every shard must be a crack shared with exactly one neighbour.
            java.util.Map<String, Integer> uses = new java.util.HashMap<>();
            for (ShardData s : f.shards) {
                for (int k = 0; k < s.outline.length; k++) {
                    V2 a = s.outline[k], b = s.outline[(k + 1) % s.outline.length];
                    boolean rim = a.length() > 0.999 && b.length() > 0.999;
                    if (!rim) uses.merge(key(a, b), 1, Integer::sum);
                }
            }
            long open = uses.values().stream().filter(n -> n != 2).count();
            assertEquals(0, open, "no T-junction gaps, seed " + seed);
        }
    }

    @Test
    void debrisFallsFreezesAndReturns() {
        SkyScene scene = new SkyScene(Fracture.generate(9L));
        assertTrue(scene.fracture.debris.size() > 100, "plenty of splinters: " + scene.fracture.debris.size());
        SkyScene.ShardPose a = new SkyScene.ShardPose(), b = new SkyScene.ShardPose();
        double r = SkyTimeline.RESTORE;
        for (var d : scene.fracture.debris) {
            scene.debrisPose(d, SkyTimeline.FREEZE + 3, r, a);
            scene.debrisPose(d, SkyTimeline.TELEPORT, r, b);
            assertEquals(a.offset, b.offset, "debris frozen in time");
            scene.debrisPose(d, r + SkyTimeline.RESTORE_LENGTH, r, b);
            assertEquals(0.0, b.alpha, 1e-9, "debris merged back");
        }
    }

    private static int corners(V2[] poly) {
        int n = 0;
        for (int i = 0; i < poly.length; i++) {
            V2 p = poly[(i + poly.length - 1) % poly.length], c = poly[i], q = poly[(i + 1) % poly.length];
            if (Math.abs(c.sub(p).normalize().cross(q.sub(c).normalize())) > 0.02) n++;
        }
        return n;
    }

    private static String key(V2 a, V2 b) {
        String ka = Math.round(a.x() * 1e6) + ":" + Math.round(a.y() * 1e6);
        String kb = Math.round(b.x() * 1e6) + ":" + Math.round(b.y() * 1e6);
        return ka.compareTo(kb) < 0 ? ka + "|" + kb : kb + "|" + ka;
    }

    @Test
    void cracksAppearGraduallyFromOneBarelyVisibleCrack() {
        SkyScene scene = new SkyScene(Fracture.generate(20261108L));
        double r = SkyTimeline.RESTORE;
        int[] visible = new int[4];
        int[] ticks = {SkyTimeline.FIRST_CRACK - 1, SkyTimeline.FIRST_CRACK + 3, SkyTimeline.CONNECT, SkyTimeline.CHIPS};
        for (int k = 0; k < ticks.length; k++) {
            double front = scene.crackFront(ticks[k], r);
            for (CrackSegment c : scene.fracture.cracks) {
                double[] range = c.visibleRange(front);
                double len = 0;
                for (int i = 0; i + 1 < range.length; i += 2) len += range[i + 1] - range[i];
                if (len > 1e-6) visible[k]++;
            }
        }
        int total = scene.fracture.cracks.size();
        assertEquals(0, visible[0], "nothing cracks while the glass is only pressed in");
        assertTrue(visible[1] >= 1 && visible[1] < total * 0.05, "it snaps: a small star of cracks, " + visible[1]);
        assertTrue(visible[2] > visible[1] && visible[2] < total * 0.9, "then they join up, " + visible[2]);
        assertTrue(visible[3] > total * 0.95, "finally the whole dome is cracked, " + visible[3]);
        assertTrue(scene.fracture.origins.size() >= 3, "new cracks start in other places too");
        // A fresh crack is a hair-thin line; old cracks are wide.
        CrackSegment first = scene.fracture.cracks.stream().min((a, b) -> Double.compare(Math.min(a.da(), a.db()), Math.min(b.da(), b.db()))).orElseThrow();
        double early = scene.crackWidth(first, scene.crackFront(SkyTimeline.FIRST_CRACK + 4, r), SkyTimeline.FIRST_CRACK + 4);
        double late = scene.crackWidth(first, scene.crackFront(SkyTimeline.FALL, r), SkyTimeline.FALL);
        assertTrue(early < 0.4 * late, "cracks widen and deepen: " + early + " -> " + late);
    }

    @Test
    void theSkyIsPressedInThenSnapsAndSplitsInTwo() {
        SkyScene scene = new SkyScene(Fracture.generate(20261108L));
        double r = SkyTimeline.RESTORE;
        V2 c = scene.fracture.impact;
        assertEquals(0.0, scene.pressure(SkyTimeline.PRESS, r), 1e-9, "nothing at first");
        double atSnap = scene.dent(c.x(), c.y(), scene.pressure(SkyTimeline.FIRST_CRACK, r));
        assertTrue(atSnap > 4.0, "the glass is pressed in several blocks deep before it snaps: " + atSnap);
        assertTrue(scene.dent(c.x() + 0.6, c.y(), scene.pressure(SkyTimeline.FIRST_CRACK, r)) < 0.1, "only around the impact");
        assertEquals(0.0, scene.pressure(r + SkyTimeline.RESTORE_LENGTH, r), 1e-9, "it springs back");
        long main = scene.fracture.cracks.stream().filter(CrackSegment::main).count();
        assertTrue(main > 10, "a great crack splits the sky: " + main);
        double front = scene.crackFront(SkyTimeline.FIRST_CRACK + 30, r);
        long mainVisible = scene.fracture.cracks.stream().filter(CrackSegment::main)
                .filter(seg -> seg.visibleRange(front)[1] > 0.99).count();
        assertTrue(mainVisible > main * 0.8, "the split runs across the whole sky within 1.5 s: " + mainVisible + "/" + main);
        assertTrue(scene.splitGap(SkyTimeline.FALL, r) > 0.99, "the halves part before the collapse");
    }

    @Test
    void shardsFallAllTheWayDownAndLieFlatOnTheGround() {
        // ground 30 blocks below the event centre in one half, 0 in the other
        SkyScene scene = new SkyScene(Fracture.generate(77L), (x, z) -> x > 0 ? -30.0 : 0.0);
        double r = SkyTimeline.RESTORE;
        SkyScene.ShardPose p = new SkyScene.ShardPose();
        int landed = 0, falling = 0;
        for (ShardData s : scene.fracture.shards) {
            if (!s.detaches || s.hovers) continue;
            falling++;
            scene.shardPose(s, SkyTimeline.FREEZE, r, p);
            double x = s.origin.x() + p.offset.x();
            double y = s.origin.y() + p.offset.y();
            double ground = x > 0 ? -30.0 : 0.0;
            if (Math.abs(y - ground) < s.thickness + 0.6) {
                landed++;
                double up = Math.abs(p.rotation.rotate(s.normal).y());
                assertTrue(up > 0.99, "a landed shard lies flat, |n.y| = " + up);
            }
            assertTrue(y > ground - 0.5, "never below the ground");
        }
        assertTrue(landed > falling * 0.85, "the glass reaches the ground before time stops: " + landed + "/" + falling);
    }

    @Test
    void chipsAndFirstFragmentsBreakAwayBeforeTheCollapse() {
        SkyScene scene = new SkyScene(Fracture.generate(20261108L));
        double r = SkyTimeline.RESTORE;
        SkyScene.ShardPose p = new SkyScene.ShardPose();
        long chipsFlying = scene.fracture.debris.stream().filter(d -> d.isChip()).filter(d -> {
            scene.debrisPose(d, SkyTimeline.FRAGMENTS + 10, r, p);
            return p.alpha > 0.1;
        }).count();
        long earlyShards = scene.fracture.shards.stream().filter(s -> {
            scene.shardPose(s, SkyTimeline.FALL - 1, r, p);
            return p.free > 0.5;
        }).count();
        long beforeChips = scene.fracture.debris.stream().filter(d -> d.isChip()).filter(d -> {
            scene.debrisPose(d, SkyTimeline.CHIPS - 20, r, p);
            return p.alpha > 0.01;
        }).count();
        assertEquals(0, beforeChips, "nothing breaks off while the cracks are still growing");
        assertTrue(chipsFlying > 10, "chips pop out of the cracks: " + chipsFlying);
        assertTrue(earlyShards >= 1 && earlyShards < 25, "a few single fragments go first: " + earlyShards);
    }

    @Test
    void everyShardHasItsOwnJaggedOutline() {
        Fracture f = Fracture.generate(20261108L);
        java.util.Set<String> shapes = new java.util.HashSet<>();
        int jagged = 0;
        for (ShardData s : f.shards) {
            if (s.outline.length > s.coarseCorners + 2) jagged++;
            // shape signature: vertex count + rounded perimeter/area ratio
            double per = 0;
            for (int i = 0; i < s.outline.length; i++) per += s.outline[i].dist(s.outline[(i + 1) % s.outline.length]);
            shapes.add(String.format(java.util.Locale.ROOT, "%d:%.7f:%.6f", s.outline.length, s.area2(), per));
            // star-shaped from the centroid (the renderer fans from it): every triangle positive
            for (int i = 0; i < s.outline.length; i++) {
                V2 a = s.outline[i].sub(s.centroid2), b = s.outline[(i + 1) % s.outline.length].sub(s.centroid2);
                assertTrue(a.cross(b) > -1e-12, "fan from the centroid stays valid for shard " + s.index);
            }
        }
        assertTrue(jagged > f.shards.size() * 0.8, "edges are jagged, not straight: " + jagged + "/" + f.shards.size());
        assertTrue(shapes.size() > f.shards.size() * 0.95, "every shard has its own shape: " + shapes.size());
    }

    @Test
    void adjacencyIsSymmetric() {
        Fracture f = Fracture.generate(5L);
        for (int i = 0; i < f.neighbours.length; i++) {
            for (int j : f.neighbours[i]) {
                final int self = i;
                assertTrue(Arrays.stream(f.neighbours[j]).anyMatch(k -> k == self), i + " <-> " + j);
            }
        }
    }

    @Test
    void cracksReachEveryJunctionAndBranchInsideShards() {
        for (long seed : SEEDS) {
            Fracture f = Fracture.generate(seed);
            long shell = f.cracks.stream().filter(c -> !c.hairline()).count();
            long hair = f.cracks.stream().filter(CrackSegment::hairline).count();
            assertTrue(shell > f.shards.size(), "shared edges form the crack network");
            assertTrue(hair > f.shards.size() / 5, "hairline branches exist");
            for (CrackSegment c : f.cracks) {
                assertTrue(c.da() <= f.maxFront * 1.5 && c.db() <= f.maxFront * 1.5 + 1, "finite front time");
                double[] r = c.visibleRange(f.maxFront * 1.02 + 2.0);
                if (!c.hairline()) {
                    assertEquals(2, r.length, "fully grown at the end of the crack phase");
                    assertEquals(1.0, r[1], 1e-9);
                }
                double[] none = c.visibleRange(-1);
                assertEquals(0.0, none[1], 1e-9, "nothing visible before the crack starts");
            }
        }
    }

    @Test
    void someShardsFallSomeHoverSomeStay() {
        Fracture f = Fracture.generate(20261108L);
        long detach = f.shards.stream().filter(s -> s.detaches).count();
        long hover = f.shards.stream().filter(s -> s.hovers).count();
        assertTrue(detach > 30, "a large rift opens: " + detach);
        assertTrue(hover > 5 && hover < detach, "some shards hang in the air: " + hover);
        assertTrue(detach < f.shards.size(), "outer shards stay cracked in place");
    }

    @Test
    void shardsMoveDuringTheFallAndFreezeAtTheStop() {
        SkyScene scene = new SkyScene(Fracture.generate(7L));
        SkyScene.ShardPose a = new SkyScene.ShardPose();
        SkyScene.ShardPose b = new SkyScene.ShardPose();
        int moving = 0;
        for (ShardData s : scene.fracture.shards) {
            if (!s.detaches) continue;
            scene.shardPose(s, SkyTimeline.FREEZE - 1, SkyTimeline.RESTORE, a);
            if (a.offset.length() > 1.0) moving++;
            // time stop: the pose is identical for any tick between FREEZE and the restore
            scene.shardPose(s, SkyTimeline.FREEZE + 5, SkyTimeline.RESTORE, a);
            scene.shardPose(s, SkyTimeline.TELEPORT, SkyTimeline.RESTORE, b);
            assertEquals(a.offset, b.offset);
            assertEquals(a.rotation, b.rotation);
        }
        assertTrue(moving > 20, "detached shards have travelled: " + moving);
    }

    @Test
    void restoreStartsAtTheFrozenSnapshotAndEndsSealed() {
        for (long seed : SEEDS) {
            SkyScene scene = new SkyScene(Fracture.generate(seed));
            SkyScene.ShardPose frozen = new SkyScene.ShardPose();
            SkyScene.ShardPose start = new SkyScene.ShardPose();
            SkyScene.ShardPose end = new SkyScene.ShardPose();
            double r = SkyTimeline.RESTORE;
            for (ShardData s : scene.fracture.shards) {
                scene.shardPose(s, r - 0.001, r, frozen);
                scene.shardPose(s, r, r, start);
                assertClose(frozen.offset, start.offset, 0.2, "continuity at restore start");
                scene.shardPose(s, r + SkyTimeline.RESTORE_LENGTH, r, end);
                assertClose(V3.ZERO, end.offset, 1e-9, "shard returns to its exact origin");
                assertEquals(Quat.IDENTITY, end.rotation, "shard returns upright");
                assertEquals(0.0, end.free, 1e-12, "shard is sealed");
            }
            assertEquals(0.0, scene.crackFront(r + SkyTimeline.RESTORE_LENGTH, r), 1e-9, "cracks vanish");
            assertEquals(0.0, scene.shellGlow(r + SkyTimeline.RESTORE_LENGTH, r), 1e-9, "glow fades out");
        }
    }

    @Test
    void abortedEventRestoresFromWhereverItWas() {
        SkyScene scene = new SkyScene(Fracture.generate(3L));
        double abortAt = SkyTimeline.FALL + 30;
        SkyScene.ShardPose p = new SkyScene.ShardPose();
        for (ShardData s : scene.fracture.shards) {
            scene.shardPose(s, abortAt + SkyTimeline.RESTORE_LENGTH, abortAt, p);
            assertClose(V3.ZERO, p.offset, 1e-9, "aborted shards come home too");
        }
        assertEquals(0.0, scene.timeStop(abortAt + 5, abortAt), 1e-12, "no time stop on an early abort");
    }

    @Test
    void hairlinesStayInsideTheirShard() {
        Fracture f = Fracture.generate(11L);
        for (CrackSegment c : f.cracks) {
            if (!c.hairline()) continue;
            ShardData s = f.shards.get(c.cellA());
            assertTrue(inside(s.outline, c.b()), "hairline end inside shard " + s.index);
        }
    }

    private static boolean inside(V2[] poly, V2 p) {
        boolean in = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            V2 a = poly[i], b = poly[j];
            if ((a.y() > p.y()) != (b.y() > p.y()) && p.x() < (b.x() - a.x()) * (p.y() - a.y()) / (b.y() - a.y()) + a.x()) in = !in;
        }
        return in;
    }

    private static void assertClose(V3 expected, V3 actual, double eps, String msg) {
        assertTrue(expected.sub(actual).length() <= eps, msg + ": " + expected + " vs " + actual);
    }
}

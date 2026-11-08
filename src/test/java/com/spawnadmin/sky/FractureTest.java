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
            assertEquals(rim, area, 1e-6, "cells must cover the disc exactly, seed " + seed);
            assertTrue(f.shards.size() >= 100 && f.shards.size() <= Fracture.TARGET_CELLS,
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
            assertTrue(hair > f.shards.size() / 2, "hairline branches exist");
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
        for (int i = 0; i < poly.length; i++) {
            V2 a = poly[i], b = poly[(i + 1) % poly.length];
            if (b.sub(a).cross(p.sub(a)) < -1e-9) return false;
        }
        return true;
    }

    private static void assertClose(V3 expected, V3 actual, double eps, String msg) {
        assertTrue(expected.sub(actual).length() <= eps, msg + ": " + expected + " vs " + actual);
    }
}

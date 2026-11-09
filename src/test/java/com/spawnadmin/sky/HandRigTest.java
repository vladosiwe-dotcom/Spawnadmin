package com.spawnadmin.sky;

import com.spawnadmin.sky.geom.V3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandRigTest {
    private static final V3 LOCK = new V3(3, 0, -2);
    private static final V3 RIFT = new V3(60, 80, 10);

    @Test
    void handAppearsOnlyFromTheHandPhase() {
        HandRig.Pose p = new HandRig.Pose();
        assertFalse(HandRig.pose(SkyTimeline.FREEZE + 5, SkyTimeline.RESTORE, LOCK, 30f, RIFT, p));
        assertTrue(HandRig.pose(SkyTimeline.HAND + 30, SkyTimeline.RESTORE, LOCK, 30f, RIFT, p));
        assertFalse(HandRig.pose(SkyTimeline.TELEPORT + 40, SkyTimeline.RESTORE, LOCK, 30f, RIFT, p), "gone after carrying the player away");
        assertFalse(HandRig.pose(SkyTimeline.HAND + 30, SkyTimeline.FALL + 10, LOCK, 30f, RIFT, p), "no hand on an early abort");
    }

    @Test
    void handEmergesAsASilhouetteFromTheDepthAndApproaches() {
        HandRig.Pose early = new HandRig.Pose(), late = new HandRig.Pose();
        HandRig.pose(SkyTimeline.HAND + 10, SkyTimeline.RESTORE, LOCK, 0f, RIFT, early);
        HandRig.pose(SkyTimeline.GRAB - 5, SkyTimeline.RESTORE, LOCK, 0f, RIFT, late);
        assertTrue(early.fill < 0.05, "first only the outline");
        assertTrue(late.fill > 0.95, "then solid light");
        assertTrue(early.wrist.sub(LOCK).length() > late.wrist.sub(LOCK).length() + 40, "it comes from far away");
    }

    @Test
    void fingersCloseOneAfterAnother() {
        HandRig.Pose p = new HandRig.Pose();
        HandRig.pose(SkyTimeline.GRAB + 12, SkyTimeline.RESTORE, LOCK, 0f, RIFT, p);
        assertTrue(p.curl[0] > p.curl[3], "index closes before the little finger");
        assertTrue(p.curl[3] > p.curl[4] - 1e-9, "the thumb closes last");
        HandRig.pose(SkyTimeline.GRIP + 12, SkyTimeline.RESTORE, LOCK, 0f, RIFT, p);
        for (double c : p.curl) {
            assertEquals(HandRig.CAGE_CURL, c, 0.05, "all fingers locked in the cage");
        }
    }

    @Test
    void liftStartsAtTheLockAndRisesIntoTheRift() {
        V3 start = HandRig.carried(SkyTimeline.LIFT, SkyTimeline.RESTORE, LOCK, 30f, RIFT);
        assertNotNull(start);
        assertTrue(start.sub(LOCK).length() < 1e-9, "no jump when the lift begins");
        V3 late = HandRig.carried(SkyTimeline.TELEPORT - 1, SkyTimeline.RESTORE, LOCK, 30f, RIFT);
        assertTrue(late.y() > 50, "player is pulled up towards the rift: " + late);
        assertNull(HandRig.carried(SkyTimeline.GRAB, SkyTimeline.RESTORE, LOCK, 30f, RIFT));
        double prev = -1;
        for (int t = SkyTimeline.LIFT; t < SkyTimeline.TELEPORT; t++) {
            V3 c = HandRig.carried(t, SkyTimeline.RESTORE, LOCK, 30f, RIFT);
            double step = prev < 0 ? 0 : c.y() - prev;
            assertTrue(step >= 0 && step < 6, "smooth monotonic rise, step " + step);
            prev = c.y();
        }
    }

    /** The hand must hold the player, not pass through them, and must not dig into the ground. */
    @Test
    void handHoldsThePlayerWithoutPassingThroughThem() {
        for (float yaw : new float[]{0f, 90f, 200f}) {
            for (int t : new int[]{SkyTimeline.GRIP + 8, SkyTimeline.LIFT + 5, SkyTimeline.LIFT + 25, SkyTimeline.TELEPORT - 5}) {
                HandRig.Pose p = new HandRig.Pose();
                assertTrue(HandRig.pose(t, SkyTimeline.RESTORE, LOCK, yaw, RIFT, p));
                V3 held = HandRig.carried(t, SkyTimeline.RESTORE, LOCK, yaw, RIFT);
                V3 body = held == null ? LOCK : held;
                int[] inside = {0};
                double[] minY = {Double.MAX_VALUE};
                double[] nearest = {Double.MAX_VALUE};
                HandRig.mesh(p, (pos, n, along, fade) -> {
                    assertFalse(Double.isNaN(pos.x() + pos.y() + pos.z()), "finite mesh");
                    if (fade < 0.99) return;
                    V3 rel = pos.sub(body);
                    double h = Math.hypot(rel.x(), rel.z());
                    if (rel.y() > -0.1 && rel.y() < 2.0 && h < 0.6) inside[0]++;
                    minY[0] = Math.min(minY[0], pos.y());
                    if (rel.y() > -1 && rel.y() < 3.5) nearest[0] = Math.min(nearest[0], h);
                });
                assertEquals(0, inside[0], "no finger inside the body (yaw " + yaw + ", t " + t + ")");
                if (held == null) {
                    assertTrue(minY[0] > LOCK.y() - 0.4, "fingers do not dig into the ground: " + minY[0]);
                }
                assertTrue(nearest[0] < 2.5, "the fingers are close around the body: " + nearest[0]);
            }
        }
    }

    @Test
    void dumpGrabPose() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int t : new int[]{SkyTimeline.HAND + 40, SkyTimeline.GRIP + 10, SkyTimeline.LIFT + 25}) {
            HandRig.Pose p = new HandRig.Pose();
            HandRig.pose(t, SkyTimeline.RESTORE, V3.ZERO, 0f, new V3(0, 90, 60), p);
            V3 held = HandRig.carried(t, SkyTimeline.RESTORE, V3.ZERO, 0f, new V3(0, 90, 60));
            V3 b = held == null ? V3.ZERO : held;
            sb.append(String.format(Locale.ROOT, "B %d %f %f %f%n", t, b.x(), b.y(), b.z()));
            HandRig.mesh(p, (pos, n, along, fade) -> sb.append(String.format(Locale.ROOT, "%d %f %f %f %f%n", t, pos.x(), pos.y(), pos.z(), fade)));
        }
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/hand-dump.txt"), sb.toString());
    }
}

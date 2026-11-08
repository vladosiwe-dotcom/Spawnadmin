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

    @Test
    void handAppearsOnlyFromTheHandPhase() {
        HandRig.Pose p = new HandRig.Pose();
        assertFalse(HandRig.pose(SkyTimeline.FREEZE + 5, SkyTimeline.RESTORE, LOCK, 30f, p));
        assertTrue(HandRig.pose(SkyTimeline.HAND + 30, SkyTimeline.RESTORE, LOCK, 30f, p));
        assertFalse(HandRig.pose(SkyTimeline.TELEPORT + 40, SkyTimeline.RESTORE, LOCK, 30f, p), "gone after carrying the player away");
        assertFalse(HandRig.pose(SkyTimeline.HAND + 30, SkyTimeline.FALL + 10, LOCK, 30f, p), "no hand on an early abort");
    }

    @Test
    void liftStartsAtTheLockAndRisesIntoTheRift() {
        V3 start = HandRig.carried(SkyTimeline.LIFT, SkyTimeline.RESTORE, LOCK, 30f);
        assertNotNull(start);
        assertTrue(start.sub(LOCK).length() < 1e-9, "no jump when the lift begins");
        V3 late = HandRig.carried(SkyTimeline.TELEPORT - 1, SkyTimeline.RESTORE, LOCK, 30f);
        assertTrue(late.y() > 60, "player is pulled up towards the rift: " + late);
        assertNull(HandRig.carried(SkyTimeline.GRAB, SkyTimeline.RESTORE, LOCK, 30f));
        double prev = -1;
        for (int t = SkyTimeline.LIFT; t < SkyTimeline.TELEPORT; t++) {
            V3 c = HandRig.carried(t, SkyTimeline.RESTORE, LOCK, 30f);
            double step = prev < 0 ? 0 : c.y() - prev;
            assertTrue(step >= 0 && step < 6, "smooth monotonic rise, step " + step);
            prev = c.y();
        }
    }

    @Test
    void closedHandWrapsAroundThePlayer() {
        HandRig.Pose p = new HandRig.Pose();
        assertTrue(HandRig.pose(SkyTimeline.LIFT, SkyTimeline.RESTORE, LOCK, 0f, p));
        double[] near = {Double.MAX_VALUE};
        int[] count = {0};
        V3 body = LOCK.add(new V3(0, 0.9, 0));
        HandRig.mesh(p, (pos, n, along, fade) -> {
            assertFalse(Double.isNaN(pos.x() + pos.y() + pos.z() + n.x() + n.y() + n.z()), "finite mesh");
            near[0] = Math.min(near[0], pos.sub(body).length());
            count[0]++;
        });
        assertEquals(0, count[0] % 3, "triangle list");
        assertTrue(count[0] > 1000, "detailed mesh");
        assertTrue(near[0] < 2.6, "fingers close around the body, nearest " + near[0]);
    }

    @Test
    void dumpGrabPose() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int t : new int[]{SkyTimeline.HAND + 40, SkyTimeline.LIFT}) {
            HandRig.Pose p = new HandRig.Pose();
            HandRig.pose(t, SkyTimeline.RESTORE, V3.ZERO, 0f, p);
            HandRig.mesh(p, (pos, n, along, fade) -> sb.append(String.format(Locale.ROOT, "%d %f %f %f %f%n", t, pos.x(), pos.y(), pos.z(), fade)));
        }
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/hand-dump.txt"), sb.toString());
    }
}

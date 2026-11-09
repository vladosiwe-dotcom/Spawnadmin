package com.spawnadmin.sky;

import com.spawnadmin.sky.geom.V3;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Parameter sweep for the grab geometry. Run explicitly: ./gradlew test --tests '*HandGrabProbe*' */
@Tag("probe")
class HandGrabProbe {
    record Score(int penetration, double minY, int sectors, double nearest) {
    }

    static Score evaluate(double t, V3 lock) {
        HandRig.Pose p = new HandRig.Pose();
        V3 rift = HandRig.defaultRiftHeart();
        if (!HandRig.pose(t, SkyTimeline.RESTORE, lock, 0f, rift, p)) return null;
        V3 body = HandRig.carried(t, SkyTimeline.RESTORE, lock, 0f, rift);
        if (body == null) body = lock;
        final V3 b = body;
        int[] pen = {0};
        double[] minY = {Double.MAX_VALUE};
        double[] nearest = {Double.MAX_VALUE};
        boolean[] sector = new boolean[12];
        HandRig.mesh(p, (pos, n, along, fade) -> {
            if (fade < 0.99) return; // forearm
            V3 rel = pos.sub(b);
            double h = Math.hypot(rel.x(), rel.z());
            if (rel.y() > -0.1 && rel.y() < 2.0 && h < 0.6) pen[0]++;
            minY[0] = Math.min(minY[0], pos.y() - lock.y());
            if (rel.y() > -1 && rel.y() < 3.5 && h < 5) {
                nearest[0] = Math.min(nearest[0], h);
                int s = (int) Math.floor((Math.atan2(rel.z(), rel.x()) + Math.PI) / (Math.PI * 2) * 12) % 12;
                sector[s] = true;
            }
        });
        int sectors = 0;
        for (boolean v : sector) if (v) sectors++;
        return new Score(pen[0], minY[0], sectors, nearest[0]);
    }

    @Test
    void sweep() {
        V3 lock = V3.ZERO;
        double bestScore = -1e9, bh = 0, bb = 0;
        for (double h = 5; h <= 14; h += 0.4) {
            for (double back = 0; back <= 9; back += 0.4) {
                HandRig.grabHeight = h;
                HandRig.grabBack = back;
                Score cage = evaluate(SkyTimeline.GRIP + 10, lock);
                Score lift = evaluate(SkyTimeline.LIFT + 20, lock);
                if (cage == null || lift == null) continue;
                if (cage.penetration() > 0 || lift.penetration() > 0 || cage.minY() < -0.3) continue;
                double score = cage.sectors() * 2 + lift.sectors() - cage.nearest() - lift.nearest();
                if (score > bestScore) {
                    bestScore = score; bh = h; bb = back;
                }
            }
        }
        HandRig.grabHeight = bh;
        HandRig.grabBack = bb;
        System.out.println("BEST grabHeight=" + bh + " grabBack=" + bb + " score=" + bestScore
                + " cage=" + evaluate(SkyTimeline.GRIP + 10, lock) + " lift=" + evaluate(SkyTimeline.LIFT + 20, lock));
    }
}

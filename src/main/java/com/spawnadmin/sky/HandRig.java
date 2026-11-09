package com.spawnadmin.sky;

import com.spawnadmin.sky.geom.DomeShape;
import com.spawnadmin.sky.geom.Quat;
import com.spawnadmin.sky.geom.V3;

/**
 * The giant hand of light: deterministic pose over time plus a procedural mesh.
 *
 * <p>All coordinates are relative to the event center, in world axes. The hand comes out of the
 * depth of the rift (first a faint silhouette, then filled with light), approaches with its
 * fingers flexing one by one, hovers over the frozen target, closes finger after finger into a
 * cage around them and, as it pulls them up, scoops the fingers under their feet. Every client
 * evaluates the same pose from (t, restoreAt, lock position, yaw, rift heart), and the carried
 * player position is derived from the same wrist path, so hand and player stay together on every
 * screen.</p>
 */
public final class HandRig {
    /** Overall scale: fingers ~9.5 blocks long, the whole hand ~20 blocks tall. */
    public static final double SCALE = 2.0;
    private static final double PALM_LENGTH = 5.4 * SCALE;
    private static final double PALM_HALF_WIDTH = 2.6 * SCALE;
    private static final double PALM_HALF_THICK = 0.85 * SCALE;
    /** Grab geometry, tuned by {@code HandRigTest} (no body or ground penetration, enclosure). */
    static double grabHeight = 9.0;
    static double grabBack = 5.2;
    /** Finger curl of the cage around the target, and of the scoop under them while lifting. */
    static final double CAGE_CURL = 0.62;
    static final double SCOOP_CURL = 0.98;

    private static final double[] FINGER_OFFSET = {1.95, 0.65, -0.65, -1.95};
    private static final double[] FINGER_LENGTH = {4.7, 5.2, 4.9, 3.9};
    private static final double[] FINGER_SPREAD = {0.22, 0.07, -0.07, -0.22};
    private static final double[] PHALANX = {0.45, 0.31, 0.24};
    private static final double[] JOINT_CURL = {1.05, 1.35, 1.0};
    /** Order in which the fingers lock: index, middle, ring, little, then the thumb. */
    private static final int[] CLOSE_DELAY = {0, 2, 4, 6, 9};

    private HandRig() {
    }

    public static final class Pose {
        public V3 wrist = V3.ZERO;
        /** Finger direction, palm normal (towards the target) and side (thumb side). */
        public V3 f = V3.UP;
        public V3 n = V3.UP;
        public V3 s = V3.UP;
        /** Curl per finger (index, middle, ring, little, thumb): 0 open, 1 closed. */
        public final double[] curl = new double[5];
        /** Finger spread: 1 = fully splayed, 0 = together. */
        public double spread;
        /** Overall light intensity and how "solid" the hand is (0 = silhouette only). */
        public double intensity;
        public double fill;
        /** Where the forearm disappears into the rift. */
        public V3 armEnd = V3.ZERO;
    }

    public interface Sink {
        /** One vertex of a triangle list. {@code along} drives the energy flow, {@code fade} the alpha. */
        void vertex(V3 pos, V3 normal, double along, double fade);
    }

    /** Default rift heart (above the apex), for callers without a fracture. */
    public static V3 defaultRiftHeart() {
        return DomeShape.apex().add(new V3(0, 28, 0));
    }

    /** Horizontal look direction for a Minecraft yaw (degrees). */
    public static V3 lookDir(float yawDegrees) {
        double r = Math.toRadians(yawDegrees);
        return new V3(-Math.sin(r), 0, Math.cos(r));
    }

    /** @return false when the hand is not visible at {@code t}. */
    public static boolean pose(double t, double restoreAt, V3 lock, float lockYaw, V3 riftHeart, Pose out) {
        if (t < SkyTimeline.HAND || restoreAt <= SkyTimeline.HAND) {
            return false;
        }
        Frame g = grabFrame(lock, lockYaw, riftHeart);
        V3 axis = riftHeart.sub(g.wrist).normalize();
        V3 deep = riftHeart.add(axis.mul(70));
        V3 hover = g.wrist.add(new V3(0, 3.5 * SCALE, 0)).sub(g.f.mul(1.2 * SCALE));

        double fadeIn = SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.HAND, SkyTimeline.HAND + 18));
        double fadeOut = 1 - SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.TELEPORT - 4, SkyTimeline.TELEPORT + 30));
        if (restoreAt < SkyTimeline.TELEPORT) {
            fadeOut = Math.min(fadeOut, 1 - SkyScene.smooth(SkyTimeline.progress(t, restoreAt, restoreAt + 16)));
        }
        out.intensity = fadeIn * fadeOut;
        if (out.intensity <= 0.001) {
            return false;
        }
        // First only the outline of something enormous, then it fills with light.
        out.fill = SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.HAND + 20, SkyTimeline.HAND + 65));

        if (t < SkyTimeline.GRAB) {
            double p = SkyScene.easeInOutCubic(SkyTimeline.progress(t, SkyTimeline.HAND, SkyTimeline.GRAB));
            out.wrist = deep.lerp(hover, p);
            out.spread = 0.3 + 0.7 * SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.GRAB - 30, SkyTimeline.GRAB));
            for (int i = 0; i < 5; i++) {
                // Each finger flexes on its own while it reaches.
                out.curl[i] = 0.18 + 0.14 * Math.sin(t * 0.13 + i * 1.3) * (1 - out.spread) - 0.2 * out.spread;
            }
        } else if (t < SkyTimeline.LIFT) {
            double p = SkyScene.easeInOut(SkyTimeline.progress(t, SkyTimeline.GRAB, SkyTimeline.GRAB + 14));
            out.wrist = hover.lerp(g.wrist, p);
            out.spread = 1.0 - SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.GRAB + 8, SkyTimeline.GRIP));
            for (int i = 0; i < 5; i++) {
                out.curl[i] = closeCurve(t - (SkyTimeline.GRAB + 6 + CLOSE_DELAY[i]));
            }
        } else {
            double p = liftCurve(SkyTimeline.progress(t, SkyTimeline.LIFT, SkyTimeline.TELEPORT));
            out.wrist = g.wrist.lerp(riftHeart, p);
            double scoop = SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.LIFT, SkyTimeline.LIFT + 14));
            for (int i = 0; i < 5; i++) {
                out.curl[i] = CAGE_CURL + (SCOOP_CURL - CAGE_CURL) * scoop;
            }
            out.spread = 0;
            if (t > SkyTimeline.TELEPORT) {
                double after = SkyTimeline.progress(t, SkyTimeline.TELEPORT, SkyTimeline.TELEPORT + 30);
                out.wrist = riftHeart.add(axis.mul(40 * after));
            }
        }
        out.f = g.f;
        out.n = g.n;
        out.s = g.s;
        out.armEnd = out.wrist.add(axis.mul(90));
        return true;
    }

    /** A finger snapping shut: fast, a slight squeeze past the cage, then settling into it. */
    private static double closeCurve(double dt) {
        if (dt <= 0) {
            return -0.2;
        }
        double p = Math.min(1, dt / 9.0);
        double shut = -0.2 + (CAGE_CURL + 0.12 + 0.2) * (1 - Math.pow(1 - p, 3));
        double settle = SkyScene.smooth(SkyTimeline.progress(dt, 8, 13));
        return shut + (CAGE_CURL - shut) * settle;
    }

    /**
     * Position of the carried player (relative to the event center), or null when the player is
     * not being carried at {@code t}.
     */
    public static V3 carried(double t, double restoreAt, V3 lock, float lockYaw, V3 riftHeart) {
        if (t < SkyTimeline.LIFT || t >= SkyTimeline.TELEPORT || restoreAt <= t) {
            return null;
        }
        Frame g = grabFrame(lock, lockYaw, riftHeart);
        double p = liftCurve(SkyTimeline.progress(t, SkyTimeline.LIFT, SkyTimeline.TELEPORT));
        return lock.add(riftHeart.sub(g.wrist).mul(p));
    }

    /** A heavy start (the grip takes the weight), then an accelerating pull into the sky. */
    static double liftCurve(double x) {
        return x * x * (0.25 + 0.75 * x);
    }

    record Frame(V3 wrist, V3 f, V3 n, V3 s) {
    }

    /**
     * Grab from above, palm down: the palm hangs high over the target's head, the fingers reach
     * past them and come down around them like a cage; the thumb closes from the side.
     */
    static Frame grabFrame(V3 lock, float yaw, V3 riftHeart) {
        // The hand reaches from the rift: fingers point away from it, past the target.
        V3 away = new V3(lock.x() - riftHeart.x(), 0, lock.z() - riftHeart.z());
        V3 f = away.length() > 4.0 ? away.normalize() : lookDir(yaw);
        V3 n = new V3(0, -1, 0);
        V3 s = f.cross(n).normalize();
        V3 palmCenter = lock.add(new V3(0, grabHeight, 0)).sub(f.mul(grabBack));
        V3 wrist = palmCenter.sub(f.mul(PALM_LENGTH * 0.5));
        return new Frame(wrist, f, n, s);
    }

    // ================================================================== mesh

    public static void mesh(Pose p, Sink sink) {
        V3 f = p.f, n = p.n, s = p.s;

        // Forearm: from deep inside the rift down to the wrist, dissolving with distance.
        int armRings = 8;
        V3[] armPts = new V3[armRings];
        double[] armRx = new double[armRings], armRy = new double[armRings], armFade = new double[armRings];
        for (int i = 0; i < armRings; i++) {
            double k = i / (double) (armRings - 1);
            armPts[i] = p.armEnd.lerp(p.wrist.sub(f.mul(0.3)), k * k * (3 - 2 * k) * 0.35 + k * 0.65);
            armRx[i] = (3.0 - 0.9 * k) * SCALE;
            armRy[i] = (2.4 - 0.9 * k) * SCALE;
            armFade[i] = Math.pow(k, 1.6);
        }
        tube(armPts, armRx, armRy, armFade, s, 16, 0, false, sink);

        // Palm: an elliptic slab, a little wider at the knuckles.
        V3[] palm = {p.wrist.sub(f.mul(0.4)), p.wrist.add(f.mul(PALM_LENGTH * 0.45)), p.wrist.add(f.mul(PALM_LENGTH))};
        double[] prx = {PALM_HALF_WIDTH * 0.82, PALM_HALF_WIDTH, PALM_HALF_WIDTH * 1.02};
        double[] pry = {PALM_HALF_THICK * 1.05, PALM_HALF_THICK, PALM_HALF_THICK * 0.9};
        tube(palm, prx, pry, new double[]{1, 1, 1}, s, 18, 10, true, sink);

        // Four fingers bending about the side axis towards the palm normal.
        V3 knuckles = p.wrist.add(f.mul(PALM_LENGTH - 0.35));
        for (int i = 0; i < 4; i++) {
            V3 base = knuckles.add(s.mul(FINGER_OFFSET[i] * SCALE));
            double spread = FINGER_SPREAD[i] * p.spread;
            V3 dir = Quat.axisAngle(n, spread).rotate(f);
            V3 axis = Quat.axisAngle(n, spread).rotate(s);
            finger(base, dir, axis, FINGER_LENGTH[i] * SCALE, 0.62 * SCALE, p.curl[i], 20 + i * 5, sink);
        }

        // Thumb: from the side of the palm, folding across it.
        V3 thumbBase = p.wrist.add(f.mul(1.3 * SCALE)).add(s.mul(PALM_HALF_WIDTH * 0.85));
        V3 thumbDir = f.mul(0.55).add(s.mul(0.75 + 0.2 * p.spread)).add(n.mul(0.35)).normalize();
        finger(thumbBase, thumbDir, f.mul(-1), 4.0 * SCALE, 0.72 * SCALE, p.curl[4] * 0.95, 45, sink);
    }

    private static void finger(V3 base, V3 dir, V3 axis, double length, double radius, double curl, double flowOffset, Sink sink) {
        int rings = PHALANX.length * 2 + 2;
        V3[] pts = new V3[rings];
        double[] rx = new double[rings], ry = new double[rings], fade = new double[rings];
        V3 p = base;
        V3 d = dir;
        int r = 0;
        pts[r] = p;
        rx[r] = ry[r] = radius;
        fade[r++] = 1;
        for (int j = 0; j < PHALANX.length; j++) {
            d = Quat.axisAngle(axis, curl * JOINT_CURL[j]).rotate(d);
            double seg = length * PHALANX[j];
            for (int h = 1; h <= 2; h++) {
                p = p.add(d.mul(seg * 0.5));
                double k = (j * 2 + h) / (double) (PHALANX.length * 2);
                pts[r] = p;
                rx[r] = ry[r] = radius * (1 - 0.28 * k);
                fade[r++] = 1;
            }
        }
        pts[r] = p.add(d.mul(radius * 0.55));
        rx[r] = ry[r] = radius * 0.35;
        fade[r] = 1;
        tube(pts, rx, ry, fade, axis, 12, flowOffset, true, sink);
    }

    /**
     * Elliptic tube through {@code pts}; cross-section axes are {@code side} and
     * tangent x side, which never twist because every joint bends about {@code side}.
     */
    private static void tube(V3[] pts, double[] rx, double[] ry, double[] fade, V3 side, int segs, double flowOffset,
                             boolean capEnd, Sink sink) {
        int n = pts.length;
        V3[][] ring = new V3[n][segs];
        V3[][] nrm = new V3[n][segs];
        double along = flowOffset;
        double[] alongAt = new double[n];
        for (int i = 0; i < n; i++) {
            V3 tan = (i == n - 1 ? pts[i].sub(pts[i - 1]) : pts[i + 1].sub(pts[i])).normalize();
            if (i > 0 && i < n - 1) {
                tan = pts[i + 1].sub(pts[i - 1]).normalize();
            }
            V3 u = side.sub(tan.mul(side.dot(tan))).normalize();
            V3 v = tan.cross(u).normalize();
            if (i > 0) {
                along += pts[i].sub(pts[i - 1]).length() * 0.2;
            }
            alongAt[i] = along;
            for (int k = 0; k < segs; k++) {
                double a = Math.PI * 2 * k / segs;
                double c = Math.cos(a), sn = Math.sin(a);
                ring[i][k] = pts[i].add(u.mul(c * rx[i])).add(v.mul(sn * ry[i]));
                nrm[i][k] = u.mul(c / Math.max(rx[i], 1e-6)).add(v.mul(sn / Math.max(ry[i], 1e-6))).normalize();
            }
        }
        for (int i = 0; i < n - 1; i++) {
            for (int k = 0; k < segs; k++) {
                int k2 = (k + 1) % segs;
                quad(sink, ring[i][k], nrm[i][k], ring[i][k2], nrm[i][k2], ring[i + 1][k2], nrm[i + 1][k2],
                        ring[i + 1][k], nrm[i + 1][k], alongAt[i], alongAt[i + 1], fade[i], fade[i + 1]);
            }
        }
        if (capEnd) {
            V3 tip = pts[n - 1];
            V3 tipN = pts[n - 1].sub(pts[n - 2]).normalize();
            for (int k = 0; k < segs; k++) {
                int k2 = (k + 1) % segs;
                sink.vertex(ring[n - 1][k], nrm[n - 1][k], alongAt[n - 1], fade[n - 1]);
                sink.vertex(ring[n - 1][k2], nrm[n - 1][k2], alongAt[n - 1], fade[n - 1]);
                sink.vertex(tip, tipN, alongAt[n - 1], fade[n - 1]);
            }
        }
    }

    private static void quad(Sink sink, V3 a, V3 na, V3 b, V3 nb, V3 c, V3 nc, V3 d, V3 nd,
                             double alongA, double alongB, double fadeA, double fadeB) {
        sink.vertex(a, na, alongA, fadeA);
        sink.vertex(b, nb, alongA, fadeA);
        sink.vertex(c, nc, alongB, fadeB);
        sink.vertex(a, na, alongA, fadeA);
        sink.vertex(c, nc, alongB, fadeB);
        sink.vertex(d, nd, alongB, fadeB);
    }
}

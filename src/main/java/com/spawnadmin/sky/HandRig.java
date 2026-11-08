package com.spawnadmin.sky;

import com.spawnadmin.sky.geom.DomeShape;
import com.spawnadmin.sky.geom.Quat;
import com.spawnadmin.sky.geom.V3;

/**
 * The giant hand of light: deterministic pose over time plus a procedural mesh.
 *
 * <p>All coordinates are relative to the event center. The hand comes out of the rift above the
 * dome apex, opens in front of the frozen summoner, closes around them and pulls them back up
 * into the rift. Every client evaluates the same pose from (t, restoreAt, lock position, yaw),
 * and the carried player position is derived from the same wrist path, so the hand and the
 * player stay together on every screen.</p>
 */
public final class HandRig {
    /** Overall scale: fingers ~5 blocks long, the whole hand ~10 blocks tall. */
    public static final double SCALE = 1.0;
    private static final double PALM_LENGTH = 5.4 * SCALE;
    private static final double PALM_HALF_WIDTH = 2.6 * SCALE;
    private static final double PALM_HALF_THICK = 0.85 * SCALE;

    private static final double[] FINGER_OFFSET = {1.95, 0.65, -0.65, -1.95};
    private static final double[] FINGER_LENGTH = {4.7, 5.2, 4.9, 3.9};
    private static final double[] FINGER_SPREAD = {0.2, 0.07, -0.07, -0.2};
    private static final double[] PHALANX = {0.45, 0.31, 0.24};
    private static final double[] JOINT_CURL = {1.05, 1.35, 1.0};

    private HandRig() {
    }

    public static final class Pose {
        public V3 wrist = V3.ZERO;
        /** Finger direction, palm normal (towards the player) and side (thumb side). */
        public V3 f = V3.UP;
        public V3 n = V3.UP;
        public V3 s = V3.UP;
        /** 0 = open hand, 1 = closed fist. */
        public double curl;
        public double intensity;
        /** Where the forearm disappears into the rift. */
        public V3 armEnd = V3.ZERO;
    }

    public interface Sink {
        /** One vertex of a triangle list. {@code along} drives the energy flow, {@code fade} the alpha. */
        void vertex(V3 pos, V3 normal, double along, double fade);
    }

    /** The rift heart above the dome apex, where the arm comes from and returns to. */
    public static V3 riftHeart() {
        return DomeShape.apex().add(new V3(0, 28, 0));
    }

    /** Horizontal look direction for a Minecraft yaw (degrees). */
    public static V3 lookDir(float yawDegrees) {
        double r = Math.toRadians(yawDegrees);
        return new V3(-Math.sin(r), 0, Math.cos(r));
    }

    /** @return false when the hand is not visible at {@code t}. */
    public static boolean pose(double t, double restoreAt, V3 lock, float lockYaw, Pose out) {
        if (t < SkyTimeline.HAND || restoreAt <= SkyTimeline.HAND) {
            return false;
        }
        Frame g = grabFrame(lock, lockYaw);
        V3 start = riftHeart();
        V3 hover = g.wrist.add(new V3(0, 4.5, 0)).sub(g.f.mul(1.5));

        double fadeIn = SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.HAND, SkyTimeline.HAND + 22));
        double fadeOut = 1 - SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.TELEPORT - 4, SkyTimeline.TELEPORT + 24));
        if (restoreAt < SkyTimeline.TELEPORT) {
            fadeOut = Math.min(fadeOut, 1 - SkyScene.smooth(SkyTimeline.progress(t, restoreAt, restoreAt + 16)));
        }
        out.intensity = fadeIn * fadeOut;
        if (out.intensity <= 0.001) {
            return false;
        }

        if (t < SkyTimeline.GRAB) {
            double p = easeOutCubic(SkyTimeline.progress(t, SkyTimeline.HAND, SkyTimeline.GRAB));
            out.wrist = start.lerp(hover, p);
            out.curl = -0.12;
        } else if (t < SkyTimeline.LIFT) {
            double p = SkyScene.easeInOut(SkyTimeline.progress(t, SkyTimeline.GRAB, SkyTimeline.LIFT));
            out.wrist = hover.lerp(g.wrist, Math.min(1, p * 1.35));
            out.curl = -0.12 + 1.04 * p;
        } else {
            double p = liftCurve(SkyTimeline.progress(t, SkyTimeline.LIFT, SkyTimeline.TELEPORT));
            out.wrist = g.wrist.lerp(start, p);
            out.curl = 0.92;
            if (t > SkyTimeline.TELEPORT) {
                double after = SkyTimeline.progress(t, SkyTimeline.TELEPORT, SkyTimeline.TELEPORT + 24);
                out.wrist = start.add(new V3(0, 18 * after, 0));
            }
        }
        out.f = g.f;
        out.n = g.n;
        out.s = g.s;
        out.armEnd = out.wrist.add(riftHeart().sub(g.wrist).normalize().mul(70));
        return true;
    }

    /**
     * Position of the carried player (relative to the event center), or null when the player is
     * not being carried at {@code t}.
     */
    public static V3 carried(double t, double restoreAt, V3 lock, float lockYaw) {
        if (t < SkyTimeline.LIFT || t >= SkyTimeline.TELEPORT || restoreAt <= t) {
            return null;
        }
        Frame g = grabFrame(lock, lockYaw);
        V3 start = riftHeart();
        double p = liftCurve(SkyTimeline.progress(t, SkyTimeline.LIFT, SkyTimeline.TELEPORT));
        return lock.add(start.sub(g.wrist).mul(p));
    }

    /** Slow start (the grip tightens), then an accelerating pull into the sky. */
    private static double liftCurve(double x) {
        return x * x * (0.4 + 0.6 * x);
    }

    private record Frame(V3 wrist, V3 f, V3 n, V3 s) {
    }

    /**
     * Grab from above, palm down: the palm hangs over the summoner's head, the fingers reach past
     * them and curl down around their body, the thumb closes from the side.
     */
    private static Frame grabFrame(V3 lock, float yaw) {
        V3 f = lookDir(yaw);
        V3 n = new V3(0, -1, 0);
        V3 s = f.cross(n).normalize();
        V3 palmCenter = lock.add(new V3(0, 1.8 + 0.6 + PALM_HALF_THICK, 0)).sub(f.mul(1.0 * SCALE));
        V3 wrist = palmCenter.sub(f.mul(PALM_LENGTH * 0.5));
        return new Frame(wrist, f, n, s);
    }

    private static double easeOutCubic(double x) {
        double y = 1 - SkyScene.clamp(x);
        return 1 - y * y * y;
    }

    // ================================================================== mesh

    public static void mesh(Pose p, Sink sink) {
        V3 f = p.f, n = p.n, s = p.s;
        double curl = p.curl;

        // Forearm: from deep inside the rift down to the wrist, dissolving with distance.
        int armRings = 7;
        V3[] armPts = new V3[armRings];
        double[] armRx = new double[armRings], armRy = new double[armRings], armFade = new double[armRings];
        for (int i = 0; i < armRings; i++) {
            double k = i / (double) (armRings - 1);
            armPts[i] = p.armEnd.lerp(p.wrist.sub(f.mul(0.3)), k * k * (3 - 2 * k) * 0.35 + k * 0.65);
            armRx[i] = (3.0 - 0.9 * k) * SCALE;
            armRy[i] = (2.4 - 0.9 * k) * SCALE;
            armFade[i] = Math.pow(k, 1.6);
        }
        tube(armPts, armRx, armRy, armFade, s, 14, 0, false, sink);

        // Palm: an elliptic slab, a little wider at the knuckles.
        V3[] palm = {p.wrist.sub(f.mul(0.4)), p.wrist.add(f.mul(PALM_LENGTH * 0.45)), p.wrist.add(f.mul(PALM_LENGTH))};
        double[] prx = {PALM_HALF_WIDTH * 0.82, PALM_HALF_WIDTH, PALM_HALF_WIDTH * 1.02};
        double[] pry = {PALM_HALF_THICK * 1.05, PALM_HALF_THICK, PALM_HALF_THICK * 0.9};
        tube(palm, prx, pry, new double[]{1, 1, 1}, s, 16, 10, true, sink);

        // Four fingers bending about the side axis towards the palm normal.
        V3 knuckles = p.wrist.add(f.mul(PALM_LENGTH - 0.35));
        for (int i = 0; i < 4; i++) {
            V3 base = knuckles.add(s.mul(FINGER_OFFSET[i] * SCALE));
            double spread = FINGER_SPREAD[i] * (1 - SkyScene.clamp(curl));
            V3 dir = Quat.axisAngle(n, spread).rotate(f);
            V3 axis = Quat.axisAngle(n, spread).rotate(s);
            double bend = curl * (1 + 0.06 * (i - 1.5));
            finger(base, dir, axis, FINGER_LENGTH[i] * SCALE, 0.62 * SCALE, bend, 20 + i * 5, sink);
        }

        // Thumb: from the side of the palm, folding across it.
        V3 thumbBase = p.wrist.add(f.mul(1.3)).add(s.mul(PALM_HALF_WIDTH * 0.85));
        V3 thumbDir = f.mul(0.55).add(s.mul(0.75)).add(n.mul(0.35)).normalize();
        finger(thumbBase, thumbDir, f.mul(-1), 4.0 * SCALE, 0.72 * SCALE, curl * 0.95, 45, sink);
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
        // Rounded tip.
        pts[r] = p.add(d.mul(radius * 0.55));
        rx[r] = ry[r] = radius * 0.35;
        fade[r] = 1;
        tube(pts, rx, ry, fade, axis, 10, flowOffset, true, sink);
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

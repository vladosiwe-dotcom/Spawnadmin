package com.spawnadmin.sky;

import com.spawnadmin.sky.geom.CrackSegment;
import com.spawnadmin.sky.geom.DebrisData;
import com.spawnadmin.sky.geom.Fracture;
import com.spawnadmin.sky.geom.Quat;
import com.spawnadmin.sky.geom.ShardData;
import com.spawnadmin.sky.geom.V3;

/**
 * Time evaluation of a sky event: given the event-relative tick {@code t} and the restore tick,
 * returns where every shard is, how far the cracks have spread and how strong each effect is.
 *
 * <p>This is a pure function of (seed, t, restoreAt). The destruction and the reassembly share the
 * same {@link ShardData}: the restore starts from a snapshot of the scripted fall evaluated at the
 * moment time stopped, so every shard flies back along a path that begins exactly where it hung.</p>
 */
public final class SkyScene {
    /** Real-feeling fall: strong gravity, light air drag (terminal speed ~150 blocks/s, never reached). */
    private static final V3 GRAVITY = new V3(0, -18.0, 0);
    private static final double DRAG = 0.12;
    /** How deep the sky is pressed in at the impact before it snaps (blocks). */
    public static final double DENT_DEPTH = 7.0;
    private static final double DENT_RADIUS = 0.2;

    /** Ground height (relative to the event center, fracture frame) at a fracture-frame (x, z). */
    public interface Ground {
        double heightAt(double x, double z);
    }

    public final Fracture fracture;
    private final Ground ground;
    // Landing caches: computed once per shard/debris from the deterministic trajectory.
    private final double[] landTau;
    private final V3[] landOffset;
    private final Quat[] landRot, flatRot;
    private final double[] debrisLandTau;
    private final V3[] debrisLandOffset;
    private final Quat[] debrisLandRot, debrisFlatRot;

    public SkyScene(Fracture fracture) {
        this(fracture, (x, z) -> 0.0);
    }

    public SkyScene(Fracture fracture, Ground ground) {
        this.fracture = fracture;
        this.ground = ground;
        int n = fracture.shards.size(), m = fracture.debris.size();
        landTau = new double[n];
        java.util.Arrays.fill(landTau, Double.NaN);
        landOffset = new V3[n];
        landRot = new Quat[n];
        flatRot = new Quat[n];
        debrisLandTau = new double[m];
        java.util.Arrays.fill(debrisLandTau, Double.NaN);
        debrisLandOffset = new V3[m];
        debrisLandRot = new Quat[m];
        debrisFlatRot = new Quat[m];
    }

    /** Mutable pose of a shard, reused per frame to avoid garbage. */
    public static final class ShardPose {
        /** Offset of the shard centroid from its rest position (blocks). */
        public V3 offset = V3.ZERO;
        public Quat rotation = Quat.IDENTITY;
        /** 0 = sealed in the dome, 1 = fully broken free (drives the rift window behind it). */
        public double free;
        /** Edge flash on docking during the restore. */
        public double flash;
        /** Visibility (fades shards that sink below the ground plane). */
        public double alpha = 1;
    }

    // ================================================================== global intensities

    /**
     * How luminous the sealed shell is. Nothing glows while the first cracks creep; the sky starts
     * to light up as the cracks join (CONNECT) and is fully lit when it gives way.
     */
    public double shellGlow(double t, double restoreAt) {
        double g = smooth(SkyTimeline.progress(t, SkyTimeline.CONNECT, SkyTimeline.FALL));
        if (t >= restoreAt) {
            g *= 1.0 - smoothstep(0.8, 1.0, restoreProgress(t, restoreAt));
        }
        return g;
    }

    /** Extra white bloom on the glass: grows with the damage, bursts when the dome gives way. */
    public double skyPulse(double t, double restoreAt) {
        double glow = shellGlow(t, restoreAt);
        double burst = t < restoreAt ? Math.exp(-Math.abs(t - SkyTimeline.FALL) / 8.0) : 0;
        return glow * 0.3 + burst * 0.6;
    }

    /**
     * How hard the sky is being pressed in (0..1): it bows inward before it snaps, keeps the load
     * while it cracks, and springs back during the restore.
     */
    public double pressure(double t, double restoreAt) {
        double p = 0.8 * Math.pow(smooth(SkyTimeline.progress(t, SkyTimeline.PRESS, SkyTimeline.FIRST_CRACK)), 1.6)
                + 0.2 * smooth(SkyTimeline.progress(t, SkyTimeline.FIRST_CRACK, SkyTimeline.FALL));
        if (t >= restoreAt) {
            p *= 1.0 - smoothstep(0.15, 0.6, restoreProgress(t, restoreAt));
        }
        return p;
    }

    /** Inward displacement (blocks) of the shell at disc point (u, v): a smooth bowl around the impact. */
    public double dent(double u, double v, double pressure) {
        double du = u - fracture.impact.x(), dv = v - fracture.impact.y();
        double d2 = (du * du + dv * dv) / (DENT_RADIUS * DENT_RADIUS);
        return DENT_DEPTH * pressure * Math.exp(-d2);
    }

    /** How far the two halves of the sky have parted along the great split (0..1). */
    public double splitGap(double t, double restoreAt) {
        double g = smooth(SkyTimeline.progress(t, SkyTimeline.CONNECT - 40, SkyTimeline.FALL));
        if (t >= restoreAt) {
            g *= 1.0 - smoothstep(0.1, 0.5, restoreProgress(t, restoreAt));
        }
        return g;
    }

    /** Crack front in the propagation metric: a sudden star at the snap, then slow and accelerating. */
    public double crackFront(double t, double restoreAt) {
        double grow = SkyTimeline.progress(Math.min(t, restoreAt), SkyTimeline.FIRST_CRACK, SkyTimeline.CRACKS_COMPLETE);
        double burst = t >= SkyTimeline.FIRST_CRACK ? 0.004 * smooth((Math.min(t, restoreAt) - SkyTimeline.FIRST_CRACK) / 5.0) : 0;
        double front = fracture.maxFront * 1.02 * Math.max(Math.pow(grow, Fracture.FRONT_EXPONENT), burst);
        if (t >= restoreAt) {
            // The cracks heal last, after the shards are back, drawing back towards where they began.
            front *= 1.0 - smoothstep(0.62, 0.95, restoreProgress(t, restoreAt));
        }
        return front;
    }

    /**
     * Width multiplier of a crack: a fresh crack is a hair-thin line, it widens and deepens as it
     * ages, and the whole network deepens as the dome is about to give way.
     */
    public double crackWidth(CrackSegment c, double front, double t) {
        double age = (front - Math.min(c.da(), c.db())) / Math.max(fracture.maxFront * 0.3, 1e-9);
        double deepen = 0.55 + 0.45 * smooth(SkyTimeline.progress(t, SkyTimeline.CONNECT - 40, SkyTimeline.FALL));
        return (0.12 + 0.88 * smooth(age)) * deepen;
    }

    /** Overall crack brightness: the very first crack is barely visible. */
    public double crackIntensity(double t, double restoreAt) {
        double in = 0.55 + 0.45 * smooth(SkyTimeline.progress(t, SkyTimeline.FIRST_CRACK, SkyTimeline.FIRST_CRACK + 80));
        double snap = t >= SkyTimeline.FIRST_CRACK ? 1.2 * Math.exp(-(t - SkyTimeline.FIRST_CRACK) / 6.0) : 0;
        double surge = 0.35 * smooth(SkyTimeline.progress(t, SkyTimeline.CHIPS, SkyTimeline.FALL));
        return in + snap + surge;
    }

    /** Brightness of the other reality behind the opening: it keeps growing while it opens. */
    public double riftBrightness(double t, double restoreAt) {
        double open = 0.25 + 1.05 * smooth(SkyTimeline.progress(t, SkyTimeline.FALL, SkyTimeline.FREEZE + 60));
        if (t >= restoreAt) {
            // The rift contracts first.
            open *= 1.0 - smoothstep(0.0, 0.35, restoreProgress(t, restoreAt));
        }
        return open;
    }

    /** Volumetric light shafts pouring out of the rift. */
    public double beams(double t, double restoreAt) {
        if (restoreAt <= SkyTimeline.FALL) {
            return 0;
        }
        double in = smooth(SkyTimeline.progress(t, SkyTimeline.FALL + 30, SkyTimeline.FREEZE));
        double out = t >= restoreAt ? 1.0 - smoothstep(0.0, 0.25, restoreProgress(t, restoreAt)) : 1.0;
        return in * out;
    }

    /** Strength of the "time stopped" look (0..1). */
    public double timeStop(double t, double restoreAt) {
        if (restoreAt <= SkyTimeline.FREEZE) {
            return 0;
        }
        double in = smooth(SkyTimeline.progress(t, SkyTimeline.FREEZE, SkyTimeline.FREEZE + 10));
        double end = Math.min(SkyTimeline.TELEPORT + 6, restoreAt);
        double out = 1 - smooth(SkyTimeline.progress(t, end, end + 16));
        return in * out;
    }

    public static double restoreProgress(double t, double restoreAt) {
        return SkyTimeline.progress(t, restoreAt, restoreAt + SkyTimeline.RESTORE_LENGTH);
    }

    // ================================================================== shards

    public void shardPose(ShardData s, double t, double restoreAt, ShardPose out) {
        out.flash = 0;
        out.alpha = 1;
        double clock = SkyTimeline.shardClock(t, restoreAt);
        fallPose(s, clock, out);
        land(s, clock - s.detachDelay, out);
        if (t < restoreAt) {
            addShimmer(s, t, clock, out);
        } else {
            restorePose(s, restoreProgress(t, restoreAt), out);
        }
        // The halves part only while the glass is whole; a free shard leaves the push behind.
        double gap = splitGap(t, restoreAt) * (1 - out.free);
        if (gap > 0 && s.splitPush.length() > 0) {
            out.offset = out.offset.add(s.splitPush.mul(gap));
        }
    }

    /** Ground contact: the shard lies where it hit the ground, flat, after a small bounce. */
    private void land(ShardData s, double tau, ShardPose out) {
        if (!s.detaches || s.hovers || tau <= 0) {
            return;
        }
        int i = s.index;
        if (Double.isNaN(landTau[i])) {
            computeLanding(i, s.origin, s.velocity, s.spinAxis, s.spinSpeed, s.normal, s.thickness * 0.5,
                    landTau, landOffset, landRot, flatRot);
        }
        settle(tau, landTau[i], landOffset[i], landRot[i], flatRot[i], out);
    }

    private void computeLanding(int i, V3 origin, V3 velocity, V3 axis, double spin, V3 normal, double half,
                                double[] tauOut, V3[] offOut, Quat[] rotOut, Quat[] flatOut) {
        ShardPose p = new ShardPose();
        double prev = 0;
        for (double tau = 0.05; tau <= 12.0; tau += 0.05) {
            ballistic(velocity, axis, spin, tau, p);
            double x = origin.x() + p.offset.x(), z = origin.z() + p.offset.z();
            double gy = ground.heightAt(x, z);
            if (origin.y() + p.offset.y() - half <= gy + 0.05) {
                double lo = prev, hi = tau;
                for (int k = 0; k < 8; k++) {
                    double mid = (lo + hi) * 0.5;
                    ballistic(velocity, axis, spin, mid, p);
                    if (origin.y() + p.offset.y() - half <= gy + 0.05) hi = mid; else lo = mid;
                }
                ballistic(velocity, axis, spin, hi, p);
                tauOut[i] = hi;
                offOut[i] = new V3(p.offset.x(), gy + half + 0.06 - origin.y(), p.offset.z());
                rotOut[i] = p.rotation;
                V3 n = p.rotation.rotate(normal);
                V3 up = n.y() >= 0 ? V3.UP : V3.UP.mul(-1);
                flatOut[i] = Quat.fromTo(n, up).mul(p.rotation);
                return;
            }
            prev = tau;
        }
        tauOut[i] = Double.POSITIVE_INFINITY;
    }

    private static void settle(double tau, double landAt, V3 landOff, Quat landR, Quat flatR, ShardPose out) {
        if (tau < landAt) {
            return;
        }
        double k = (tau - landAt) / 0.3;
        double bounce = Math.sin(Math.PI * clamp(k)) * 0.45 * (1 - clamp(k));
        out.offset = landOff.add(new V3(0, bounce, 0));
        out.rotation = landR.slerp(flatR, smooth(clamp(k * 1.4)));
    }

    /** Seconds (shard clock) at which shard {@code i} hits the ground, or infinity / NaN if never. */
    public double landingTime(ShardData s) {
        if (!s.detaches || s.hovers) {
            return Double.POSITIVE_INFINITY;
        }
        if (Double.isNaN(landTau[s.index])) {
            computeLanding(s.index, s.origin, s.velocity, s.spinAxis, s.spinSpeed, s.normal, s.thickness * 0.5,
                    landTau, landOffset, landRot, flatRot);
        }
        return landTau[s.index] + s.detachDelay;
    }

    public V3 landingOffset(ShardData s) {
        landingTime(s);
        return landOffset[s.index];
    }

    /** Scripted motion after the shard breaks free; {@code clock} is seconds since FALL. */
    public static void fallPose(ShardData s, double clock, ShardPose out) {
        double tau = clock - s.detachDelay;
        if (!s.detaches || tau <= 0) {
            out.offset = V3.ZERO;
            out.rotation = Quat.IDENTITY;
            out.free = 0;
            return;
        }
        out.free = smooth(clamp(tau / 0.35));
        if (s.hovers) {
            double k = 1 - Math.exp(-tau / 0.7);
            double bob = Math.sin(tau * 1.3 + s.tint * 6.0) * 0.4 * k;
            out.offset = s.hoverOffset.mul(k).add(new V3(0, bob, 0));
            double angle = s.hoverTilt * (1 - Math.exp(-tau / 1.1)) + 0.05 * Math.sin(tau * 0.9 + s.tint * 3);
            out.rotation = Quat.axisAngle(s.spinAxis, angle);
        } else {
            ballistic(s.velocity, s.spinAxis, s.spinSpeed, tau, out);
        }
    }

    private static void ballistic(V3 velocity, V3 axis, double spin, double tau, ShardPose out) {
        double e = Math.exp(-DRAG * tau);
        V3 launch = velocity.mul((1 - e) / DRAG);
        V3 fall = GRAVITY.mul(tau / DRAG - (1 - e) / (DRAG * DRAG));
        out.offset = launch.add(fall);
        out.rotation = Quat.axisAngle(axis, spin * tau);
    }

    /** Pose of a debris splinter: chipped off with its shard, frozen with it, returned with it. */
    public void debrisPose(DebrisData d, double t, double restoreAt, ShardPose out) {
        out.flash = 0;
        double tau = d.isChip()
                ? SkyTimeline.frozenSeconds(t, restoreAt) - d.chipTime
                : SkyTimeline.shardClock(t, restoreAt) - d.detachDelay;
        if (tau <= 0) {
            out.offset = V3.ZERO;
            out.rotation = Quat.IDENTITY;
            out.free = 0;
            out.alpha = 0;
            return;
        }
        out.free = 1;
        ballistic(d.velocity, d.spinAxis, d.spinSpeed, tau, out);
        int i = d.index;
        if (Double.isNaN(debrisLandTau[i])) {
            computeLanding(i, d.origin, d.velocity, d.spinAxis, d.spinSpeed, d.normal, 0.02,
                    debrisLandTau, debrisLandOffset, debrisLandRot, debrisFlatRot);
        }
        if (debrisLandOffset[i] != null) {
            settle(tau, debrisLandTau[i], debrisLandOffset[i], debrisLandRot[i], debrisFlatRot[i], out);
        }
        out.alpha = smooth(clamp(tau / 0.15));
        if (t >= restoreAt) {
            double u = clamp((restoreProgress(t, restoreAt) - d.restoreStart) / d.restoreLength);
            returnHome(d.normal, u, out);
            // Splinters melt back into their shard as it seals.
            out.alpha *= 1.0 - smoothstep(0.82, 1.0, u);
        }
    }

    /** Glass under stress: shards tremble before breaking free. */
    private static void addShimmer(ShardData s, double t, double clock, ShardPose out) {
        if (t < SkyTimeline.CHIPS || out.free > 0) {
            return;
        }
        double amp = smooth(SkyTimeline.progress(t, SkyTimeline.CHIPS, SkyTimeline.FALL)) * (s.detaches ? 1.0 : 0.3);
        double wobble = Math.sin(t * 2.7 + s.tint * 40.0);
        out.offset = out.offset.add(s.normal.mul(0.12 * amp * wobble));
        out.rotation = out.rotation.mul(Quat.axisAngle(s.spinAxis, 0.012 * amp * Math.sin(t * 3.1 + s.tint * 20.0)));
    }

    /**
     * Reassembly from the frozen snapshot already stored in {@code out}: the shard turns back
     * upright, flies home along an arc, overshoots slightly and seals with a flash.
     */
    public static void restorePose(ShardData s, double restoreProgress, ShardPose out) {
        if (out.free <= 0) {
            return;
        }
        double u = clamp((restoreProgress - s.restoreStart) / s.restoreLength);
        returnHome(s.normal, u, out);
        out.free *= 1.0 - smoothstep(0.9, 1.0, u);
    }

    /** Shared reassembly path: turn upright, fly home along an arc, overshoot and seal. */
    private static void returnHome(V3 normal, double u, ShardPose out) {
        V3 p0 = out.offset;
        Quat q0 = out.rotation;
        double turn = easeInOut(clamp(u / 0.55));
        out.rotation = q0.slerp(Quat.IDENTITY, turn);

        double travel = easeInOutCubic(clamp((u - 0.12) / 0.88));
        V3 control = p0.mul(0.5).add(normal.mul(p0.length() * 0.25));
        double a = 1 - travel;
        out.offset = p0.mul(a * a).add(control.mul(2 * a * travel));

        if (u > 0.86 && u < 1.0) {
            double bump = Math.sin(Math.PI * (u - 0.86) / 0.14);
            out.offset = out.offset.add(normal.mul(0.35 * bump));
            out.flash = bump;
        }
        if (u >= 1.0) {
            out.offset = V3.ZERO;
            out.rotation = Quat.IDENTITY;
        }
    }

    // ================================================================== cracks

    /**
     * A shell crack is drawn on the dome only while both shards beside it are sealed; once one
     * breaks free its edge glow takes over. Hairlines always travel with their own shard.
     */
    public boolean crackOnShell(CrackSegment c, double[] freeByShard) {
        if (c.hairline()) {
            return true;
        }
        return freeByShard[c.cellA()] < 0.02 && freeByShard[c.cellB()] < 0.02;
    }

    // ================================================================== easing

    public static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    public static double smooth(double x) {
        x = clamp(x);
        return x * x * (3 - 2 * x);
    }

    public static double smoothstep(double a, double b, double x) {
        return smooth((x - a) / (b - a));
    }

    public static double easeInOut(double x) {
        return 0.5 - 0.5 * Math.cos(Math.PI * clamp(x));
    }

    public static double easeInOutCubic(double x) {
        x = clamp(x);
        return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
    }
}

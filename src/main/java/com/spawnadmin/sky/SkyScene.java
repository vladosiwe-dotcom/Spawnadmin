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
    private static final V3 GRAVITY = new V3(0, -7.0, 0);
    private static final double DRAG = 0.45;

    public final Fracture fracture;

    public SkyScene(Fracture fracture) {
        this.fracture = fracture;
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

    /** Crack front in the propagation metric: a slow, hesitant start that accelerates. */
    public double crackFront(double t, double restoreAt) {
        double grow = SkyTimeline.progress(Math.min(t, restoreAt), SkyTimeline.FIRST_CRACK, SkyTimeline.CRACKS_COMPLETE);
        double front = fracture.maxFront * 1.02 * Math.pow(grow, Fracture.FRONT_EXPONENT);
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
        double in = 0.25 + 0.75 * smooth(SkyTimeline.progress(t, SkyTimeline.FIRST_CRACK, SkyTimeline.SPREAD + 40));
        double surge = 0.35 * smooth(SkyTimeline.progress(t, SkyTimeline.CHIPS, SkyTimeline.FALL));
        return in + surge;
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
        if (t < restoreAt) {
            fallPose(s, clock, out);
            addShimmer(s, t, clock, out);
        } else {
            fallPose(s, clock, out);
            restorePose(s, restoreProgress(t, restoreAt), out);
        }
        double y = s.origin.y() + out.offset.y();
        out.alpha = clamp((y - 0.5) / 4.0);
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
        out.alpha = smooth(clamp(tau / 0.15));
        if (t >= restoreAt) {
            double u = clamp((restoreProgress(t, restoreAt) - d.restoreStart) / d.restoreLength);
            returnHome(d.normal, u, out);
            // Splinters melt back into their shard as it seals.
            out.alpha *= 1.0 - smoothstep(0.82, 1.0, u);
        }
        double y = d.origin.y() + out.offset.y();
        out.alpha *= clamp((y - 0.5) / 4.0);
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

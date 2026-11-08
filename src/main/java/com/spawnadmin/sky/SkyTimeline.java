package com.spawnadmin.sky;

/**
 * Shared timeline of a Heavenly Summoning event, in game ticks since the sky starts to glow.
 * The server and every client use the same constants, so the scene stays in sync without
 * streaming animation data: clients only need the start tick, the seed and the restore tick.
 */
public final class SkyTimeline {
    public static final int GLOW = 0;
    public static final int CRACK = 40;
    public static final int SHATTER = 140;
    public static final int FALL = 165;
    public static final int FREEZE = 235;
    public static final int HAND = 265;
    public static final int GRAB = 325;
    public static final int LIFT = 345;
    public static final int TELEPORT = 390;
    public static final int RESTORE = 400;
    public static final int RESTORE_LENGTH = 140;

    /** Time the falling shards keep simulating before the freeze. */
    public static final int FALL_LENGTH = FREEZE - FALL;

    public enum Phase {
        GLOW, CRACK, SHATTER, FALL, FREEZE, HAND, GRAB, LIFT, GONE, RESTORE, DONE
    }

    private SkyTimeline() {
    }

    /**
     * @param t          ticks since the event start (may be fractional on the client)
     * @param restoreAt  tick at which the restore begins (RESTORE normally, earlier on abort)
     */
    public static Phase phaseAt(double t, double restoreAt) {
        if (t >= restoreAt + RESTORE_LENGTH) return Phase.DONE;
        if (t >= restoreAt) return Phase.RESTORE;
        if (t >= TELEPORT) return Phase.GONE;
        if (t >= LIFT) return Phase.LIFT;
        if (t >= GRAB) return Phase.GRAB;
        if (t >= HAND) return Phase.HAND;
        if (t >= FREEZE) return Phase.FREEZE;
        if (t >= FALL) return Phase.FALL;
        if (t >= SHATTER) return Phase.SHATTER;
        if (t >= CRACK) return Phase.CRACK;
        return Phase.GLOW;
    }

    /** Clamped progress of {@code t} inside [from, to). */
    public static double progress(double t, double from, double to) {
        if (t <= from) return 0.0;
        if (t >= to) return 1.0;
        return (t - from) / (to - from);
    }

    /**
     * Simulation clock of the falling shards: it runs from the FALL tick and stops at the freeze
     * (or at an earlier abort). Both the fall and the restore read shard state through this clock,
     * which is what keeps destruction and reassembly consistent.
     */
    public static double shardClock(double t, double restoreAt) {
        double stop = Math.min(FREEZE, restoreAt);
        return Math.max(0.0, Math.min(t, stop) - FALL) / 20.0;
    }
}

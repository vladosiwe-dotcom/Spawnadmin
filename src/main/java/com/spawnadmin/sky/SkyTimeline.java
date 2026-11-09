package com.spawnadmin.sky;

/**
 * Shared timeline of a Heavenly Summoning event, in game ticks since the first crack appears
 * (the hidden wait before it is server-only). The server and every client use the same
 * constants, so the scene stays in sync without streaming animation data.
 *
 * <pre>
 *   0  FIRST_CRACK  one barely visible crack in front of the target's eyes, growing slowly
 *  60  SPREAD       thin branches; new small cracks appear elsewhere
 * 180  CONNECT      the cracks join up, widen and deepen; the sky starts to glow
 * 240  CHIPS        small chips of glass pop out of the crack junctions
 * 270  FRAGMENTS    the first single fragments break away
 * 300  FALL         the dome gives way: shards fly outward and fall, the rift opens
 * 420  FREEZE       time stops locally
 * 450  HAND         the hand emerges from the depth of the rift
 * 530  GRAB         the fingers close one after another
 * 560  LIFT         the hand pulls the target up into the rift
 * 620  TELEPORT     the target vanishes into the light
 * 640  RESTORE      200 ticks of reassembly: the rift contracts, shards return, cracks heal
 * </pre>
 */
public final class SkyTimeline {
    public static final int FIRST_CRACK = 0;
    /** Kept for code that refers to the start of the visible part. */
    public static final int GLOW = FIRST_CRACK;
    public static final int CRACK = FIRST_CRACK;
    public static final int SPREAD = 60;
    public static final int CONNECT = 180;
    public static final int CHIPS = 240;
    public static final int FRAGMENTS = 270;
    /** Kept name: shards tremble from here on. */
    public static final int SHATTER = FRAGMENTS;
    public static final int FALL = 300;
    public static final int FREEZE = 420;
    public static final int HAND = 450;
    public static final int GRAB = 530;
    /** The fingers lock around the target: the impact moment. */
    public static final int GRIP = GRAB + 16;
    public static final int LIFT = 560;
    public static final int TELEPORT = 620;
    public static final int RESTORE = 640;
    public static final int RESTORE_LENGTH = 200;

    /** End of the crack growth: the front covers the dome at this tick. */
    public static final int CRACKS_COMPLETE = CHIPS;

    public enum Phase {
        HIDDEN, FIRST_CRACK, SPREAD, CONNECT, CHIPS, FRAGMENTS, FALL, FREEZE, HAND, GRAB, LIFT, GONE, RESTORE, DONE
    }

    private SkyTimeline() {
    }

    /**
     * @param t          ticks since the first crack (negative during the hidden wait)
     * @param restoreAt  tick at which the restore begins (RESTORE normally, earlier on abort)
     */
    public static Phase phaseAt(double t, double restoreAt) {
        if (t < 0) return Phase.HIDDEN;
        if (t >= restoreAt + RESTORE_LENGTH) return Phase.DONE;
        if (t >= restoreAt) return Phase.RESTORE;
        if (t >= TELEPORT) return Phase.GONE;
        if (t >= LIFT) return Phase.LIFT;
        if (t >= GRAB) return Phase.GRAB;
        if (t >= HAND) return Phase.HAND;
        if (t >= FREEZE) return Phase.FREEZE;
        if (t >= FALL) return Phase.FALL;
        if (t >= FRAGMENTS) return Phase.FRAGMENTS;
        if (t >= CHIPS) return Phase.CHIPS;
        if (t >= CONNECT) return Phase.CONNECT;
        if (t >= SPREAD) return Phase.SPREAD;
        return Phase.FIRST_CRACK;
    }

    /** Clamped progress of {@code t} inside [from, to). */
    public static double progress(double t, double from, double to) {
        if (t <= from) return 0.0;
        if (t >= to) return 1.0;
        return (t - from) / (to - from);
    }

    /**
     * Simulation clock of the breaking glass in seconds relative to FALL: negative before it (the
     * first fragments break away earlier), stopped at the freeze or at an earlier abort. Both the
     * fall and the restore read shard state through this clock, which keeps destruction and
     * reassembly consistent.
     */
    public static double shardClock(double t, double restoreAt) {
        double stop = Math.min(FREEZE, restoreAt);
        return (Math.min(t, stop) - FALL) / 20.0;
    }

    /** Event time in seconds, stopped at the freeze (chips and cracks freeze with time too). */
    public static double frozenSeconds(double t, double restoreAt) {
        return Math.min(t, Math.min(FREEZE, restoreAt)) / 20.0;
    }
}

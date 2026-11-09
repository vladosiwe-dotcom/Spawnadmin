package com.spawnadmin.sky;

/**
 * Shared timeline of a Heavenly Summoning event, in game ticks since the first crack appears
 * (the hidden wait before it is server-only). The server and every client use the same
 * constants, so the scene stays in sync without streaming animation data.
 *
 * <pre>
 *   0  PRESS        the sky is pressed in: the glass bows inward in front of the target, creaking
 *  60  FIRST_CRACK  it snaps: a sharp star of cracks, and one great crack splits the whole sky
 *  60  SPREAD       the cracks keep spreading and branching; new ones start elsewhere
 * 200  CONNECT      the cracks join up, widen and deepen; light leaks through the great split
 * 250  CHIPS        small chips of glass pop out of the crack junctions
 * 275  FRAGMENTS    the first single fragments break away
 * 300  FALL         the dome gives way with a roar: shards fall all the way down onto the world
 * 420  FREEZE       time stops locally
 * 450  HAND         the hand emerges from the depth of the rift
 * 530  GRAB         the target is caught where they are (they may run until now); the fingers
 *                   close one after another
 * 560  LIFT         the hand pulls the target up into the rift
 * 620  TELEPORT     the target vanishes into the light
 * 640  RESTORE      200 ticks of reassembly: the rift contracts, shards return, cracks heal
 * </pre>
 */
public final class SkyTimeline {
    /** The visible part starts here: the glass is being pressed in. */
    public static final int PRESS = 0;
    /** The snap: the first sharp crack. */
    public static final int FIRST_CRACK = 60;
    /** Kept for code that refers to the start of the visible part. */
    public static final int GLOW = PRESS;
    public static final int CRACK = FIRST_CRACK;
    public static final int SPREAD = FIRST_CRACK;
    public static final int CONNECT = 200;
    public static final int CHIPS = 250;
    public static final int FRAGMENTS = 275;
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
        HIDDEN, PRESS, FIRST_CRACK, CONNECT, CHIPS, FRAGMENTS, FALL, FREEZE, HAND, GRAB, LIFT, GONE, RESTORE, DONE
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
        if (t >= FIRST_CRACK) return Phase.FIRST_CRACK;
        return Phase.PRESS;
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

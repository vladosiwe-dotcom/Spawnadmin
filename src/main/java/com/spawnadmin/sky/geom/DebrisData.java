package com.spawnadmin.sky.geom;

/**
 * A tiny splinter chipped off a shard when it breaks free. Same scripted motion model as a
 * shard (fall, freeze, restore along the same timeline), but small, fast-spinning and many.
 */
public final class DebrisData {
    public final int parent;
    /** Rest position on the dome (relative to the event center) and its disc coordinate. */
    public final V3 origin;
    public final V2 disc;
    /** Triangle corners relative to {@link #origin}. */
    public final V3[] tri;
    public final V3 normal;
    public final double detachDelay;
    public final V3 velocity;
    public final V3 spinAxis;
    public final double spinSpeed;
    public final double restoreStart;
    public final double restoreLength;
    /**
     * For chips popping out of the cracks before the collapse: the event time in seconds at which
     * it flies off. NaN for splinters that break away with their shard.
     */
    public final double chipTime;

    DebrisData(int parent, V3 origin, V2 disc, V3[] tri, V3 normal, double detachDelay, V3 velocity, V3 spinAxis,
               double spinSpeed, double restoreStart, double restoreLength, double chipTime) {
        this.parent = parent;
        this.origin = origin;
        this.disc = disc;
        this.tri = tri;
        this.normal = normal;
        this.detachDelay = detachDelay;
        this.velocity = velocity;
        this.spinAxis = spinAxis;
        this.spinSpeed = spinSpeed;
        this.restoreStart = restoreStart;
        this.restoreLength = restoreLength;
        this.chipTime = chipTime;
    }

    public boolean isChip() {
        return !Double.isNaN(chipTime);
    }
}

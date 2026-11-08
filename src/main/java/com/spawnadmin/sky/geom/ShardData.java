package com.spawnadmin.sky.geom;

/**
 * Static data of one glass shard: its outline, its rest pose on the dome and its scripted motion.
 * Everything is generated once per event from the seed and never changes, so the fall and the
 * restore animation read exactly the same numbers.
 */
public final class ShardData {
    public final int index;
    /** Convex outline in disc space, counter-clockwise. */
    public final V2[] outline;
    /** Area centroid in disc space. */
    public final V2 centroid2;
    /** Rest position of the centroid on the dome (relative to the event center). */
    public final V3 origin;
    /** Outward dome normal at the centroid. */
    public final V3 normal;
    /** Outline vertices relative to {@link #origin}: outer face then inner face. */
    public final V3[] outer;
    public final V3[] inner;
    /** Distance from the impact point in disc units. */
    public final double impactDist;

    public final boolean detaches;
    public final boolean hovers;
    /** Seconds after the FALL tick at which this shard breaks free. */
    public final double detachDelay;
    /** Ballistic launch velocity (blocks / second) and spin. */
    public final V3 velocity;
    public final V3 spinAxis;
    public final double spinSpeed;
    /** Hover target offset and the final tilt for hovering shards. */
    public final V3 hoverOffset;
    public final double hoverTilt;
    /** Restore window inside the restore phase (fractions of RESTORE_LENGTH). */
    public final double restoreStart;
    public final double restoreLength;
    /** Per-shard random number for shading variation. */
    public final double tint;

    ShardData(int index, V2[] outline, V2 centroid2, V3 origin, V3 normal, V3[] outer, V3[] inner, double impactDist,
              boolean detaches, boolean hovers, double detachDelay, V3 velocity, V3 spinAxis, double spinSpeed,
              V3 hoverOffset, double hoverTilt, double restoreStart, double restoreLength, double tint) {
        this.index = index;
        this.outline = outline;
        this.centroid2 = centroid2;
        this.origin = origin;
        this.normal = normal;
        this.outer = outer;
        this.inner = inner;
        this.impactDist = impactDist;
        this.detaches = detaches;
        this.hovers = hovers;
        this.detachDelay = detachDelay;
        this.velocity = velocity;
        this.spinAxis = spinAxis;
        this.spinSpeed = spinSpeed;
        this.hoverOffset = hoverOffset;
        this.hoverTilt = hoverTilt;
        this.restoreStart = restoreStart;
        this.restoreLength = restoreLength;
        this.tint = tint;
    }

    /** Area of the outline in disc units². */
    public double area2() {
        double a = 0;
        for (int i = 0; i < outline.length; i++) {
            a += outline[i].cross(outline[(i + 1) % outline.length]);
        }
        return a * 0.5;
    }
}

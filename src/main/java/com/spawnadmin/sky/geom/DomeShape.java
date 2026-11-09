package com.spawnadmin.sky.geom;

/**
 * The glass sky shell: a spherical cap above the event center. Coordinates are relative to the
 * event center (the summoner's position when the sky started glowing).
 *
 * <p>The fracture pattern lives in "disc space": the unit disc mapped onto the cap with an
 * azimuthal equidistant projection, so equal disc distances are equal arc lengths on the dome.</p>
 */
public final class DomeShape {
    public static final double RADIUS = 110.0;
    /** Sphere center sits below the summoner, which flattens the visible dome into a sky vault. */
    public static final double CENTER_DROP = 40.0;
    /** Polar angle of the rim: rim height = RADIUS*cos(THETA_MAX) - CENTER_DROP = 15 blocks. */
    public static final double THETA_MAX = Math.acos(55.0 / 110.0);
    /** Glass thickness of the shell. */
    public static final double THICKNESS = 0.55;

    public static final V3 SPHERE_CENTER = new V3(0, -CENTER_DROP, 0);

    private DomeShape() {
    }

    /** Unit radial direction (outward normal) of the disc point. */
    public static V3 normalAt(double u, double v) {
        double r = Math.sqrt(u * u + v * v);
        double theta = r * THETA_MAX;
        double phi = StrictMath.atan2(v, u);
        double s = StrictMath.sin(theta);
        return new V3(s * StrictMath.cos(phi), StrictMath.cos(theta), s * StrictMath.sin(phi));
    }

    /** Point on the shell surface (offset along the normal by {@code lift}). */
    public static V3 pointAt(double u, double v, double lift) {
        return SPHERE_CENTER.add(normalAt(u, v).mul(RADIUS + lift));
    }

    /** Allocation-free {@link #pointAt}: writes the point into {@code out[0..2]}. */
    public static void pointAtInto(double u, double v, double lift, double[] out) {
        double r = Math.sqrt(u * u + v * v);
        double theta = r * THETA_MAX;
        double s = Math.sin(theta);
        double c = Math.cos(theta);
        double cphi = r < 1e-12 ? 1 : u / r, sphi = r < 1e-12 ? 0 : v / r;
        double k = RADIUS + lift;
        out[0] = s * cphi * k;
        out[1] = c * k - CENTER_DROP;
        out[2] = s * sphi * k;
    }

    public static V3 pointAt(V2 p, double lift) {
        return pointAt(p.x(), p.y(), lift);
    }

    /** Arc length on the dome (blocks) of one disc-space unit. */
    public static double blocksPerDiscUnit() {
        return RADIUS * THETA_MAX;
    }

    /** The highest point of the dome, where the hand emerges. */
    public static V3 apex() {
        return pointAt(0, 0, 0);
    }
}

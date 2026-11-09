package com.spawnadmin.sky.geom;

/**
 * One straight piece of a crack in disc space.
 *
 * <p>Growth is expressed in the crack-propagation metric: the crack front reaches {@code a} at
 * {@code da} and {@code b} at {@code db}. Shell edges grow from both ends (fronts can arrive from
 * either side); hairlines grow only from {@code a} to {@code b}.</p>
 *
 * @param cellA,cellB shards on both sides of a shell edge; for hairlines both equal the owner
 * @param hairline    true for fine branches inside a single shard
 * @param width       crack width in blocks
 * @param main        part of the great crack that splits the whole sky in two
 */
public record CrackSegment(V2 a, V2 b, double da, double db, double weight, int cellA, int cellB,
                           boolean hairline, double width, boolean main) {

    /**
     * Visible portion at front distance {@code front}. Returns {start fraction from a, end
     * fraction from a}; for a two-ended edge, the parts grown from each end are merged.
     */
    public double[] visibleRange(double front) {
        if (hairline) {
            double f = clamp((front - da) / Math.max(weight, 1e-9));
            return new double[]{0.0, f};
        }
        double fa = clamp((front - da) / Math.max(weight, 1e-9));
        double fb = clamp((front - db) / Math.max(weight, 1e-9));
        if (fa + fb >= 1.0) {
            return new double[]{0.0, 1.0};
        }
        // Two separate pieces; callers draw [0,fa] and [1-fb,1]. Encode as four values.
        return new double[]{0.0, fa, 1.0 - fb, 1.0};
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}

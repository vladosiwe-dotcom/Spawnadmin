package com.spawnadmin.sky.geom;

/** Minimal immutable 2D vector used for the fracture pattern in disc space. */
public record V2(double x, double y) {
    public V2 add(V2 o) {
        return new V2(x + o.x, y + o.y);
    }

    public V2 sub(V2 o) {
        return new V2(x - o.x, y - o.y);
    }

    public V2 mul(double s) {
        return new V2(x * s, y * s);
    }

    public double dot(V2 o) {
        return x * o.x + y * o.y;
    }

    public double cross(V2 o) {
        return x * o.y - y * o.x;
    }

    public double length() {
        return Math.sqrt(x * x + y * y);
    }

    public double dist(V2 o) {
        double dx = x - o.x, dy = y - o.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public V2 lerp(V2 o, double t) {
        return new V2(x + (o.x - x) * t, y + (o.y - y) * t);
    }

    public V2 rotate(double angle) {
        double c = StrictMath.cos(angle), s = StrictMath.sin(angle);
        return new V2(x * c - y * s, x * s + y * c);
    }

    public V2 normalize() {
        double l = length();
        return l < 1e-12 ? new V2(0, 0) : mul(1.0 / l);
    }
}

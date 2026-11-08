package com.spawnadmin.sky.geom;

/** Minimal immutable 3D vector (double precision, no Minecraft dependency). */
public record V3(double x, double y, double z) {
    public static final V3 ZERO = new V3(0, 0, 0);
    public static final V3 UP = new V3(0, 1, 0);

    public V3 add(V3 o) {
        return new V3(x + o.x, y + o.y, z + o.z);
    }

    public V3 sub(V3 o) {
        return new V3(x - o.x, y - o.y, z - o.z);
    }

    public V3 mul(double s) {
        return new V3(x * s, y * s, z * s);
    }

    public double dot(V3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public V3 cross(V3 o) {
        return new V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public V3 normalize() {
        double l = length();
        return l < 1e-12 ? ZERO : mul(1.0 / l);
    }

    public V3 lerp(V3 o, double t) {
        return new V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /** Any unit vector perpendicular to this one. */
    public V3 anyPerpendicular() {
        V3 a = Math.abs(x) < 0.9 ? new V3(1, 0, 0) : new V3(0, 1, 0);
        return cross(a).normalize();
    }
}

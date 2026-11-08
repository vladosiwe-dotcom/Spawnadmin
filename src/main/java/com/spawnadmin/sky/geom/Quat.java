package com.spawnadmin.sky.geom;

/** Unit quaternion (w + xi + yj + zk) for shard and hand rotations. */
public record Quat(double w, double x, double y, double z) {
    public static final Quat IDENTITY = new Quat(1, 0, 0, 0);

    public static Quat axisAngle(V3 axis, double angle) {
        V3 a = axis.normalize();
        double h = angle * 0.5, s = Math.sin(h);
        return new Quat(Math.cos(h), a.x() * s, a.y() * s, a.z() * s);
    }

    public Quat mul(Quat q) {
        return new Quat(
                w * q.w - x * q.x - y * q.y - z * q.z,
                w * q.x + x * q.w + y * q.z - z * q.y,
                w * q.y - x * q.z + y * q.w + z * q.x,
                w * q.z + x * q.y - y * q.x + z * q.w);
    }

    public V3 rotate(V3 v) {
        // v' = v + 2w(q x v) + 2 q x (q x v)
        double tx = 2 * (y * v.z() - z * v.y());
        double ty = 2 * (z * v.x() - x * v.z());
        double tz = 2 * (x * v.y() - y * v.x());
        return new V3(
                v.x() + w * tx + (y * tz - z * ty),
                v.y() + w * ty + (z * tx - x * tz),
                v.z() + w * tz + (x * ty - y * tx));
    }

    public double dot(Quat q) {
        return w * q.w + x * q.x + y * q.y + z * q.z;
    }

    public Quat normalize() {
        double l = Math.sqrt(w * w + x * x + y * y + z * z);
        return l < 1e-12 ? IDENTITY : new Quat(w / l, x / l, y / l, z / l);
    }

    /** Spherical interpolation along the shortest arc. */
    public Quat slerp(Quat to, double t) {
        double d = dot(to);
        Quat b = to;
        if (d < 0) {
            d = -d;
            b = new Quat(-to.w, -to.x, -to.y, -to.z);
        }
        if (d > 0.9995) {
            return new Quat(w + (b.w - w) * t, x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t).normalize();
        }
        double theta = Math.acos(d);
        double s = Math.sin(theta);
        double wa = Math.sin((1 - t) * theta) / s, wb = Math.sin(t * theta) / s;
        return new Quat(w * wa + b.w * wb, x * wa + b.x * wb, y * wa + b.y * wb, z * wa + b.z * wb);
    }
}

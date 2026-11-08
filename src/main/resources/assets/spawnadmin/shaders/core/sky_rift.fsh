#version 150

// The other reality seen through the broken sky. The patch is only a window: the colour is
// computed from the world-space view direction, so it reads as a deep, infinite space.
// RiftCenter.xyz: direction from the camera towards the rift's heart, w: overall brightness.
uniform float GameTime;
uniform vec4 RiftCenter;

in vec4 vertexColor;
in vec2 texCoord;
in vec3 worldNormal;
in vec3 viewNormal;
in vec3 worldPos;

out vec4 fragColor;

float hash3(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise3(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash3(i + vec3(0, 0, 0)), hash3(i + vec3(1, 0, 0)), f.x),
                   mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x),
                   mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise3(p);
        p = p * 2.03 + vec3(1.7, 9.2, 3.1);
        a *= 0.5;
    }
    return v;
}

void main() {
    vec3 D = normalize(worldPos);
    vec3 C = normalize(RiftCenter.xyz);
    float t = GameTime * 1200.0;
    float toward = max(dot(D, C), 0.0);

    // Swirling luminous clouds of the other realm.
    vec3 q = D * 3.2;
    float swirl = fbm(q + vec3(0.0, t * 0.03, t * 0.02));
    float clouds = fbm(q * 1.7 + swirl * 1.8 - vec3(t * 0.015));
    vec3 deep = vec3(0.55, 0.62, 0.95);
    vec3 gold = vec3(1.0, 0.92, 0.70);
    vec3 white = vec3(1.0);
    vec3 col = mix(deep, gold, smoothstep(0.35, 0.8, clouds));
    col = mix(col, white, smoothstep(0.6, 0.95, clouds) * 0.8);

    // Heart of the rift and god rays radiating from it.
    float heart = pow(toward, 24.0) * 2.5 + pow(toward, 5.0) * 0.6;
    vec3 side = normalize(cross(C, vec3(0.0, 0.0, 1.0)) + 1e-4);
    float ang = atan(dot(D, side), dot(D, cross(C, side)));
    float rays = pow(0.5 + 0.5 * sin(ang * 13.0 + t * 0.2), 6.0) * pow(toward, 3.0);
    col += white * (heart + rays * 0.7);

    // Distant stars.
    vec3 sp = floor(D * 260.0);
    float star = step(0.996, hash3(sp)) * (0.6 + 0.4 * sin(t * 3.0 + hash3(sp + 3.0) * 30.0));
    col += vec3(star);

    col *= RiftCenter.w;
    fragColor = vec4(col, vertexColor.a);
}

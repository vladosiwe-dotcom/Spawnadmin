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

    // A deep twilight cosmos: indigo void, violet and gold nebulae drifting slowly.
    vec3 q = D * 3.0;
    float swirl = fbm(q + vec3(0.0, t * 0.025, t * 0.018));
    float neb = fbm(q * 1.8 + swirl * 2.2 - vec3(t * 0.012));
    float wisps = fbm(q * 5.0 - swirl * 1.5 + vec3(t * 0.02));
    vec3 voidCol = vec3(0.025, 0.03, 0.10);
    vec3 violet = vec3(0.32, 0.16, 0.55);
    vec3 teal = vec3(0.10, 0.38, 0.55);
    vec3 gold = vec3(1.0, 0.78, 0.42);
    vec3 col = voidCol;
    col = mix(col, violet, smoothstep(0.38, 0.72, neb) * 0.85);
    col = mix(col, teal, smoothstep(0.55, 0.9, swirl) * 0.45);
    col += gold * pow(smoothstep(0.55, 0.95, neb * 0.6 + wisps * 0.5), 2.0) * 0.75;

    // A distant sun of the other world at the heart of the rift, with soft rays.
    float heart = pow(toward, 60.0) * 1.6 + pow(toward, 12.0) * 0.35;
    vec3 side = normalize(cross(C, vec3(0.0, 0.0, 1.0)) + 1e-4);
    float ang = atan(dot(D, side), dot(D, cross(C, side)));
    float rays = pow(0.5 + 0.5 * sin(ang * 11.0 + t * 0.15), 8.0) * pow(toward, 6.0);
    col += vec3(1.0, 0.95, 0.85) * heart + gold * rays * 0.35;

    // Stars, twinkling.
    vec3 sp = floor(D * 320.0);
    float star = step(0.9965, hash3(sp)) * (0.55 + 0.45 * sin(t * 2.5 + hash3(sp + 3.0) * 40.0));
    col += vec3(0.9, 0.95, 1.0) * star;

    col *= RiftCenter.w;
    fragColor = vec4(col, vertexColor.a);
}

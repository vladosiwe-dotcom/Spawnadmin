#version 150

// Cinematic pass over the whole frame while a sky event is near.
//   Grade: x = darken, y = desaturate, z = time stop, w = contrast
//   Flare: xy = light source on screen (uv), z = intensity, w = aspect (width / height)
//   Ring:  x = radius (screen heights), y = intensity, z = thickness, w = spin
//   Fx:    x = ghost (prism) strength, y = rainbow light leaks, z = pull warp, w = seconds
uniform sampler2D Sampler0;
uniform vec2 ScreenSize;
uniform vec4 Grade;
uniform vec4 Flare;
uniform vec4 Ring;
uniform vec4 Fx;

in vec2 texCoord;

out vec4 fragColor;

float hash(float n) {
    return fract(sin(n * 127.1) * 43758.5453);
}

float hash2(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// Smooth 1D noise over an angle: thin, uneven light streaks.
float streaks(float a, float k, float seed) {
    float x = a * k + seed;
    float i = floor(x), f = fract(x);
    float h = mix(hash(i), hash(i + 1.0), f * f * (3.0 - 2.0 * f));
    return h;
}

// A spectrum: 0 = red .. 1 = violet, dark outside.
vec3 spectrum(float x) {
    x = clamp(x, 0.0, 1.0);
    vec3 c = vec3(
        smoothstep(0.55, 0.0, abs(x - 0.10)) + 0.35 * smoothstep(0.2, 0.0, abs(x - 0.92)),
        smoothstep(0.32, 0.0, abs(x - 0.45)),
        smoothstep(0.32, 0.0, abs(x - 0.78)));
    return c * smoothstep(0.0, 0.08, x) * smoothstep(1.0, 0.9, x);
}

void main() {
    float aspect = Flare.w;
    vec2 d = texCoord - 0.5;

    // Being dragged back: the frame is pulled towards its centre, colours tear apart.
    float pull = Fx.z;
    vec2 uv = 0.5 + d * (1.0 - pull * 0.05 * (0.4 + dot(d, d)));
    float split = 0.0015 + 0.004 * Grade.z + 0.012 * pull + 0.003 * Flare.z;
    vec3 c;
    c.r = texture(Sampler0, uv + d * split).r;
    c.g = texture(Sampler0, uv).g;
    c.b = texture(Sampler0, uv - d * split).b;

    // Grade: the world dims and greys while the sky breaks.
    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(c, vec3(lum) * vec3(0.97, 0.99, 1.03), Grade.y);
    c = (c - 0.45) * (1.0 + Grade.w) + 0.45;
    c *= 1.0 - Grade.x;
    vec3 cold = vec3(lum) * vec3(0.86, 0.95, 1.10) + vec3(0.02, 0.03, 0.05);
    c = mix(c, cold, Grade.z * 0.88);
    float r = length(d * vec2(aspect, 1.0)) / aspect * 1.4;
    float vignette = smoothstep(1.05, 0.3, r);
    c *= mix(1.0, 0.62 + 0.38 * vignette, clamp(Grade.y + Grade.z + Grade.x, 0.0, 1.0));

    // Light source: a star of rays, a soft halo and an anamorphic streak.
    vec2 fp = Flare.xy;
    vec2 q = (texCoord - fp) * vec2(aspect, 1.0);
    float dist = length(q);
    float ang = atan(q.y, q.x);
    if (Flare.z > 0.001) {
        float core = exp(-dist * dist * 2600.0) * 2.2 + exp(-dist * 26.0) * 0.4 + exp(-dist * 7.0) * 0.08;
        float rays = pow(abs(cos(ang * 6.0 + 0.3)), 90.0) * exp(-dist * 4.0) * 0.9
                   + pow(abs(cos(ang * 11.0 + 1.1)), 160.0) * exp(-dist * 7.0) * 0.5;
        float fine = pow(streaks(ang, 38.0, 3.0), 9.0) * 1.6 + pow(streaks(ang, 71.0, 11.0), 14.0) * 1.2;
        rays += fine * exp(-dist * 5.5);
        float streak = exp(-abs(q.y) * 260.0) * exp(-abs(q.x) * 2.4) * 0.8;
        // Rays and streak only open up when the light is strong: a quiet glow stays a point.
        float strong = pow(clamp(Flare.z, 0.0, 2.0), 1.7);
        vec3 flare = vec3(1.0, 0.97, 0.9) * (core * Flare.z + rays * 0.7 * strong) + vec3(0.65, 0.82, 1.0) * streak * strong;
        // A faint rainbow halo around the source, like light through thick glass.
        float hr = dist - 0.24;
        flare += spectrum(hr / 0.07 + 0.5) * exp(-hr * hr * 900.0) * 0.18 * strong;
        c += flare;

        // Prism ghosts along the line through the centre of the frame.
        vec2 axis = vec2(0.5) - fp;
        for (int i = 0; i < 6; i++) {
            float fi = float(i);
            float k = -0.35 + fi * 0.42 + 0.1 * hash(fi + 2.0);
            vec2 gp = fp + axis * 2.0 * k;
            float gr = 0.02 + 0.07 * hash(fi + 7.0);
            float gd = length((texCoord - gp) * vec2(aspect, 1.0)) / gr;
            vec3 g = spectrum(gd * 0.85 - 0.05 + 0.1 * fi) * smoothstep(1.1, 0.85, gd)
                   + vec3(0.6, 0.7, 1.0) * 0.15 * smoothstep(1.0, 0.0, gd);
            c += g * Fx.x * (0.35 + 0.5 * hash(fi + 13.0));
        }
    }

    // Shock ring: a spectral crescent with radial streaks racing out of the source.
    if (Ring.y > 0.001) {
        float rr = dist - Ring.x;
        float band = rr / Ring.z;
        float s = 0.35 + 0.65 * pow(streaks(ang, 120.0, 5.0), 2.0);
        float crescent = 0.45 + 0.55 * cos(ang - Ring.w);
        vec3 ring = spectrum(band * 0.5 + 0.5) * s * (0.4 + 0.6 * crescent);
        c += ring * Ring.y * 1.6;
    }

    // Rainbow light leaks: prism smears drifting at the edges of the frame.
    if (Fx.y > 0.001) {
        float tm = Fx.w;
        for (int i = 0; i < 5; i++) {
            float fi = float(i);
            float a0 = hash(fi * 3.1 + 1.0) * 6.2831 + tm * (0.05 + 0.04 * hash(fi));
            vec2 pc = vec2(0.5) + vec2(cos(a0) * 0.62, sin(a0) * 0.55);
            float rot = hash(fi + 9.0) * 3.1416 + tm * 0.1;
            vec2 e = (texCoord - pc) * vec2(aspect, 1.0);
            vec2 er = vec2(cos(rot) * e.x + sin(rot) * e.y, -sin(rot) * e.x + cos(rot) * e.y);
            float len = 0.22 + 0.2 * hash(fi + 4.0);
            float wid = 0.025 + 0.03 * hash(fi + 6.0);
            float shape = exp(-(er.x * er.x) / (len * len) - (er.y * er.y) / (wid * wid));
            vec3 col = spectrum(er.y / wid * 0.35 + 0.5);
            float flick = 0.6 + 0.4 * sin(tm * (1.3 + fi) + fi * 2.0);
            c += col * shape * Fx.y * 0.7 * flick;
        }
    }

    // Film grain while the scene is graded.
    float g = hash2(floor(texCoord * ScreenSize) + vec2(floor(Fx.w * 24.0) * 17.0, 0.0)) - 0.5;
    c += g * 0.05 * clamp(Grade.y + Grade.z, 0.0, 1.0);

    fragColor = vec4(c, 1.0);
}

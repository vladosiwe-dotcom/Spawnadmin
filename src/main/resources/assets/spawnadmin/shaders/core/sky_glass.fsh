#version 150

// Glass shard of the sky dome.
// texCoord.x: 0 at the shard centre, 1 on its rim (edge proximity)
// texCoord.y: edge glow strength (broken free / docking flash)
// GlassParams: x = shell glow, y = refraction strength, z = sky pulse, w = time stop

uniform sampler2D Sampler0;   // copy of the frame behind the glass
uniform vec2 ScreenSize;
uniform float GameTime;
uniform vec4 GlassParams;

in vec4 vertexColor;
in vec2 texCoord;
in vec3 worldNormal;
in vec3 viewNormal;
in vec3 worldPos;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec3 V = normalize(-worldPos);
    vec3 N = normalize(worldNormal);
    vec3 VN = normalize(viewNormal);
    if (dot(N, V) < 0.0) {
        N = -N;
        VN = -VN;
    }
    float ndv = clamp(dot(N, V), 0.0, 1.0);
    float fresnel = 0.06 + 0.94 * pow(1.0 - ndv, 4.0);

    // Refraction with slight dispersion: each channel bends by a different amount.
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    float edge = texCoord.x;
    vec2 bend = VN.xy * GlassParams.y * (0.6 + 1.4 * edge * edge);
    vec3 refr;
    refr.r = texture(Sampler0, uv + bend * 1.06).r;
    refr.g = texture(Sampler0, uv + bend).g;
    refr.b = texture(Sampler0, uv + bend * 0.94).b;

    // Fake environment reflection: sky gradient + a sun glint.
    vec3 R = reflect(-V, N);
    vec3 horizon = vec3(0.80, 0.88, 1.00);
    vec3 zenith = vec3(0.38, 0.58, 0.96);
    vec3 env = mix(horizon, zenith, clamp(R.y * 0.5 + 0.5, 0.0, 1.0));
    vec3 sun = normalize(vec3(0.35, 0.85, 0.25));
    float glint = pow(max(dot(R, sun), 0.0), 180.0) * 3.0 + pow(max(dot(R, sun), 0.0), 18.0) * 0.25;

    float t = GameTime * 1200.0;
    float shimmer = 0.5 + 0.5 * sin(dot(worldPos, vec3(0.07, 0.05, 0.09)) + t * 0.8);
    float sparkle = step(0.9975, hash(floor(gl_FragCoord.xy * 0.5) + floor(t * 6.0))) * GlassParams.x;

    vec3 tint = vertexColor.rgb;
    vec3 color = refr * mix(vec3(1.0), tint, 0.55);
    color = mix(color, env, fresnel * 0.75);
    color += vec3(glint);

    // The sky itself glows: a luminous haze across the whole shell, pulsing as it breaks.
    vec3 glowColor = vec3(0.86, 0.94, 1.0);
    color += glowColor * GlassParams.x * (0.10 + 0.10 * shimmer) + glowColor * GlassParams.z * 0.35;

    // Bright rims on free shards and on docking.
    float rim = smoothstep(0.72, 1.0, edge);
    color += vec3(0.75, 0.92, 1.0) * rim * texCoord.y * 1.8;
    color += vec3(sparkle);

    // Frozen time: colder, flatter glass.
    float lum = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(color, vec3(lum) * vec3(0.88, 0.96, 1.08), GlassParams.w * 0.6);

    float alpha = vertexColor.a * clamp(0.55 + fresnel * 0.6 + rim * texCoord.y * 0.5, 0.0, 1.0);
    if (alpha < 0.003) {
        discard;
    }
    fragColor = vec4(color, alpha);
}

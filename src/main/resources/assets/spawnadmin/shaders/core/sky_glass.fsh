#version 150

// A shard of the sky itself. Each shard carries the piece of the Minecraft sky (gradient and
// clouds) that was behind it, so the intact dome melts into the real sky and the falling glass
// is visibly the sky breaking. On top: glass refraction, fresnel reflection, a green-cyan
// glass edge and bright rims.
// GlassParams: x = shell glow, y = refraction, z = sky pulse, w = time stop

uniform sampler2D Sampler0;   // copy of the frame (refraction)
uniform sampler2D Sampler1;   // minecraft:textures/environment/clouds.png
uniform vec2 ScreenSize;
uniform float GameTime;
uniform vec4 GlassParams;
uniform vec4 SkyColor;
uniform vec4 HorizonColor;
uniform vec4 CloudColor;

in vec4 vertexColor;
in vec2 cloudUv;
in float cloudVis;
in float variation;
in vec2 localPos;
in vec3 worldNormal;
in vec3 viewNormal;
in vec3 worldPos;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float edge = vertexColor.r;
    float glow = vertexColor.g;
    float skyT = vertexColor.b;
    float alpha = vertexColor.a;

    vec3 V = normalize(-worldPos);
    vec3 N = normalize(worldNormal);
    vec3 VN = normalize(viewNormal);
    if (dot(N, V) < 0.0) {
        N = -N;
        VN = -VN;
    }
    float ndv = clamp(dot(N, V), 0.0, 1.0);
    float fresnel = 0.04 + 0.96 * pow(1.0 - ndv, 5.0);

    // The piece of sky this shard was cut from.
    vec3 sky = mix(HorizonColor.rgb, SkyColor.rgb, skyT);
    vec4 cloud = texture(Sampler1, fract(cloudUv));
    sky = mix(sky, CloudColor.rgb, cloud.a * cloudVis * CloudColor.a);

    // What lies behind the glass, bent by it.
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec2 bend = VN.xy * GlassParams.y * (0.25 + 1.2 * edge * edge);
    vec3 refr = vec3(texture(Sampler0, uv + bend * 1.05).r, texture(Sampler0, uv + bend).g, texture(Sampler0, uv + bend * 0.95).b);

    // Reflection of the sky and a sun glint.
    vec3 R = reflect(-V, N);
    vec3 env = mix(HorizonColor.rgb, SkyColor.rgb, clamp(R.y * 0.6 + 0.4, 0.0, 1.0)) * 1.15;
    vec3 sun = normalize(vec3(0.3, 0.88, 0.25));
    float glint = pow(max(dot(R, sun), 0.0), 220.0) * 4.0 + pow(max(dot(R, sun), 0.0), 24.0) * 0.18;

    // Every shard is its own piece of glass: its tint, its scratches, the ripple marks of the
    // fracture along its edges, a few bubbles. Shown on broken pieces; the sealed sky stays clean.
    float own = clamp(glow * 1.2, 0.0, 1.0);
    vec3 tint = mix(mix(vec3(0.88, 1.0, 0.93), vec3(0.88, 0.95, 1.05), smoothstep(0.2, 0.6, variation)),
                    vec3(0.97, 0.98, 1.0), smoothstep(0.6, 0.95, variation));
    float ang = variation * 6.2831;
    vec2 sdir = vec2(cos(ang), sin(ang));
    float along = dot(localPos, sdir);
    float across = dot(localPos, vec2(-sdir.y, sdir.x));
    float scratch = smoothstep(0.965, 1.0, sin(across * 7.0 + 2.5 * sin(along * 0.35 + variation * 31.0)))
                  * step(0.35, fract(variation * 13.0)) * smoothstep(-6.0, 6.0, along + 8.0 * variation);
    float r = length(localPos);
    float ripple = (0.5 + 0.5 * sin(r * 11.0 + variation * 40.0)) * smoothstep(0.78, 0.98, edge);
    float hackle = pow(0.5 + 0.5 * sin(atan(localPos.y, localPos.x) * 70.0 + variation * 90.0), 6.0) * smoothstep(0.88, 1.0, edge);
    vec2 cell = floor(localPos * 5.0);
    float bubble = step(0.996, hash(cell + variation * 17.0)) * smoothstep(0.25, 0.0, length(fract(localPos * 5.0) - 0.5));

    vec3 color = mix(sky, refr, 0.12 + 0.25 * glow * edge);
    color *= mix(vec3(1.0), tint, own);
    color = mix(color, env, fresnel * (0.25 + 0.5 * glow));
    color += vec3(glint) * (0.3 + glow);
    // surface detail catches the light (strongest at grazing angles, where real scratches shine)
    float catchLight = 0.25 + 0.75 * fresnel + glint;
    color += vec3(0.9, 0.97, 1.0) * own * (scratch * 0.35 * catchLight + hackle * 0.25 + ripple * 0.06 + bubble * 0.5);

    // Glass edge: a thin dark-green band and a bright cut line, like a real broken pane.
    float band = smoothstep(0.80, 0.93, edge) * (1.0 - smoothstep(0.96, 1.0, edge));
    float cut = smoothstep(0.95, 1.0, edge);
    vec3 glassEdge = vec3(0.55, 0.80, 0.74);
    color = mix(color, color * glassEdge, band * (0.35 + 0.4 * glow));
    color += vec3(0.80, 0.97, 1.0) * cut * (0.15 + 1.1 * glow);

    // The sky lights up before it breaks; a white bloom pulses through the shell.
    float t = GameTime * 1200.0;
    float shimmer = 0.5 + 0.5 * sin(dot(worldPos, vec3(0.05, 0.04, 0.07)) + t * 0.7 + variation * 6.0);
    color += vec3(0.92, 0.97, 1.0) * (GlassParams.x * (0.04 + 0.05 * shimmer) * (1.0 - glow) + GlassParams.z * 0.2);
    color += vec3(step(0.9982, hash(floor(gl_FragCoord.xy * 0.5) + floor(t * 5.0))) * glow);

    // Frozen time: colder, flatter.
    float lum = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(color, vec3(lum) * vec3(0.88, 0.96, 1.08), GlassParams.w * 0.6);

    float a = alpha * clamp(0.85 + fresnel * 0.3 + cut * glow, 0.0, 1.0);
    if (a < 0.003) {
        discard;
    }
    fragColor = vec4(color, a);
}

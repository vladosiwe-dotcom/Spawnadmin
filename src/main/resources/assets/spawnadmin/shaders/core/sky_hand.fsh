#version 150

// Energy hand: additive white light with a fresnel rim and flowing streaks.
// texCoord.x: coordinate along the arm/finger (for the flow), texCoord.y: local fade.
// HandParams.x: overall intensity, y: pass (1 body, 0 glow), z: fill (0 silhouette .. 1 solid).
uniform float GameTime;
uniform vec4 HandParams;

in vec4 vertexColor;
in vec2 texCoord;
in vec3 worldNormal;
in vec3 viewNormal;
in vec3 worldPos;

out vec4 fragColor;

float hash(float n) {
    return fract(sin(n) * 43758.5453);
}

float noise(float x) {
    float i = floor(x), f = fract(x);
    return mix(hash(i), hash(i + 1.0), f * f * (3.0 - 2.0 * f));
}

void main() {
    vec3 V = normalize(-worldPos);
    vec3 N = normalize(worldNormal);
    float ndv = abs(dot(N, V));
    float rim = pow(1.0 - ndv, 2.2);
    float t = GameTime * 1200.0;
    float flow = noise(texCoord.x * 2.5 - t * 3.0 + worldPos.y * 0.1);
    float streak = pow(noise(texCoord.x * 7.0 - t * 5.0 + dot(N, vec3(3.0, 1.0, 2.0))), 3.0);
    float fade = vertexColor.a * texCoord.y * HandParams.x;
    // Seen from inside the grip the hand turns to translucent light instead of a white wall.
    float dist = length(worldPos);
    float near = smoothstep(2.5, 11.0, dist);
    if (HandParams.y > 0.5) {
        // Body pass: a pale solid core with a cyan rim, so the hand reads even against a bright sky.
        vec3 core = mix(vec3(0.97, 0.99, 1.0), vec3(0.45, 0.78, 1.0), smoothstep(0.15, 0.9, rim));
        float a = clamp(0.55 + rim * 0.4, 0.0, 0.95) * fade * near * HandParams.z;
        fragColor = vec4(core * (0.92 + flow * 0.08), a);
        return;
    }
    // Silhouette first (rim only), then light flows through the whole hand as it fills.
    float fill = HandParams.z;
    float body = (0.10 + flow * 0.14 + streak * 0.3) * fill + rim * (1.1 + 0.6 * (1.0 - fill));
    vec3 c = vertexColor.rgb * body;
    c += vec3(0.55, 0.82, 1.0) * rim * rim * 0.7;
    fragColor = vec4(c * fade * mix(0.35, 1.0, near), 1.0);
}

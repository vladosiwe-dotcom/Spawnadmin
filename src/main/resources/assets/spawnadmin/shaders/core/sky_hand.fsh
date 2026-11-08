#version 150

// Energy hand: additive white light with a fresnel rim and flowing streaks.
// texCoord.x: coordinate along the arm/finger (for the flow), texCoord.y: local fade.
// HandParams.x: overall intensity.
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
    float body = 0.18 + rim * 1.25 + flow * 0.18 + streak * 0.35;
    vec3 c = vertexColor.rgb * body;
    c += vec3(0.65, 0.85, 1.0) * rim * rim * 0.6;
    fragColor = vec4(c * vertexColor.a * texCoord.y * HandParams.x, 1.0);
}

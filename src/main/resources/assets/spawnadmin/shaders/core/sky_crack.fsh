#version 150

// Crack line drawn additively. texCoord.x runs -1..1 across the line width,
// texCoord.y is the crack's intensity. CrackParams.x: global brightness.
uniform float GameTime;
uniform vec4 CrackParams;

in vec4 vertexColor;
in vec2 texCoord;
in vec3 worldNormal;
in vec3 viewNormal;
in vec3 worldPos;

out vec4 fragColor;

void main() {
    float x = texCoord.x;
    float core = exp(-x * x * 40.0);
    float halo = exp(-x * x * 4.0) * 0.35;
    float flicker = 0.85 + 0.15 * sin(GameTime * 1200.0 * 9.0 + worldPos.x * 0.3 + worldPos.z * 0.2);
    vec3 c = vertexColor.rgb * (core * 1.7 + halo) * texCoord.y * CrackParams.x * flicker;
    c += vec3(core * core) * texCoord.y * 0.6 * CrackParams.x;
    fragColor = vec4(c * vertexColor.a, 1.0);
}

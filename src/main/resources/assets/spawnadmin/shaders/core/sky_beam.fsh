#version 150

// Volumetric light of the rift. BeamParams.x: intensity, BeamParams.y: 0 = shaft, 1 = glow sprite.
// Shaft: texCoord.x across (-1..1), texCoord.y along (0 at the rift .. 1 far end).
// Sprite: texCoord in [-1, 1]^2.
uniform float GameTime;
uniform vec4 BeamParams;

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
    float a;
    if (BeamParams.y > 0.5) {
        float r = length(texCoord);
        a = exp(-r * r * 4.0) * 0.9 + exp(-r * r * 18.0) * 0.8;
        a *= smoothstep(1.0, 0.85, r);
    } else {
        float x = abs(texCoord.x);
        float y = clamp(texCoord.y, 0.0, 1.0);
        float t = GameTime * 1200.0;
        float dust = 0.75 + 0.25 * noise(y * 18.0 - t * 0.6 + texCoord.x * 3.0);
        a = pow(1.0 - x, 2.2) * pow(1.0 - y, 1.3) * smoothstep(0.0, 0.04, y) * dust * 0.32;
    }
    vec3 c = vertexColor.rgb * a * vertexColor.a * BeamParams.x;
    fragColor = vec4(c, 1.0);
}

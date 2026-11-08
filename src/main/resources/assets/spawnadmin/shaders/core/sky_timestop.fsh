#version 150

// Time stop: the world drains of colour, turns cold and still, with a faint frozen ripple.
// StopParams.x: strength.
uniform sampler2D Sampler0;
uniform vec2 ScreenSize;
uniform float GameTime;
uniform vec4 StopParams;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float s = StopParams.x;
    vec2 uv = texCoord;
    vec2 d = uv - 0.5;
    float r = length(d);
    // Slight chromatic split at the edges, like light caught mid-flight.
    vec2 off = d * 0.006 * s;
    vec3 c;
    c.r = texture(Sampler0, uv + off).r;
    c.g = texture(Sampler0, uv).g;
    c.b = texture(Sampler0, uv - off).b;
    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    vec3 cold = vec3(lum) * vec3(0.86, 0.95, 1.10) + vec3(0.02, 0.03, 0.05);
    c = mix(c, cold, s * 0.88);
    float vignette = smoothstep(0.85, 0.25, r);
    c *= mix(1.0, 0.78 + 0.22 * vignette, s);
    fragColor = vec4(c, 1.0);
}

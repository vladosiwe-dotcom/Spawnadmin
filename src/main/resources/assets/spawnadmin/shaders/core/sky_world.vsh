#version 150

// Shared vertex stage for the sky event geometry (POSITION_TEX_COLOR_NORMAL).
// Positions are camera-relative in world axes, so the world-space view ray is just Position.
in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec2 texCoord;
out vec3 worldNormal;
out vec3 viewNormal;
out vec3 worldPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    texCoord = UV0;
    worldNormal = Normal;
    viewNormal = mat3(ModelViewMat) * Normal;
    worldPos = Position;
}

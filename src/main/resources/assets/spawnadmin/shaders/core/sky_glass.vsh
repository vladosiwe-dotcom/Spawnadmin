#version 150

// Glass shard vertex (NEW_ENTITY layout, repurposed):
//   Color  r = edge proximity, g = edge glow, b = sky gradient (0 horizon .. 1 zenith), a = alpha
//   UV0    = cloud texture coordinate of this point of the sky, at its rest position
//   UV1    x = cloud visibility * 1000, y = per-shard variation * 1000
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec2 cloudUv;
out float cloudVis;
out float variation;
out vec3 worldNormal;
out vec3 viewNormal;
out vec3 worldPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    cloudUv = UV0;
    cloudVis = float(UV1.x) / 1000.0;
    variation = float(UV1.y) / 1000.0;
    worldNormal = Normal;
    viewNormal = mat3(ModelViewMat) * Normal;
    worldPos = Position;
}

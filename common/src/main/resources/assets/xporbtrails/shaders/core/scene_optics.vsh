#version 330

#moj_import <minecraft:projection.glsl>
#moj_import <xporbtrails:scene_optics.glsl>

out vec2 texCoord;
flat out mat4 clipToWorld;
flat out float farDepth;

void main() {
    mat4 worldToClip = ProjMat * ViewMatrix;
    clipToWorld = inverse(worldToClip);
    vec4 a = ProjMat * vec4(0.0, 0.0, -1.0, 1.0);
    vec4 b = ProjMat * vec4(0.0, 0.0, -100.0, 1.0);
    farDepth = a.z / a.w > b.z / b.w ? 0.0 : 1.0;

    // Rasterize the union's enclosing rectangle. Project all eight corners since
    // the world projection includes view bob, hurt tilt and portal transforms.
    vec2 low = vec2(1.0);
    vec2 high = vec2(-1.0);
    for (int i = 0; i < int(Parameters.x); i++) {
        vec2 sourceLow = vec2(1.0);
        vec2 sourceHigh = vec2(-1.0);
        int front = 0;
        for (int j = 0; j < 8; j++) {
            vec3 side = 2.0 * vec3(j & 1, (j >> 1) & 1, (j >> 2) & 1) - 1.0;
            vec4 p = worldToClip * vec4(Sources[i].xyz + side * Sources[i].w, 1.0);
            if (p.w > 0.001) {
                front++;
                sourceLow = min(sourceLow, p.xy / p.w);
                sourceHigh = max(sourceHigh, p.xy / p.w);
            }
        }
        if (front == 0) continue;
        if (front != 8) {
            low = vec2(-1.0);
            high = vec2(1.0);
            break;
        }
        low = min(low, sourceLow);
        high = max(high, sourceHigh);
    }
    vec2 corner = vec2(gl_VertexID & 1, (gl_VertexID >> 1) & 1);
    vec2 ndc = mix(clamp(low, -1.0, 1.0), clamp(max(high, low), -1.0, 1.0), corner);
    texCoord = ndc * 0.5 + 0.5;
    gl_Position = vec4(ndc, 0.0, 1.0);
}

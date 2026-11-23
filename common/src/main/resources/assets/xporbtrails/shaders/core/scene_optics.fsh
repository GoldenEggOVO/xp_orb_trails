#version 330
#extension GL_ARB_separate_shader_objects : require

#include <xporbtrails:scene_optics.glsl>

uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;

layout(location = 0) in vec2 texCoord;
layout(location = 1) flat in mat4 clipToWorld;
layout(location = 5) flat in float farDepth;
layout(location = 0) out vec4 fragColor;

vec3 worldAt(vec2 uv, float depth) {
    float z = LightColorAndDepthRange.w > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 position = clipToWorld * vec4(uv * 2.0 - 1.0, z, 1.0);
    return position.xyz / position.w;
}

vec3 surfaceStep(vec2 center, vec2 delta, vec3 p) {
    vec2 uv0 = center - delta;
    vec2 uv1 = center + delta;
    float z0 = texture(SceneDepth, uv0).r;
    float z1 = texture(SceneDepth, uv1).r;
    vec3 a = z0 == farDepth ? vec3(1e6) : p - worldAt(uv0, z0);
    vec3 b = z1 == farDepth ? vec3(1e6) : worldAt(uv1, z1) - p;
    return dot(a, a) < dot(b, b) ? a : b;
}

void main() {
    vec2 pixel = 1.0 / vec2(textureSize(SceneDepth, 0));
    float depth = texture(SceneDepth, texCoord).r;
    vec3 nearPoint = worldAt(texCoord, 1.0 - farDepth);
    vec3 ray = normalize(worldAt(texCoord, 0.5) - nearPoint);
    if (Parameters.y < 0.5) {
        if (depth == farDepth) discard;
        vec3 surface = worldAt(texCoord, depth);
        vec3 dx = surfaceStep(texCoord, vec2(pixel.x, 0.0), surface);
        vec3 dy = surfaceStep(texCoord, vec2(0.0, pixel.y), surface);
        vec3 normal = cross(dx, dy);
        float normalLength = length(normal);
        if (normalLength <= 1e-9) discard;
        normal /= normalLength;
        if (dot(normal, ray) > 0.0) normal = -normal;
        float energy = 0.0;
        for (int i = 0; i < int(Parameters.x); i++) {
            vec3 toLight = Sources[i].xyz - surface;
            float distanceToLight = length(toLight);
            float falloff = max(0.0, 1.0 - distanceToLight / Sources[i].w);
            float diffuse = max(0.0, dot(normal, toLight) / max(distanceToLight, 0.001));
            energy += diffuse * falloff * falloff;
        }
        if (energy <= 0.0001) discard;
        vec4 scene = texture(SceneColor, texCoord);
        vec3 response = sqrt(max(scene.rgb, vec3(0.0))) * (1.0 - scene.rgb);
        fragColor = vec4(scene.rgb + response * LightColorAndDepthRange.rgb
                * (1.0 - exp(-energy * Parameters.z)), scene.a);
        return;
    }

    float surfaceDistance = depth == farDepth ? 1e6 : length(worldAt(texCoord, depth) - nearPoint);
    vec2 offset = vec2(0.0);
    float firstEntry = 1e6;
    for (int i = 0; i < int(Parameters.x); i++) {
        vec3 toCenter = Sources[i].xyz - nearPoint;
        float along = dot(toCenter, ray);
        vec3 local = along * ray - toCenter;
        float radius = Sources[i].w;
        float chordSq = radius * radius - dot(local, local);
        if (chordSq <= 0.0) continue;
        float halfChord = sqrt(chordSq);
        float entry = max(0.0, along - halfChord);
        float exitDistance = min(surfaceDistance, along + halfChord);
        if (exitDistance <= entry) continue;
        firstEntry = min(firstEntry, entry);
        float density = (exitDistance - entry) / (2.0 * radius);
        float wave = sin(local.y * 13.0 / radius - 3.0 * Parameters.w + 2.0 * local.z);
        float ripple = cos(local.x * 8.0 / radius + 2.0 * Parameters.w + 4.0 * local.y);
        offset += vec2(wave + ripple * 0.28, ripple * 0.52) * density * density;
    }
    if (firstEntry == 1e6) discard;
    vec2 refracted = clamp(texCoord + clamp(offset, vec2(-1.5), vec2(1.5)) * Parameters.z * pixel,
            pixel * 0.5, vec2(1.0) - pixel * 0.5);
    float sampleDepth = texture(SceneDepth, refracted).r;
    if (sampleDepth != farDepth && length(worldAt(refracted, sampleDepth) - nearPoint) < firstEntry) discard;
    fragColor = texture(SceneColor, refracted);
}

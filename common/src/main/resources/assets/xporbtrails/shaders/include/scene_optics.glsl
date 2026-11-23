layout(std140) uniform Optics {
    mat4 ViewMatrix;
    vec4 Sources[8];             // Camera-relative xyz and world radius.
    vec4 Parameters;             // Count, heat mode, strength, wave phase.
    vec4 LightColorAndDepthRange; // RGB and device zero-to-one clip convention.
};

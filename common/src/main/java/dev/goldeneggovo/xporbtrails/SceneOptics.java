package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.goldeneggovo.xporbtrails.mixin.RenderPipelinesAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Depth-reconstructed point lighting and scene-color refraction, before the HUD. */
final class SceneOptics {
    static final int MAX_SOURCES = 8;
    // std140: model-view, Source[8], Parameters, LightColorAndDepthRange.
    private static final int UNIFORM_BYTES = 64 + (MAX_SOURCES + 2) * 16;
    private static volatile Frame frame;
    private static TextureTarget sceneCopy;
    private static final class Pipeline {
        static final RenderPipeline OPTICS = RenderPipelinesAccessor.xporbtrails$register(RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("xporbtrails", "pipeline/scene_optics"))
                .withVertexShader(Identifier.fromNamespaceAndPath("xporbtrails", "core/scene_optics"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("xporbtrails", "core/scene_optics"))
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("Optics", UniformType.UNIFORM_BUFFER)
                        .withSampler("SceneColor")
                        .withSampler("SceneDepth").build())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withCull(false).build());
    }

    record Settings(boolean heat, float radius, float strength, float speed, int color, int maxOrbs) {
        static Settings read(JsonObject advanced) {
            if (advanced == null || !(advanced.get("optics") instanceof JsonObject json)) return null;
            var options = new OrbEffectOptions(json);
            boolean heat = "heat".equals(options.type);
            if (!heat && !"light".equals(options.type)) return null;
            return new Settings(heat, (float) options.number("radius", heat ? 0.75 : 3.25, 0.1, 8),
                    (float) options.number("strength", heat ? 3.0 : 1.4, 0, heat ? 12 : 4),
                    (float) options.number("speed", 1.0, 0, 5), options.color("color", 0xffffcb72),
                    options.count("maxOrbs", 6, 1, MAX_SOURCES));
        }
    }

    record Source(Vec3 position, double distanceSq) {}
    private record Frame(Settings settings, List<Source> sources, float phase) {}

    private SceneOptics() {}

    static void extract(ClientLevel level, Vec3 camera, float partialTick, Iterable<Integer> trackedIds) {
        var config = XpOrbTrailsClient.CONFIG;
        Settings settings = level == null || !config.enabled ? null : Settings.read(config.advanced);
        if (settings == null || settings.strength == 0) {
            frame = null;
            return;
        }
        double rangeSq = config.renderRange * config.renderRange;
        var nearby = new ArrayList<Source>(settings.maxOrbs);
        for (int id : trackedIds) {
            if (!(level.getEntity(id) instanceof ExperienceOrb orb) || !orb.isAlive()) continue;
            Vec3 point = orb.getPosition(partialTick).add(0, config.orbYOffset, 0);
            double distanceSq = point.distanceToSqr(camera);
            if (distanceSq <= rangeSq) addNearest(nearby, point, distanceSq, settings.maxOrbs);
        }
        // Both wave terms repeat at an integer number of turns.
        float phase = (float) (((TrailRenderer.effectTick() + (double) partialTick) * settings.speed / 50.0
                % (Math.PI * 2)));
        frame = new Frame(settings, List.copyOf(nearby), phase);
    }

    static void addNearest(List<Source> sources, Vec3 point, double distanceSq, int limit) {
        int index = sources.size();
        while (index > 0 && distanceSq < sources.get(index - 1).distanceSq) index--;
        if (index >= limit) return;
        if (sources.size() == limit) sources.removeLast();
        sources.add(index, new Source(point, distanceSq));
    }

    static void render(Vec3 camera) {
        Frame current = frame;
        if (current == null) {
            releaseCopy();
            return;
        }
        if (current.sources.isEmpty()) return;
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTexture color = target.getColorTexture();
        GpuTexture depth = target.getDepthTexture();
        if (color == null || depth == null || target.getColorTextureView() == null
                || target.getDepthTextureView() == null || color.getFormat() != GpuFormat.RGBA8_UNORM
                || (color.usage() & GpuTexture.USAGE_COPY_SRC) == 0
                || (depth.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0
                || color.getWidth(0) != depth.getWidth(0) || color.getHeight(0) != depth.getHeight(0)) return;
        var pipeline = Pipeline.OPTICS;
        if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) return;
        if (sceneCopy == null || sceneCopy.width != target.width || sceneCopy.height != target.height) {
            releaseCopy();
            sceneCopy = new TextureTarget("XP Orb Trails scene color", target.width, target.height, false, color.getFormat());
        }
        var device = RenderSystem.getDevice();
        var encoder = device.createCommandEncoder();
        encoder.copyTextureToTexture(color, sceneCopy.getColorTexture(), 0, 0, 0, 0, 0, target.width, target.height);
        var samplers = RenderSystem.getSamplerCache();
        try (var stack = MemoryStack.stackPush()) {
            var data = Std140Builder.onStack(stack, UNIFORM_BYTES).putMat4f(RenderSystem.getModelViewMatrixCopy());
            for (var source : current.sources) {
                Vec3 point = source.position;
                data.putVec4((float) (point.x - camera.x), (float) (point.y - camera.y),
                        (float) (point.z - camera.z), current.settings.radius);
            }
            for (int i = current.sources.size(); i < MAX_SOURCES; i++) data.putVec4(0, 0, 0, 0);
            Settings settings = current.settings;
            data.putVec4(current.sources.size(), settings.heat ? 1 : 0, settings.strength, current.phase);
            data.putVec4(((settings.color >> 16) & 255) / 255f, ((settings.color >> 8) & 255) / 255f,
                    (settings.color & 255) / 255f, device.getDeviceInfo().isZZeroToOne() ? 1 : 0);
            var uniform = encoder.transientMemory().uploadGpu(data.get(),
                    device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
            // Sample the original depth without attaching it for writes. Color always comes from a separate texture.
            try (var pass = encoder.createRenderPass(() -> "XP Orb Trails scene optics",
                    target.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("Optics", uniform);
                pass.bindTexture("SceneColor", sceneCopy.getColorTextureView(), samplers.getClampToEdge(FilterMode.LINEAR));
                pass.bindTexture("SceneDepth", target.getDepthTextureView(), samplers.getClampToEdge(FilterMode.NEAREST));
                pass.draw(4, 1, 0, 0);
            }
        }
    }

    private static void releaseCopy() {
        if (sceneCopy != null) {
            sceneCopy.destroyBuffers();
            sceneCopy = null;
        }
    }

    static void close() {
        releaseCopy();
        frame = null;
    }
}

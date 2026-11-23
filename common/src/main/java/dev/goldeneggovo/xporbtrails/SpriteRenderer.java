package dev.goldeneggovo.xporbtrails;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.goldeneggovo.xporbtrails.mixin.RenderPipelinesAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

final class SpriteRenderer {
    private static final Map<SpriteSheet, Atlas> ATLASES = new IdentityHashMap<>();
    private static final List<Atlas> batches = new ArrayList<>();
    private static final Identifier ORB_TEXTURE = Identifier.withDefaultNamespace("textures/entity/experience/experience_orb.png");
    private static final SpriteSheet.Frame[] ORB_ICONS = new SpriteSheet.Frame[16];
    private static Atlas orbAtlas;
    private static volatile Minecraft textureOwner;
    private static StagedVertexBuffer buffer;
    private static int textureSerial;
    private static boolean additive;

    private SpriteRenderer() { }

    static void begin(boolean additiveGlow) {
        additive = additiveGlow;
    }

    static void world(SpriteSheet sheet, SpriteSheet.Frame frame, Vec3 center, Vec3 camera,
                      double size, double opacity) {
        int alpha = alpha(opacity);
        worldTinted(sheet, frame, center, camera, size, alpha << 24 | 0xFFFFFF, 0);
    }

    static void worldTinted(SpriteSheet sheet, SpriteSheet.Frame frame, Vec3 center, Vec3 camera,
                            double size, int color, double rotation) {
        if (color >>> 24 == 0 || size <= 0) return;
        Atlas atlas = batch(sheet);
        if (atlas == null) return;
        atlas.quads.add(new Quad(frame, center.subtract(camera), scale(frame, size), color, rotation));
    }

    static void worldOrb(int icon, int rgb, Vec3 origin, Vec3 camera, double size, double opacity) {
        int alpha = alpha(opacity);
        if (alpha == 0 || size <= 0 || icon < 0 || icon > 15) return;
        if (orbAtlas == null || orbAtlas.quads.isEmpty()) {
            textureOwner = Minecraft.getInstance();
            // The texture manager owns the active pack's atlas, including its reload and release.
            var texture = textureOwner.getTextureManager().getTexture(ORB_TEXTURE);
            if (orbAtlas == null || orbAtlas.texture != texture) orbAtlas = new Atlas(ORB_TEXTURE, texture, 64, 64);
            batches.add(orbAtlas);
        }
        var frame = ORB_ICONS[icon];
        if (frame == null) {
            frame = new SpriteSheet.Frame(icon % 4 * 16, icon / 4 * 16, 16, 16, 16, 16, 0, 0, 8, 12);
            ORB_ICONS[icon] = frame;
        }
        orbAtlas.quads.add(new Quad(frame, origin.subtract(camera), size / 16, alpha << 24 | rgb, 0));
    }

    static void worldRibbon(SpriteSheet sheet, SpriteSheet.Frame frame, List<TrailRenderer.Sample> samples,
                            Vec3 camera, long fadeNow, TrailConfig cfg, double fade, double reflow, boolean pulse) {
        Atlas atlas = batch(sheet);
        if (atlas == null) return;
        var map = new SpriteRibbon.FrameMap(sheet, frame);
        atlas.ribbons.add(out -> SpriteRibbon.emit(map, samples, camera, 1, fadeNow, cfg, fade, reflow, pulse, (a, b) -> {
            ribbonVertex(out, a.top(), a.u(), map.v0(), a.color());
            ribbonVertex(out, a.bottom(), a.u(), map.v1(), a.color());
            ribbonVertex(out, b.bottom(), b.u(), map.v1(), b.color());
            ribbonVertex(out, b.top(), b.u(), map.v0(), b.color());
        }));
    }

    private static Atlas batch(SpriteSheet sheet) {
        Atlas atlas = atlas(sheet);
        if (atlas != null && atlas.quads.isEmpty() && atlas.ribbons.isEmpty()) batches.add(atlas);
        return atlas;
    }

    static void decal(SpriteSheet sheet, SpriteSheet.Frame frame, Vec3 center, Vec3 right, Vec3 up, int color) {
        var atlas = batch(sheet);
        if (atlas == null) return;
        float left = (float)frame.x()/sheet.width(), top = (float)frame.y()/sheet.height();
        float end = (float)(frame.x()+frame.width())/sheet.width(), bottom = (float)(frame.y()+frame.height())/sheet.height();
        atlas.ribbons.add(out -> {
            ribbonVertex(out, center.subtract(right).add(up), left, top, color);
            ribbonVertex(out, center.subtract(right).subtract(up), left, bottom, color);
            ribbonVertex(out, center.add(right).subtract(up), end, bottom, color);
            ribbonVertex(out, center.add(right).add(up), end, top, color);
        });
    }

    private static void ribbonVertex(VertexConsumer out, Vec3 p, float u, float v, int color) {
        out.addVertex((float) p.x, (float) p.y, (float) p.z).setUv(u, v).setColor(color);
    }

    static void end() {
        if (batches.isEmpty()) return;
        if (buffer == null) buffer = new StagedVertexBuffer(() -> "XP Orb Sprite Buffer", RenderType.SMALL_BUFFER_SIZE);
        try {
            var target = textureOwner.gameRenderer.mainRenderTarget();
            var colorTarget = target.getColorTextureView();
            if (colorTarget == null) return;
            Matrix4f view = RenderSystem.getModelViewMatrixCopy();
            for (Atlas atlas : batches) {
                atlas.draw = buffer.appendDraw(DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS,
                        additive ? null : VertexSorting.DISTANCE_TO_ORIGIN);
                VertexConsumer out = buffer.getVertexBuilder(atlas.draw);
                for (var ribbon : atlas.ribbons) ribbon.accept(out);
                for (Quad quad : atlas.quads) emit(out, atlas, quad, view);
            }
            buffer.upload();
            var transforms = RenderSystem.getDynamicUniforms().writeTransform(
                    view, Pipelines.WHITE, Pipelines.ZERO, Pipelines.IDENTITY);
            try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "XP Orb Sprites", colorTarget, Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(additive ? Pipelines.ADDITIVE : Pipelines.ALPHA));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                for (Atlas atlas : batches) {
                    var info = buffer.getExecuteInfo(atlas.draw);
                    if (info == null) continue;
                    pass.setUniform("Sampler0", atlas.texture.getTextureView(), atlas.texture.getSampler());
                    pass.setVertexBuffer(0, info.vertexBuffer().slice());
                    pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                    pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
                }
            }
        } finally {
            buffer.endFrame();
            for (Atlas atlas : batches) {
                atlas.ribbons.clear();
                atlas.quads.clear();
                atlas.draw = null;
            }
            batches.clear();
        }
    }

    private static void emit(VertexConsumer out, Atlas atlas, Quad quad, Matrix4f view) {
        var f = quad.frame;
        double left = (f.offsetX() - f.pivotX()) * quad.scale;
        double top = (f.offsetY() - f.pivotY()) * quad.scale;
        double right = left + f.width() * quad.scale;
        double bottom = top + f.height() * quad.scale;
        float u0 = (float) f.x() / atlas.width, u1 = (float) (f.x() + f.width()) / atlas.width;
        float v0 = (float) f.y() / atlas.height, v1 = (float) (f.y() + f.height()) / atlas.height;
        vertex(out, quad, view, left, top, u0, v0);
        vertex(out, quad, view, left, bottom, u0, v1);
        vertex(out, quad, view, right, bottom, u1, v1);
        vertex(out, quad, view, right, top, u1, v0);
    }

    private static void vertex(VertexConsumer out, Quad quad, Matrix4f view, double x, double y, float u, float v) {
        // The camera rotation's first two rows are world-space screen right and up.
        Vec3 origin = quad.origin;
        if (quad.rotation != 0) {
            double cos = Math.cos(quad.rotation), sin = Math.sin(quad.rotation);
            double localX = x * cos - y * sin;
            y = x * sin + y * cos;
            x = localX;
        }
        out.addVertex((float) (origin.x + view.m00() * x - view.m01() * y),
                (float) (origin.y + view.m10() * x - view.m11() * y),
                (float) (origin.z + view.m20() * x - view.m21() * y)).setUv(u, v).setColor(quad.color);
    }

    static void guiRibbon(GuiGraphicsExtractor graphics, ScreenRectangle canvas, SpriteSheet sheet,
                          SpriteSheet.Frame frame, List<TrailRenderer.Sample> path, double unitsPerBlock,
                          long fadeNow, TrailConfig cfg, double fade, double reflow, boolean pulse) {
        Atlas atlas = atlas(sheet);
        if (atlas == null) return;
        var map = new SpriteRibbon.FrameMap(sheet, frame);
        var edges = new ArrayList<SpriteRibbon.Edge>(path.size());
        SpriteRibbon.emit(map, path, null, unitsPerBlock, fadeNow, cfg, fade, reflow, pulse, (a, b) -> {
            edges.add(a);
            edges.add(b);
        });
        if (edges.isEmpty()) return;
        var pose = new Matrix3x2f(graphics.pose());
        var texture = TextureSetup.singleTexture(atlas.texture.getTextureView(), atlas.texture.getSampler());
        var pipeline = cfg.additiveGlow ? Pipelines.GUI_ADDITIVE : RenderPipelines.GUI_TEXTURED;
        textureOwner.gameRenderer.gameRenderState().guiRenderState.addGuiElement(new GuiRibbon(
                pipeline, texture, canvas.transformMaxBounds(pose), pose, edges, map.v0(), map.v1()));
    }

    private record GuiRibbon(RenderPipeline pipeline, TextureSetup textureSetup, ScreenRectangle scissorArea,
                             Matrix3x2f pose, List<SpriteRibbon.Edge> edges, float v0, float v1)
            implements GuiElementRenderState {
        @Override public ScreenRectangle bounds() { return scissorArea; }

        @Override public void buildVertices(VertexConsumer out) {
            for (int i = 0; i < edges.size(); i += 2) {
                var a = edges.get(i);
                var b = edges.get(i + 1);
                vertex(out, a.top(), a.u(), v0, a.color());
                vertex(out, a.bottom(), a.u(), v1, a.color());
                vertex(out, b.bottom(), b.u(), v1, b.color());
                vertex(out, b.top(), b.u(), v0, b.color());
            }
        }

        private void vertex(VertexConsumer out, Vec3 position, float u, float v, int color) {
            out.addVertexWith2DPose(pose, (float) position.x, (float) position.y).setUv(u, v).setColor(color);
        }
    }

    static void gui(GuiGraphicsExtractor graphics, SpriteSheet sheet, SpriteSheet.Frame frame,
                    int x, int y, double size, double opacity) {
        guiTinted(graphics, sheet, frame, x, y, size, alpha(opacity) << 24 | 0xFFFFFF, 0);
    }

    static void guiTinted(GuiGraphicsExtractor graphics, SpriteSheet sheet, SpriteSheet.Frame frame,
                          float x, float y, double size, int argb, double rotation) {
        if (argb >>> 24 == 0 || size <= 0) return;
        Atlas atlas = atlas(sheet);
        if (atlas == null) return;
        float scale = (float) scale(frame, size);
        var pose = graphics.pose();
        pose.pushMatrix();
        try {
            pose.translate(x, y).rotate((float) rotation).scale(scale).translate((float) -frame.pivotX(), (float) -frame.pivotY());
            var pipeline = XpOrbTrailsClient.CONFIG.additiveGlow ? Pipelines.GUI_ADDITIVE : RenderPipelines.GUI_TEXTURED;
            graphics.blit(pipeline, atlas.id, frame.offsetX(), frame.offsetY(), frame.x(), frame.y(),
                    frame.width(), frame.height(), sheet.width(), sheet.height(), argb);
        } finally {
            pose.popMatrix();
        }
    }

    private static int alpha(double opacity) {
        return (int) Math.round(255 * Math.clamp(opacity, 0, 1));
    }

    private static double scale(SpriteSheet.Frame frame, double size) {
        return size / Math.max(frame.sourceWidth(), frame.sourceHeight());
    }

    private static Atlas atlas(SpriteSheet sheet) {
        if (!RenderSystem.isOnRenderThread() || RenderSystem.tryGetDevice() == null) return null;
        Atlas existing = ATLASES.get(sheet);
        if (existing != null) return existing;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getTextureManager() == null) return null;
        textureOwner = client;
        var id = Identifier.fromNamespaceAndPath("xporbtrails", "dynamic/sprite_" + textureSerial++);
        // DynamicTexture uses a nearest sampler. Keep a single GPU copy shared by GUI and world draws.
        var texture = new DynamicTexture(id::toString, sheet.width(), sheet.height(), false);
        try {
            var pixels = texture.getPixels();
            var image = sheet.image();
            int[] row = new int[sheet.width()];
            for (int y = 0; y < sheet.height(); y++) {
                image.getRGB(0, y, row.length, 1, row, 0, row.length);
                for (int x = 0; x < row.length; x++) pixels.setPixel(x, y, row[x]);
            }
            texture.upload();
            pixels.close();
            client.getTextureManager().register(id, texture);
        } catch (RuntimeException | Error e) {
            texture.close();
            throw e;
        }
        Atlas atlas = new Atlas(id, texture, sheet.width(), sheet.height());
        ATLASES.put(sheet, atlas);
        return atlas;
    }

    static void reload() {
        Minecraft owner = textureOwner;
        if (owner == null) return;
        owner.execute(() -> {
            var live = XpOrbTrailsClient.sprites().values();
            ATLASES.entrySet().removeIf(entry -> {
                if (live.contains(entry.getKey())) return false;
                owner.getTextureManager().release(entry.getValue().id);
                return true;
            });
        });
    }

    static void close() {
        if (textureOwner != null) {
            for (Atlas atlas : ATLASES.values()) textureOwner.getTextureManager().release(atlas.id);
            ATLASES.clear();
            textureOwner = null;
        }
        batches.clear();
        orbAtlas = null;
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }

    private record Quad(SpriteSheet.Frame frame, Vec3 origin, double scale, int color, double rotation) { }

    private static final class Atlas {
        final Identifier id;
        final AbstractTexture texture;
        final int width, height;
        final List<Quad> quads = new ArrayList<>();
        final List<Consumer<VertexConsumer>> ribbons = new ArrayList<>();
        StagedVertexBuffer.Draw draw;

        Atlas(Identifier id, AbstractTexture texture, int width, int height) {
            this.id = id;
            this.texture = texture;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Pipelines {
        static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);
        static final Vector3f ZERO = new Vector3f();
        static final Matrix4f IDENTITY = new Matrix4f();
        static final BlendFunction GLOW = new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE,
                BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA);
        static final RenderPipeline ALPHA = make("sprite_alpha", BlendFunction.TRANSLUCENT, true);
        static final RenderPipeline ADDITIVE = make("sprite_additive", GLOW, true);
        static final RenderPipeline GUI_ADDITIVE = make("sprite_gui_additive", GLOW, false);

        private static RenderPipeline make(String name, BlendFunction blend, boolean world) {
            var builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("xporbtrails", name))
                    .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                    .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                    .withVertexShader(Identifier.withDefaultNamespace("core/position_tex_color"))
                    .withFragmentShader(Identifier.withDefaultNamespace("core/position_tex_color"))
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .withCull(false)
                    .withColorTargetState(new ColorTargetState(blend));
            if (world) builder.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false));
            return RenderPipelinesAccessor.xporbtrails$register(builder.build());
        }
    }
}

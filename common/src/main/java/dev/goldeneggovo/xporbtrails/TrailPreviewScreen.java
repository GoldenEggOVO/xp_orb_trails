package dev.goldeneggovo.xporbtrails;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class TrailPreviewScreen extends Screen {
    private static final double TRAVEL_SECONDS = 4.0;
    private static final double PIXELS_PER_BLOCK = 75;
    private static final Identifier ORB_TEXTURE = Identifier.withDefaultNamespace("textures/entity/experience/experience_orb.png");
    private final Screen parent;
    private final double[] previousTubeRing = new double[CrossSectionRadii.MAX_POINTS * 3];
    private final double[] tubeRing = new double[CrossSectionRadii.MAX_POINTS * 3];
    private final FixedStampHistory fixedHistory = new FixedStampHistory();
    private record PreviewPath(int left, int right, int centerY, int height, double spacing) {}
    private PreviewPath fixedPath;
    private int lastFixedTick = -1;
    private PreviewPath ribbonPath;
    private TrailRenderer.Trail ribbonHistory;
    private int lastRibbonTick;
    private long demoStartNanos = System.nanoTime();
    private boolean pickupReplay;
    private EffectEmission flightEmission, pickupEmission;
    private EmitterView emitterView;
    private long emitterCycle;
    private EmitterView advancedView;
    private AdvancedTrailPreview advancedTrail;
    private BodyPickupPreview advancedOrb;

    public TrailPreviewScreen(Screen parent) {
        super(Component.translatable("screen.xporbtrails.preview_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int footerY = height - 28;
        addRenderableWidget(new SettingsButton(width / 2 - 154, footerY, 150,
                Component.translatable(pickupReplay ? "screen.xporbtrails.preview_pickup" : "screen.xporbtrails.replay_pickup"),
                b -> { if (pickupReplay) replayPickup(); else replay(); }, SettingsButton.Style.CONTROL, false));
        addRenderableWidget(new SettingsButton(width / 2 + 4, footerY, 150, Component.translatable("screen.xporbtrails.back_to_settings"),
                b -> onClose(), SettingsButton.Style.CONTROL, false));
    }

    void replay() {
        pickupReplay = false;
        fixedPath = null;
        ribbonPath = null;
        emitterView = null;
        advancedView = null;
        demoStartNanos = System.nanoTime();
    }

    void replayPickup() {
        replay();
        pickupReplay = true;
        // Leave a brief approach so the viewer sees the orb just before collection.
        demoStartNanos -= (long) ((TRAVEL_SECONDS - 0.30) * 1.0E9);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);

        int canvasW = Math.max(260, Math.min(640, width - 32));
        int canvasH = Math.max(120, height - 76);
        int x0 = width / 2 - canvasW / 2;
        int y0 = 30;
        extractPreview(graphics, x0, y0, canvasW, canvasH, false);
        var advanced = XpOrbTrailsClient.CONFIG.advanced;
        if (advanced.has("workshop"))
            graphics.centeredText(font, Component.translatable("screen.xporbtrails.preview_workshop"), width / 2, y0 + 4, 0xffd0e9d5);
        else if (advanced.has("scene") || advanced.has("optics"))
            graphics.centeredText(font, Component.translatable("screen.xporbtrails.preview_world_rules"), width / 2, y0 + 4, 0xffd0e9d5);
    }

    public void extractPreview(GuiGraphicsExtractor graphics, int x, int y, int w, int h, boolean compact) {
        graphics.enableScissor(x, y, x + w, y + h);
        drawBackdrop(graphics, x, y, w, h, compact);
        drawPreview(graphics, x, y, w, h, compact);
        graphics.disableScissor();
    }

    private void drawBackdrop(GuiGraphicsExtractor graphics, int x, int y, int w, int h, boolean compact) {
        graphics.fill(x, y, x + w, y + h, 0xFF0B1517);
        int inset = compact ? 22 : 1;
        for (int gx = x + 1; gx < x + w; gx += 23) {
            graphics.fill(gx, y + inset, gx + 1, y + h - inset, 0xFF1F3232);
        }
        for (int gy = y + inset; gy < y + h - inset; gy += 23) {
            graphics.fill(x + 1, gy, x + w - 1, gy + 1, 0xFF1F3232);
        }
        if (!compact) graphics.outline(x, y, w, h, TrailConfigScreen.RULE);
    }

    private void drawPreview(GuiGraphicsExtractor graphics, int x, int y, int w, int h, boolean compact) {
        TrailConfig c = XpOrbTrailsClient.CONFIG;
        var canvas = new ScreenRectangle(x, y, w, h);
        SpriteSheet trailSprite = XpOrbTrailsClient.sprite(AssetRole.TRAIL, c.trailSprite);
        SpriteSheet flightSprite = XpOrbTrailsClient.sprite(AssetRole.ORB, c.flightEffect);
        CustomEffect flight = XpOrbTrailsClient.effect(AssetRole.ORB, c.flightEffect);
        boolean bodyTrail = "body".equals(c.trailSprite);
        boolean customTrail = bodyTrail || trailSprite != null;
        boolean customTube = !customTrail && c.usesCustomCrossSection();
        boolean hasAdvancedTrail = c.enabled && AdvancedTrails.enabled(c);
        boolean hasAdvancedOrb = c.enabled && (c.advanced.has("body") || c.advanced.has("pickup"));
        CustomEffect pickup = XpOrbTrailsClient.effect(AssetRole.PICKUP, c.pickupFlashStyle);
        SpriteSheet pickupSprite = XpOrbTrailsClient.sprite(AssetRole.PICKUP, c.pickupFlashStyle);
        double flashSeconds = pickupSprite == null ? c.pickupFlashSeconds : pickupSprite.durationMillis() / 1000.0;
        double elapsed = (System.nanoTime() - demoStartNanos) / 1_000_000_000.0;
        double period = cycleDuration(c, flashSeconds, flight, pickup);
        double cycle = elapsed % period;
        double seconds = Math.min(cycle, TRAVEL_SECONDS);
        double travel = Math.min(1.0, cycle / TRAVEL_SECONDS);
        travel = TrailEffects.smooth(travel);
        boolean particles = c.enabled && (flight != null && flight.emitter != null
                || c.pickupFlash && pickup != null && pickup.emitter != null);
        boolean scaledView = particles || hasAdvancedTrail || hasAdvancedOrb;
        double pixelsPerBlock = scaledView ? Math.clamp((h - 48) / 5.0, 12, PIXELS_PER_BLOCK) : PIXELS_PER_BLOCK;
        int margin = (int) Math.min(w * 0.32, Math.max(30, pixelsPerBlock * 2.5));
        int left = x + (scaledView ? margin : 28);
        int right = x + w - (scaledView ? margin : 30);
        int centerY = y + h / 2 + (scaledView ? 0 : 8);

        if (!compact) {
            Component mode = hasAdvancedTrail ? Component.translatable("screen.xporbtrails.preview_advanced_trail")
                    : customTrail ? Component.translatable("screen.xporbtrails.trail_sprite_mode").append(": ")
                    .append(previewLayout(c, trailSprite)) : Component.translatable("screen.xporbtrails.preview_mode")
                    .append(": ").append(Component.translatable("screen.xporbtrails.mode." + c.colorMode));
            Component flash = Component.translatable("screen.xporbtrails.preview_flash").append(": ")
                    .append(c.pickupFlash && c.advanced.has("pickup") ? Component.translatable("screen.xporbtrails.profile_effect")
                            : TrailConfigScreen.effectLabel(AssetRole.PICKUP, c.pickupFlash ? c.pickupFlashStyle : "none"));
            Component flightLabel = Component.translatable("screen.xporbtrails.flight_effect").append(": ")
                    .append(c.advanced.has("body") ? Component.translatable("screen.xporbtrails.profile_effect")
                            : TrailConfigScreen.effectLabel(AssetRole.ORB, c.flightEffect));
            graphics.text(font, mode, x + 10, y + 9, 0xFFBFD9C8);
            graphics.text(font, flash, x + 10, y + 21, 0xFF8EB59B);
            graphics.text(font, flightLabel, x + 10, y + 33, 0xFF8EB59B);
        }

        int samples = Math.max(36, Math.min(90, w / 6));
        double visibleLength = Math.min(1.0, 0.28 + c.lifetimeSeconds / 7.5);
        double tailStart = Math.max(0.0, travel - visibleLength);
        if (customTrail || customTube) {
            tailStart = TrailEffects.smooth(Math.max(0, seconds - c.lifetimeSeconds) / TRAVEL_SECONDS);
        }
        double flashProgress = pickupProgress(cycle, flashSeconds);
        boolean reflow = c.pickupFlash && pickup != null && pickup.reflow && flashProgress >= 0;
        boolean pulse = c.flightPulse && cycle < TRAVEL_SECONDS;
        double front = reflow ? TrailEffects.reflowFront(flashProgress) : TrailEffects.pulseFront(seconds, c.pulseSpeed);
        double bandWidth = reflow ? TrailEffects.reflowBandWidth(c.pickupFlashSize) : 0.12;
        double fade = reflow ? (flashProgress < 1 ? 1 : 0) : 1.0 - TrailEffects.smooth((cycle - TRAVEL_SECONDS) / c.pickupFadeSeconds);
        PreviewFrame advanced = null;
        if (hasAdvancedTrail || hasAdvancedOrb) {
            var view = new EmitterView(left, right, centerY, h, pixelsPerBlock);
            if (!view.equals(advancedView)) {
                advancedView = view;
                advancedTrail = new AdvancedTrailPreview();
                advancedOrb = new BodyPickupPreview();
            }
            advanced = new PreviewFrame(graphics, canvas, view);
            if (hasAdvancedTrail) {
                advancedTrail.append(advanced, c, view::flight, (long) (elapsed / period), cycle, TRAVEL_SECONDS, fade);
                advanced.flush();
            }
        }
        if (c.enabled && !hasAdvancedTrail && customTrail && fade > 0 && travel > tailStart) {
            long fadeNow = (long) (seconds * 1.0E9), now = (long) (cycle * 1.0E9);
            double reflowProgress = reflow ? flashProgress : -1;
            if (bodyTrail || (c.fixedStamps && "stamps".equals(c.trailSpriteMode))) {
                var path = new PreviewPath(left, right, centerY, h, pixelsPerBlock * TrailRenderer.stampSpacing(c));
                updateFixedPath(path, seconds);
                var marks = fixedHistory.snapshot(fadeNow - (long) (c.lifetimeSeconds * 1.0E9));
                TrailRenderer.emitFixedStamps(marks, fadeNow, c, fade, reflowProgress, pulse, (mark, scale, opacity) -> {
                    int px = (int) Math.round(mark.position().x), py = (int) Math.round(mark.position().y);
                    if (bodyTrail) {
                        drawBody(graphics, canvas, px, py, mark.time() / 1.0E9, c, flightSprite, flight, pixelsPerBlock, scale, opacity, opacity);
                    } else {
                        var frame = mark.spriteFrame(trailSprite, now, 0, false, c.showVanillaOrb);
                        if (frame != null) SpriteRenderer.gui(graphics, trailSprite, frame, px, py,
                                pixelsPerBlock * 0.30 * scale, opacity);
                    }
                });
            } else {
                if ("ribbon".equals(c.trailSpriteMode)) {
                    var path = ribbonSamples(new PreviewPath(left, right, centerY, h, pixelsPerBlock * c.pointSpacing),
                            seconds, fadeNow - (long) (c.lifetimeSeconds * 1.0E9));
                    var frame = trailSprite.frameAt(cycle, trailSprite.loop());
                    if (frame != null) SpriteRenderer.guiRibbon(graphics, canvas,
                            trailSprite, frame, path, pixelsPerBlock, fadeNow, c, fade, reflowProgress, pulse);
                } else {
                    var path = spritePath(left, right, centerY, h, tailStart, travel, samples);
                    TrailRenderer.emitTrailStamps(trailSprite, path, fadeNow, now, c, fade, reflowProgress, pulse,
                            pixelsPerBlock, (frame, point, size, opacity) -> SpriteRenderer.gui(graphics, trailSprite,
                                    frame, (int) Math.round(point.x), (int) Math.round(point.y), size, opacity));
                }
            }
        }
        if (c.enabled && !hasAdvancedTrail && customTube && fade > 0 && travel > tailStart) {
            drawCustomTube(graphics, canvas, c, left, right, centerY, h, pixelsPerBlock, tailStart, travel,
                    Math.min(samples, 44), seconds, fade, reflow ? flashProgress : -1, pulse);
        }
        for (int i = 0; c.enabled && !hasAdvancedTrail && !customTrail && !customTube && fade > 0 && i < samples; i++) {
            double q = i / (double) (samples - 1);
            double p = tailStart + (travel - tailStart) * q;
            int px = left + (int) Math.round((right - left) * p);
            int py = pathY(centerY, h, p, seconds);
            double shape = TrailRenderer.widthScale(c, q);
            int radius = Math.max(1, Math.min(11, (int) Math.round(c.width * 22.0 * shape)));
            int rgb = colorAt(c, q, seconds);
            int alpha = clamp255((int) (255 * c.effectStrength * fade * (0.14 + q * 0.86)));
            if (reflow || pulse) {
                double light = TrailEffects.band(q, front, bandWidth);
                double visibility = reflow ? TrailEffects.reflowVisibility(q, front) : 1;
                double strength = reflow ? c.pickupFlashStrength : 0.70;
                int color = TrailEffects.bandColor(rgb, c.effectStrength * fade * (0.14 + q * 0.86),
                        c.effectStrength * strength, light, visibility);
                alpha = color >>> 24;
                if (alpha == 0) continue;
                rgb = color & 0xFFFFFF;
                radius = Math.max(1, (int) Math.round(radius * (1 + 0.28 * light)));
            }
            if (c.additiveGlow) glowSquare(graphics, px, py, radius, rgb, alpha);
            else graphics.fill(px - radius, py - radius, px + radius + 1, py + radius + 1, (alpha << 24) | rgb);
        }

        int headX = left + (int) Math.round((right - left) * travel);
        int headY = pathY(centerY, h, travel, seconds);
        if (cycle < TRAVEL_SECONDS) {
            drawBody(graphics, canvas, headX, headY, seconds, c, flightSprite, flight, pixelsPerBlock, 1, c.effectStrength, 1);
        }
        if (hasAdvancedOrb) {
            advancedOrb.append(advanced, c, advancedView::flight, (long) (elapsed / period), cycle, TRAVEL_SECONDS);
            advanced.flush();
        }

        if (c.enabled && c.pickupFlash && flashProgress >= 0.0 && flashProgress < 1.0) {
            if (pickupSprite != null) {
                var frame = pickupSprite.frameAt(cycle - TRAVEL_SECONDS, false);
                if (frame != null) SpriteRenderer.gui(graphics, pickupSprite, frame, headX, headY,
                        pixelsPerBlock * c.pickupFlashSize, c.effectStrength * c.pickupFlashStrength);
            } else if (pickup != null) {
                double p = reflow ? tailStart + (travel - tailStart) * front : travel;
                int centerX = left + (int) Math.round((right - left) * p);
                double pathSeconds = customTrail ? crossingTime(p) : seconds;
                drawEffectPieces(graphics, canvas, centerX, pathY(centerY, h, p, pathSeconds), flashProgress, c, seconds,
                        pickup, pixelsPerBlock * 0.24 * c.pickupFlashSize, c.effectStrength * c.pickupFlashStrength);
            }
        }

        if (particles) {
            var view = new EmitterView(left, right, centerY, h, pixelsPerBlock);
            drawEmissions(graphics, canvas, c, view, (long) (elapsed / period), cycle, flight, pickup, tailStart, customTrail);
        } else {
            emitterView = null;
            flightEmission = pickupEmission = null;
        }

        if (compact) {
            graphics.fill(x, y, x + w, y + 22, 0xFF1A2C27);
            graphics.fill(x, y + h - 22, x + w, y + h, 0xFF1A2C27);
            graphics.text(font, Component.translatable("screen.xporbtrails.preview_live"),
                    x + 9, y + 7, TrailConfigScreen.TEXT, false);
            if (hasAdvancedTrail || customTrail) {
                Component label = hasAdvancedTrail ? Component.translatable("screen.xporbtrails.preview_advanced_trail")
                        : previewLayout(c, trailSprite);
                graphics.text(font, font.plainSubstrByWidth(label.getString(), w - 18), x + 9, y + h - 15,
                        TrailConfigScreen.TEXT, false);
                graphics.outline(x, y, w, h, 0xFF658D7B);
                return;
            }
            if (customTube) {
                graphics.text(font, customSectionLabel(c), x + 9, y + h - 15, TrailConfigScreen.TEXT, false);
                drawCrossSection(graphics, x + w - 16, y + h - 11, c, 7, colorAt(c, 1, seconds));
            } else {
                graphics.text(font, Component.translatable("screen.xporbtrails.preview_cross_section"),
                        x + 9, y + h - 15, TrailConfigScreen.TEXT, false);
                Component sides = Component.translatable("screen.xporbtrails.cross_section.sides", c.crossSectionSides);
                int valueX = x + w - font.width(sides) - 8;
                drawCrossSection(graphics, valueX - 14, y + h - 11, c, 7, colorAt(c, 1, seconds));
                graphics.text(font, sides, valueX, y + h - 15, TrailConfigScreen.TEXT, false);
            }
            graphics.outline(x, y, w, h, 0xFF658D7B);
            return;
        }
        if (hasAdvancedTrail) return;
        if (customTrail) {
            graphics.text(font, font.plainSubstrByWidth(TrailConfigScreen.trailLabel(c.trailSprite).getString(), w - 24), x + 12, y + h - 19,
                    TrailConfigScreen.TEXT, false);
        } else {
            Component label = customTube ? customSectionLabel(c) : TrailConfigScreen.crossSectionLabel(c.crossSectionSides);
            graphics.text(font, label, x + 12, y + h - 19, TrailConfigScreen.TEXT, false);
            drawCrossSection(graphics, x + w - 22, y + h - 16, c, 12, colorAt(c, 1, seconds));
        }
    }

    private List<TrailRenderer.Sample> ribbonSamples(PreviewPath path, double seconds, long oldest) {
        int tick = (int) Math.ceil(seconds * 20);
        if (!path.equals(ribbonPath) || tick < lastRibbonTick) {
            ribbonPath = path;
            ribbonHistory = new TrailRenderer.Trail();
            lastRibbonTick = -1;
        }
        while (lastRibbonTick < tick) {
            lastRibbonTick++;
            ribbonHistory.append(previewPoint(path, lastRibbonTick / 20.0), lastRibbonTick * 50_000_000L, path.spacing);
        }
        ribbonHistory.expireBefore(oldest);
        return ribbonHistory.samples(previewPoint(path, seconds));
    }

    private static Vec3 previewPoint(PreviewPath path, double seconds) {
        double p = TrailEffects.smooth(seconds / TRAVEL_SECONDS);
        return new Vec3(path.left + (path.right - path.left) * p, pathHeight(path.centerY, path.height, p, seconds), 0);
    }

    private static Component previewLayout(TrailConfig c, SpriteSheet sheet) {
        if ("body".equals(c.trailSprite)) return TrailConfigScreen.trailLabel("body");
        String key;
        if ("stamps".equals(c.trailSpriteMode)) {
            key = c.fixedStamps ? "preview_fixed_stamps" : "trail_sprite_mode.stamps";
        } else if (sheet.ribbon() != null) {
            key = "preview_segmented_ribbon";
        } else {
            key = "ribbon_mapping." + c.ribbonMapping;
        }
        return Component.translatable("screen.xporbtrails." + key);
    }

    static double cycleDuration(TrailConfig c) {
        var sheet = XpOrbTrailsClient.sprite(AssetRole.PICKUP, c.pickupFlashStyle);
        return cycleDuration(c, sheet == null ? c.pickupFlashSeconds : sheet.durationMillis() / 1000.0,
                XpOrbTrailsClient.effect(AssetRole.ORB, c.flightEffect),
                XpOrbTrailsClient.effect(AssetRole.PICKUP, c.pickupFlashStyle));
    }

    private static double cycleDuration(TrailConfig c, double flashSeconds, CustomEffect flight, CustomEffect pickup) {
        double flash = c.pickupFlash ? flashSeconds : 0;
        if (c.enabled && c.pickupFlash && pickup != null && pickup.emitter != null) {
            flash = EffectEmission.pickupDuration(pickup, flashSeconds) + pickup.emitter.maxLifetime();
        }
        if (c.enabled && flight != null && flight.emitter != null) flash = Math.max(flash, flight.emitter.maxLifetime());
        if (c.enabled) flash = Math.max(flash, Math.max(AdvancedTrails.afterglowSeconds(c), BodyPickupPreview.afterglowSeconds(c)));
        return TRAVEL_SECONDS + Math.max(1.2, Math.max(c.pickupFadeSeconds, flash) + 0.2);
    }

    static double pickupProgress(double cycle, double duration) {
        return (cycle - TRAVEL_SECONDS) / duration;
    }

    private static Component customSectionLabel(TrailConfig c) {
        return Component.translatable("screen.xporbtrails.cross_section.custom", c.crossSectionRadii.length);
    }

    private void drawCustomTube(GuiGraphicsExtractor graphics, ScreenRectangle canvas, TrailConfig config,
                                int left, int right, int centerY, int height, double pixelsPerBlock, double start, double end, int samples,
                                double seconds, double fade, double reflow, boolean pulse) {
        var path = new ArrayList<TrailRenderer.Sample>(samples);
        for (int i = 0; i < samples; i++) {
            double p = start + (end - start) * i / (samples - 1);
            double x = (right - left) * p / pixelsPerBlock;
            double y = (centerY - pathHeight(centerY, height, p, seconds)) / pixelsPerBlock;
            // Include travel toward the viewer so the projected sides can be seen around bends.
            path.add(new TrailRenderer.Sample(new Vec3(x, y, x * 0.65), (long) (crossingTime(p) * 1.0E9)));
        }
        var vertices = new ArrayList<EffectVertex>(4 * config.crossSectionRadii.length * (samples - 1));
        TrailRenderer.emitStyledTube((x, y, z, color) -> vertices.add(new EffectVertex(
                        (float) (left + x * pixelsPerBlock), (float) (centerY - y * pixelsPerBlock), color)),
                path, Vec3.ZERO, (long) (seconds * 1.0E9), (long) (config.lifetimeSeconds * 1.0E9),
                config, config.effectStrength * fade, reflow, pulse, previousTubeRing, tubeRing);
        submitEffect(graphics, canvas, vertices);
    }

    static double[] crossSectionVertices(int sides, double radius) {
        return crossSectionVertices(sides, radius, CrossSectionRadii.NONE);
    }

    static double[] crossSectionVertices(int sides, double radius, double[] radii) {
        if (radii.length != 0) sides = radii.length;
        if (sides == 2) return new double[]{-radius, -1.5, radius, -1.5, radius, 1.5, -radius, 1.5};
        double[] vertices = new double[sides * 2];
        for (int i = 0; i < sides; i++) {
            double angle = Math.PI * 2 * i / sides - Math.PI / 2;
            double length = radii.length == 0 ? radius : radius * radii[i];
            vertices[i * 2] = Math.cos(angle) * length;
            vertices[i * 2 + 1] = Math.sin(angle) * length;
        }
        return vertices;
    }

    private void drawCrossSection(GuiGraphicsExtractor graphics, int x, int y, TrailConfig c, int radius, int rgb) {
        double[] vertices = crossSectionVertices(c.crossSectionSides, radius,
                c.usesCustomCrossSection() ? c.crossSectionRadii : CrossSectionRadii.NONE);
        double[] intersections = new double[vertices.length / 2];
        for (int row = -radius; row < radius; row++) {
            double scan = row + 0.5;
            int count = 0;
            for (int i = 0; i < vertices.length; i += 2) {
                int next = (i + 2) % vertices.length;
                double y0 = vertices[i + 1], y1 = vertices[next + 1];
                if ((y0 > scan) == (y1 > scan)) continue;
                double edge = vertices[i] + (scan - y0) * (vertices[next] - vertices[i]) / (y1 - y0);
                intersections[count++] = edge;
            }
            Arrays.sort(intersections, 0, count);
            for (int i = 0; i < count; i += 2) {
                graphics.fill(x + (int) Math.floor(intersections[i]), y + row,
                        x + (int) Math.ceil(intersections[i + 1]), y + row + 1, 0xFF000000 | rgb);
            }
        }
    }

    private void glowSquare(GuiGraphicsExtractor graphics, int x, int y, int radius, int rgb, int alpha) {
        int outer = radius + 4;
        graphics.fill(x - outer, y - outer, x + outer + 1, y + outer + 1, (alpha / 7 << 24) | rgb);
        int middle = radius + 2;
        graphics.fill(x - middle, y - middle, x + middle + 1, y + middle + 1, (alpha / 3 << 24) | rgb);
        graphics.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, (alpha << 24) | rgb);
        if (radius >= 3) graphics.fill(x - radius / 2, y - radius / 2, x + radius / 2 + 1, y + radius / 2 + 1, (Math.min(255, alpha + 55) << 24) | 0xE8FFD8);
    }

    private void drawBody(GuiGraphicsExtractor graphics, ScreenRectangle canvas, int x, int y, double seconds, TrailConfig c,
                          SpriteSheet sheet, CustomEffect effect, double pixelsPerBlock, double scale, double opacity, double nativeOpacity) {
        if (c.showsVanillaBody(sheet != null || effect != null)) {
            float size = (float) (0.30 * pixelsPerBlock * scale / 16);
            int tint = (int) Math.round(Math.clamp(nativeOpacity, 0, 1) * 128) << 24
                    | FixedStampHistory.vanillaTint(seconds * 20);
            float baseY = (float) (y + pixelsPerBlock * (c.orbYOffset - 0.1));
            graphics.pose().pushMatrix().translate(x, baseY).scale(size);
            graphics.blit(RenderPipelines.GUI_TEXTURED, ORB_TEXTURE, -8, -12, 16, 16, 16, 16, 64, 64, tint);
            graphics.pose().popMatrix();
        }
        if (!c.enabled) return;
        double size = pixelsPerBlock * TrailEffects.flightSize(c) * scale;
        if (sheet != null) {
            var frame = sheet.frameAt(seconds, !c.showVanillaOrb || sheet.loop());
            if (frame != null) SpriteRenderer.gui(graphics, sheet, frame, x, y, 2 * size, opacity);
        } else if (effect != null) {
            drawEffectPieces(graphics, canvas, x, y, TrailEffects.flightProgress(seconds, c.pulseSpeed), c,
                    seconds, effect, size, opacity);
        }
    }

    private void updateFixedPath(PreviewPath path, double seconds) {
        int tick = (int) Math.floor(seconds * 20);
        if (!path.equals(fixedPath) || tick < lastFixedTick) {
            fixedHistory.reset();
            fixedPath = path;
            lastFixedTick = -1;
        }
        // Use stable tick endpoints so an advancing head cannot move an earlier stamp.
        while (lastFixedTick < tick) {
            double crossed = ++lastFixedTick / 20.0, p = TrailEffects.smooth(crossed / TRAVEL_SECONDS);
            var point = new Vec3(path.left + (path.right - path.left) * p, pathHeight(path.centerY, path.height, p, crossed), 0);
            fixedHistory.append(point, lastFixedTick * 50_000_000L, path.spacing);
        }
    }

    private record EffectVertex(float x, float y, int color) {}

    /** The orthographic GUI consumes the same world-space primitives as the in-game renderer. */
    private static final class PreviewFrame extends EffectFrame {
        private static final Vec3 VIEW = new Vec3(Math.sqrt(0.375), 0.5, Math.sqrt(0.375));
        private record Layer(double depth, Consumer<List<EffectVertex>> emit) {}
        private final List<Layer> layers = new ArrayList<>();
        private final GuiGraphicsExtractor graphics;
        private final ScreenRectangle canvas;
        private final EmitterView view;

        PreviewFrame(GuiGraphicsExtractor graphics, ScreenRectangle canvas, EmitterView view) {
            this.graphics = graphics;
            this.canvas = canvas;
            this.view = view;
        }

        private EffectVertex project(Vec3 p, int argb) {
            return new EffectVertex((float) (view.left + EmitterView.right(p.x, p.z) * view.pixelsPerBlock),
                    (float) (view.centerY - EmitterView.up(p.x, p.y, p.z) * view.pixelsPerBlock), argb);
        }

        @Override void line(Vec3 a, Vec3 b, double width, int argb) {
            if (width <= 0 || argb >>> 24 == 0) return;
            Vec3 side = b.subtract(a).cross(VIEW).normalize().scale(width * 0.5);
            quad(a.add(side), a.subtract(side), b.subtract(side), b.add(side), argb);
        }

        @Override void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int argb) {
            if (argb >>> 24 == 0) return;
            double depth = (a.dot(VIEW) + b.dot(VIEW) + c.dot(VIEW) + d.dot(VIEW)) / 4;
            layers.add(new Layer(depth, vertices -> {
                var pa = project(a, argb);
                var pb = project(b, argb);
                var pc = project(c, argb);
                var pd = project(d, argb);
                // Vanilla GUI culls clockwise screen faces; world ribbons are double-sided.
                boolean reverse = (pb.x - pa.x) * (pd.y - pa.y) - (pb.y - pa.y) * (pd.x - pa.x) > 0;
                vertices.add(pa);
                vertices.add(reverse ? pd : pb);
                vertices.add(pc);
                vertices.add(reverse ? pb : pd);
            }));
        }

        @Override void sprite(SpriteSheet sheet, Vec3 center, double size, double age, int argb, double rotation) {
            if (sheet == null || size <= 0 || argb >>> 24 == 0) return;
            var frame = sheet.frameAt(age, sheet.loop());
            if (frame == null) return;
            layers.add(new Layer(center.dot(VIEW), vertices -> {
                submit(vertices);
                var p = project(center, argb);
                SpriteRenderer.guiTinted(graphics, sheet, frame, p.x, p.y, size * view.pixelsPerBlock, argb, rotation);
            }));
        }

        private void submit(List<EffectVertex> vertices) {
            if (vertices.isEmpty()) return;
            submitEffect(graphics, canvas, List.copyOf(vertices));
            vertices.clear();
        }

        void flush() {
            layers.sort(Comparator.comparingDouble(Layer::depth));
            List<EffectVertex> vertices = new ArrayList<>();
            for (var layer : layers) layer.emit.accept(vertices);
            submit(vertices);
            layers.clear();
        }
    }

    // Screen right and up are orthogonal unit vectors in the preview's oblique world view.
    private record EmitterView(int left, int right, int centerY, int height, double pixelsPerBlock) {
        static final double DIAGONAL = Math.sqrt(0.5), UP_Y = Math.sqrt(0.75), UP_XZ = -DIAGONAL * 0.5;

        Vec3 source(double p, double seconds) {
            double x = (right - left) * p / pixelsPerBlock;
            double y = (centerY - pathHeight(centerY, height, p, seconds)) / pixelsPerBlock;
            return new Vec3(DIAGONAL * x + UP_XZ * y, UP_Y * y, -DIAGONAL * x + UP_XZ * y);
        }

        Vec3 flight(double seconds) {
            return source(TrailEffects.smooth(seconds / TRAVEL_SECONDS), seconds);
        }

        static double right(double x, double z) { return (x - z) * DIAGONAL; }
        static double up(double x, double y, double z) { return UP_Y * y + UP_XZ * (x + z); }
    }

    private void drawEmissions(GuiGraphicsExtractor graphics, ScreenRectangle canvas, TrailConfig c, EmitterView view,
                               long index, double cycle, CustomEffect flight, CustomEffect pickup,
                               double tailStart, boolean customTrail) {
        if (!view.equals(emitterView) || index != emitterCycle) {
            emitterView = view;
            emitterCycle = index;
            flightEmission = pickupEmission = null;
        }
        long now = Math.round(cycle * 1.0E9), collected = Math.round(TRAVEL_SECONDS * 1.0E9);
        if (flight != null && flight.emitter != null) {
            if (flightEmission == null || flightEmission.effect != flight) {
                flightEmission = EffectEmission.flight(flight, 1, 0, view.flight(0));
            }
            flightEmission.moveAlong(Math.min(collected, now), view::flight);
            drawParticles(graphics, canvas, c, view, flightEmission.sample(now), cycle);
        } else {
            flightEmission = null;
        }
        if (now < collected || !c.pickupFlash || pickup == null || pickup.emitter == null) {
            pickupEmission = null;
            return;
        }
        if (pickupEmission == null || pickupEmission.effect != pickup) {
            double duration = c.pickupFlashSeconds;
            pickupEmission = EffectEmission.pickup(pickup, 2, collected, view.flight(TRAVEL_SECONDS), duration,
                    pickup.reflow ? t -> {
                        double p = tailStart + (1 - tailStart) * TrailEffects.reflowFront(t / duration);
                        return view.source(p, customTrail ? crossingTime(p) : TRAVEL_SECONDS);
                    } : null);
        }
        drawParticles(graphics, canvas, c, view, pickupEmission.sample(now), cycle);
    }

    private void drawParticles(GuiGraphicsExtractor graphics, ScreenRectangle canvas, TrailConfig c, EmitterView view,
                               EffectEmission.Frame frame, double seconds) {
        if (frame.particles().isEmpty()) return;
        var effect = frame.effect();
        var vertices = new ArrayList<EffectVertex>();
        int tail = TrailEffects.colorAt(c, 0, seconds, effect.rainbowSaturation);
        int head = TrailEffects.colorAt(c, 1, seconds, effect.rainbowSaturation);
        double size = view.pixelsPerBlock * (frame.pickup() ? 0.24 * c.pickupFlashSize : TrailEffects.flightSize(c));
        double strength = c.effectStrength * (frame.pickup() ? c.pickupFlashStrength : 1);
        for (var particle : frame.particles()) {
            var p = particle.position();
            double x = view.left + EmitterView.right(p.x, p.z) * view.pixelsPerBlock;
            double y = view.centerY - EmitterView.up(p.x, p.y, p.z) * view.pixelsPerBlock;
            effect.drawParticle(particle, strength, c, tail, head, guiEffectSink(vertices, x, y, size));
        }
        submitEffect(graphics, canvas, vertices);
    }

    private record GuiEffect(Matrix3x2f pose, ScreenRectangle scissorArea, List<EffectVertex> vertices)
            implements GuiElementRenderState {
        @Override public RenderPipeline pipeline() { return RenderPipelines.GUI; }
        @Override public TextureSetup textureSetup() { return TextureSetup.noTexture(); }
        @Override public ScreenRectangle bounds() { return scissorArea; }

        @Override public void buildVertices(VertexConsumer out) {
            for (var vertex : vertices) out.addVertexWith2DPose(pose, vertex.x, vertex.y).setColor(vertex.color);
        }
    }

    private void drawEffectPieces(GuiGraphicsExtractor graphics, ScreenRectangle canvas, int x, int y, double progress,
                                  TrailConfig config, double seconds, CustomEffect file, double size, double strength) {
        if (file.emitter != null) return;
        var vertices = new ArrayList<EffectVertex>();
        int tail = TrailEffects.colorAt(config, 0, seconds, file.rainbowSaturation);
        int head = TrailEffects.colorAt(config, 1, seconds, file.rainbowSaturation);
        file.draw(progress, strength, config, tail, head, guiEffectSink(vertices, x, y, size));
        submitEffect(graphics, canvas, vertices);
    }

    private static TrailEffects.PieceSink guiEffectSink(List<EffectVertex> vertices, double x, double y, double size) {
        return (dx, dy, dz, worldAxes, color) -> {
            // A fixed oblique camera makes all three world-aligned planes visible in the preview.
            double right = worldAxes ? EmitterView.right(dx, dz) : dx;
            double up = worldAxes ? EmitterView.up(dx, dy, dz) : dy;
            vertices.add(new EffectVertex((float) (x + right * size), (float) (y - up * size), color));
        };
    }

    private static void submitEffect(GuiGraphicsExtractor graphics, ScreenRectangle canvas, List<EffectVertex> vertices) {
        if (vertices.isEmpty()) return;
        var pose = new Matrix3x2f(graphics.pose());
        net.minecraft.client.Minecraft.getInstance().gameRenderer.gameRenderState().guiRenderState.addGuiElement(
                new GuiEffect(pose, canvas.transformMaxBounds(pose), vertices));
    }

    private static List<TrailRenderer.Sample> spritePath(int left, int right, int centerY, int height,
                                                         double tail, double head, int samples) {
        var path = new ArrayList<TrailRenderer.Sample>(samples);
        for (int i = 0; i < samples; i++) {
            double p = tail + (head - tail) * i / (samples - 1);
            double crossed = crossingTime(p);
            var point = new Vec3(left + (right - left) * p, pathHeight(centerY, height, p, crossed), 0);
            path.add(new TrailRenderer.Sample(point, (long) (crossed * 1.0E9)));
        }
        return path;
    }

    private static int pathY(int centerY, int height, double p, double seconds) {
        return (int) Math.round(pathHeight(centerY, height, p, seconds));
    }

    private static double pathHeight(int centerY, int height, double p, double seconds) {
        double amplitude = Math.max(10.0, Math.min(34.0, height * 0.16));
        return centerY + Math.sin(p * 7.2 + seconds * 1.7) * amplitude
                + Math.sin(p * 14.0 - seconds * 0.8) * amplitude * 0.24;
    }

    private static double crossingTime(double p) {
        return TRAVEL_SECONDS * (0.5 - Math.sin(Math.asin(Math.clamp(1 - 2 * p, -1, 1)) / 3));
    }

    static int colorAt(TrailConfig c, double p, double seconds) {
        return TrailEffects.colorAt(c, p, seconds, 0.88F);
    }


    private static int clamp255(int value) { return Math.max(0, Math.min(255, value)); }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.gui.setScreen(parent);
    }

    @Override public boolean isPauseScreen() { return false; }
}

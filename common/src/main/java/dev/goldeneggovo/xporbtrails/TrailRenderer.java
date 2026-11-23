package dev.goldeneggovo.xporbtrails;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.goldeneggovo.xporbtrails.mixin.RenderPipelinesAccessor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

public final class TrailRenderer {
    private static final int CURVE_STEPS = 4;
    private static final int MAX_POINTS = 128;
    static final int MAX_TRAIL_STAMPS = 16;
    private static final double[][] CIRCLE_DIRECTIONS = circleDirections();
    // Only the render thread uses these buffers; adjacent rings are emitted immediately.
    private static final double[] RING_A = new double[32 * 3];
    private static final double[] RING_B = new double[32 * 3];
    // The geometry shared with the GUI can be evaluated without initializing the world pipelines.
    private static final class WorldBuffers {
        static final RenderPipeline ALPHA = createPipeline("xp_orb_trails_alpha",
                new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA));
        static final RenderPipeline ADDITIVE = createPipeline("xp_orb_trails_additive",
                new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE));
        static final StagedVertexBuffer BUFFER =
                new StagedVertexBuffer(() -> "XP Orb Trail Buffer", RenderType.SMALL_BUFFER_SIZE);
    }
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1, 1, 1, 1);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final Map<Integer, Trail> TRAILS = new LinkedHashMap<>();
    private static volatile List<RenderTrail> renderTrails = List.of();
    private static ClientLevel lastLevel;
    private static long effectTick;
    private static volatile EffectFrame advancedFrame = new EffectFrame();

    private TrailRenderer() { }

    public static synchronized void updateWorld(ClientLevel level) {
        if (!XpOrbTrailsClient.CONFIG.enabled) level = null;
        if (lastLevel != level) {
            TRAILS.clear();
            renderTrails = List.of();
            advancedFrame = new EffectFrame();
            SceneOptics.close();
            SceneEffects.clear();
            Workshop.clear();
            BodyPickupEffects.clear();
            lastLevel = level;
        }
    }

    static long effectTick() {
        return effectTick;
    }

    public static synchronized void tickEffects(ClientLevel level) {
        updateWorld(level);
        if (lastLevel == null) return;
        effectTick++;
        EventParticles.tick(level, effectTick);
    }

    public static synchronized void track(ExperienceOrb orb) {
        TrailConfig cfg = XpOrbTrailsClient.CONFIG;
        if (!(orb.level() instanceof ClientLevel level)) return;
        updateWorld(level);
        if (!cfg.enabled) return;

        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        if (orb.position().distanceToSqr(camera) > cfg.renderRange * cfg.renderRange) {
            TRAILS.remove(orb.getId());
            return;
        }

        trimToCap(cfg.trailCap);
        Trail trail = TRAILS.get(orb.getId());
        if (trail == null) {
            // Keep existing trails continuous instead of churning every orb at the limit.
            if (TRAILS.size() >= cfg.trailCap) return;
            trail = new Trail();
            TRAILS.put(orb.getId(), trail);
        }

        Vec3 position = orb.getPosition(1.0F);
        Vec3 point = trailPoint(orb, position, cfg);
        long now = System.nanoTime();
        if (cfg.advanced.size() != 0) {
            BodyPickupEffects.tick(orb);
            SceneEffects.track(orb);
            Workshop.track(orb);
        }
        trail.advancedState = AdvancedTrails.state(trail.advancedState, cfg);
        if (trail.advancedState != null)
            trail.advancedState.move(position.add(0, cfg.orbYOffset, 0), orb.getDeltaMovement().scale(20), now, orbSeed(orb));
        trail.lastSeen = now;
        trail.disappearedAt = 0L;
        trail.useSource(FixedStampHistory.Source.selected(cfg));
        // The next rendered frame interpolates from this tick's starting position.
        CustomEffect flight = XpOrbTrailsClient.effect(AssetRole.ORB, cfg.flightEffect);
        if (flight != null && flight.emitter != null) {
            trail.updateFlight(flight, orb, orb.getPosition(0).add(0, cfg.orbYOffset, 0), now);
        }
        if (trail.fixedSource != null) trail.recordStamps(orb, orb.getPosition(0), now, 0);
        trail.append(point, now, cfg.pointSpacing);
        trail.flow(cfg.smoothFlow);
    }

    public static synchronized boolean showsVanillaBody(ExperienceOrb orb) {
        TrailConfig cfg = XpOrbTrailsClient.CONFIG;
        if (!cfg.enabled) return true;
        // Entity rendering can precede the first tick of a newly spawned orb.
        if (orb.tickCount == 0 && (lastLevel != orb.level() || !TRAILS.containsKey(orb.getId()))
                && orb.level().getEntity(orb.getId()) == orb) track(orb);
        if (cfg.advanced.has("workshop") && PixelWorkshop.replacesVanilla(orb)) return false;
        if (cfg.showVanillaOrb) return true;
        if ("none".equals(cfg.flightEffect)) {
            if (!cfg.advanced.has("body")) return false;
            return !BodyPickupEffects.hasBody(cfg) || !TRAILS.containsKey(orb.getId());
        }
        trimToCap(cfg.trailCap);
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        boolean tracked = lastLevel == orb.level() && TRAILS.containsKey(orb.getId())
                && orb.position().distanceToSqr(camera) <= cfg.renderRange * cfg.renderRange;
        boolean artwork = tracked && (XpOrbTrailsClient.sprite(AssetRole.ORB, cfg.flightEffect) != null
                || XpOrbTrailsClient.effect(AssetRole.ORB, cfg.flightEffect) != null);
        return cfg.showsVanillaBody(artwork);
    }

    private static void trimToCap(int cap) {
        while (TRAILS.size() > cap) {
            Iterator<Integer> iterator = TRAILS.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    public static synchronized void confirmPickup(ExperienceOrb orb) {
        confirmPickup(orb, Minecraft.getInstance().player == null ? -1 : Minecraft.getInstance().player.getId());
    }

    public static synchronized void confirmPickup(ExperienceOrb orb, int collector) {
        TrailConfig cfg = XpOrbTrailsClient.CONFIG;
        if (!cfg.enabled || orb.level() != lastLevel) return;
        if (cfg.advanced.size() != 0 && (TRAILS.containsKey(orb.getId()) || TRAILS.size() < cfg.trailCap)
                && orb.position().distanceToSqr(Minecraft.getInstance().gameRenderer.mainCamera().position()) <= cfg.renderRange * cfg.renderRange) {
            SceneEffects.pickup(orb, collector);
            Workshop.pickup(orb, collector);
            if (cfg.pickupFlash) BodyPickupEffects.pickup(orb);
        }
        if (!cfg.pickupFlash) return;
        CustomEffect pickup = XpOrbTrailsClient.effect(AssetRole.PICKUP, cfg.pickupFlashStyle);
        Object pickupSource = pickup != null ? pickup : XpOrbTrailsClient.sprite(AssetRole.PICKUP, cfg.pickupFlashStyle);
        if (pickupSource == null) return;
        Trail trail = TRAILS.get(orb.getId());
        if (trail == null) {
            Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
            if (TRAILS.size() >= cfg.trailCap
                    || orb.position().distanceToSqr(camera) > cfg.renderRange * cfg.renderRange) return;
            trail = new Trail();
            TRAILS.put(orb.getId(), trail);
        }
        trail.pickupSource = pickupSource;
        trail.pickupAt = System.nanoTime();
        trail.pickupPosition = trailPoint(orb, orb.getPosition(1.0F), cfg);
        trail.pickupPath = null;
        trail.pickupMarks = List.of();
        if (pickup != null && pickup.reflow) {
            long oldest = trail.pickupAt - (long) (cfg.lifetimeSeconds * 1_000_000_000L);
            trail.useSource(FixedStampHistory.Source.selected(cfg));
            if (trail.fixedSource != null) trail.recordStamps(orb, orb.getPosition(1), trail.pickupAt, 1);
            trail.pickupMarks = trail.fixed.snapshot(oldest);
            trail.fixed.reset();
            trail.expireBefore(oldest);
            trail.append(trail.pickupPosition, trail.pickupAt, cfg.pointSpacing);
            if (trail.pickupMarks.isEmpty()) {
                trail.pickupPath = sampleCurve(trail.points, trail.pickupPosition, null);
            } else {
                trail.pickupPosition = orb.getPosition(1.0F).add(0, cfg.orbYOffset, 0);
                List<Sample> path = new ArrayList<>(trail.pickupMarks.size() + 1);
                for (var mark : trail.pickupMarks) path.add(new Sample(mark.position(), mark.time()));
                path.add(new Sample(trail.pickupPosition, trail.pickupAt));
                trail.pickupPath = path;
            }
            // A stacked orb can survive the pickup. Its next flight gets a fresh history.
            trail.points.clear();
            trail.cachedSamples = null;
        }
        if (pickup != null && pickup.emitter != null) {
            var path = trail.pickupPath;
            Vec3 origin = trail.pickupPosition;
            double seconds = cfg.pickupFlashSeconds;
            long seed = orbSeed(orb) ^ (++trail.pickupSequence * 0x9E3779B97F4A7C15L);
            trail.addPickup(EffectEmission.pickup(pickup, seed, trail.pickupAt, origin, seconds,
                    path == null ? null : t -> reflowPosition(path, origin, Math.min(1, t / seconds))));
        }
    }

    private static long orbSeed(ExperienceOrb orb) {
        var uuid = orb.getUUID();
        return uuid.getMostSignificantBits() ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 23);
    }

    static synchronized double bodySpriteAge(ExperienceOrb orb, long frameNanos) {
        var trail = TRAILS.get(orb.getId());
        return trail == null ? 0 : (frameNanos - trail.startedAt) / 1.0E9;
    }

    private static Vec3 trailPoint(ExperienceOrb orb, Vec3 position, TrailConfig cfg) {
        Minecraft client = Minecraft.getInstance();
        Vec3 point = position.add(0, cfg.orbYOffset, 0);
        Vec3 motion = orb.getDeltaMovement();
        double speed = motion.length();
        if (cfg.motionShift > 0.0 && speed > 0.045) {
            double t = TrailEffects.smooth((speed - 0.045) / 0.075);
            point = point.subtract(motion.scale(cfg.motionShift * t / speed));
        }
        if (cfg.cameraPush > 0.0 && client.gameRenderer != null) {
            Vec3 away = point.subtract(client.gameRenderer.mainCamera().position());
            double length = away.length();
            if (length > 1.0E-6) point = point.add(away.scale(cfg.cameraPush / length));
        }
        return point;
    }

    public static synchronized void extract(ClientLevel level, Vec3 camera, float partialTick) {
        long now = System.nanoTime();
        TrailConfig cfg = XpOrbTrailsClient.CONFIG;
        updateWorld(level);
        SceneOptics.extract(level, camera, partialTick, TRAILS.keySet());
        if (!cfg.enabled || level == null) return;
        EffectFrame frame = new EffectFrame();
        BodyPickupEffects.extract(frame, level, partialTick);
        SceneEffects.extract(frame, level, partialTick);
        Workshop.extract(frame, level, partialTick, now);
        trimToCap(cfg.trailCap);
        long lifetime = (long) (cfg.lifetimeSeconds * 1_000_000_000L);

        double rangeSq = cfg.renderRange * cfg.renderRange;
        var fixedSource = FixedStampHistory.Source.selected(cfg);
        boolean advancedTrail = AdvancedTrails.enabled(cfg);
        boolean needsCurve = advancedTrail || (!Workshop.replacesTrail() && fixedSource == null && !"body".equals(cfg.trailSprite));
        CustomEffect flight = XpOrbTrailsClient.effect(AssetRole.ORB, cfg.flightEffect);
        boolean drawFlight = flight != null
                || XpOrbTrailsClient.sprite(AssetRole.ORB, cfg.flightEffect) != null;
        SpriteSheet pickup = XpOrbTrailsClient.sprite(AssetRole.PICKUP, cfg.pickupFlashStyle);
        CustomEffect pickupEffect = XpOrbTrailsClient.effect(AssetRole.PICKUP, cfg.pickupFlashStyle);
        Object pickupSource = pickupEffect != null ? pickupEffect : pickup;
        double flashSeconds = pickup == null ? cfg.pickupFlashSeconds : pickup.durationMillis() / 1000.0;
        List<RenderTrail> snapshot = new ArrayList<>(TRAILS.size());

        partialTick = Math.max(0.0F, Math.min(1.0F, partialTick));
        Iterator<Map.Entry<Integer, Trail>> iterator = TRAILS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Trail> entry = iterator.next();
            Trail trail = entry.getValue();
            if (trail.pickupSource != null && (!cfg.pickupFlash || trail.pickupSource != pickupSource)) {
                trail.pickupSource = null;
                trail.pickupAt = 0L;
                trail.pickupPosition = null;
            }
            trail.useSource(fixedSource);
            ExperienceOrb liveOrb = level.getEntity(entry.getKey()) instanceof ExperienceOrb orb
                    && orb.isAlive() && !orb.isRemoved() ? orb : null;
            Vec3 position = liveOrb != null ? liveOrb.position()
                    : trail.points.isEmpty() ? trail.pickupPosition : trail.points.get(trail.points.size() - 1).position;
            if (position != null && position.distanceToSqr(camera) > rangeSq) {
                iterator.remove();
                continue;
            }
            trail.advancedState = AdvancedTrails.state(trail.advancedState, cfg);
            trail.expireAdvanced(now, lifetime, trail.startedAt, cfg);
            Vec3 orbPosition = liveOrb == null ? null : liveOrb.getPosition(partialTick);
            Vec3 livePoint = liveOrb == null ? null : trailPoint(liveOrb, orbPosition, cfg);
            if (liveOrb != null) {
                trail.disappearedAt = 0L;
                if (flight != null && flight.emitter != null) {
                    trail.updateFlight(flight, liveOrb, orbPosition.add(0, cfg.orbYOffset, 0), now);
                }
                if (fixedSource != null) trail.recordStamps(liveOrb, orbPosition, now, partialTick);
            } else if (trail.disappearedAt == 0L) {
                trail.disappearedAt = now;
            }
            var emissions = trail.emissions(now, flight, cfg.pickupFlash ? pickupEffect : null);
            boolean advancedParticles = trail.advancedState != null && trail.advancedState.alive(now);
            if (advancedParticles) trail.advancedState.appendParticles(frame, now, cfg.effectStrength);
            boolean pendingDebris = trail.advancedState != null && trail.advancedState.usesExpiry() && !trail.points.isEmpty();
            boolean keepParticles = pendingDebris || advancedParticles || !emissions.isEmpty() || !trail.pickups.isEmpty();
            long fadeNow = trail.disappearedAt == 0L ? now : trail.disappearedAt;
            double disappearanceFade = trail.disappearedAt == 0L ? 1.0
                    : 1.0 - TrailEffects.smooth((now - trail.disappearedAt)
                    / (cfg.pickupFadeSeconds * 1_000_000_000.0));
            double flashProgress = trail.pickupAt == 0L ? -1.0
                    : (now - trail.pickupAt) / (flashSeconds * 1_000_000_000.0);
            boolean flashVisible = cfg.pickupFlash && (pickup != null || pickupEffect != null) && flashProgress >= 0.0 && flashProgress < 1.0;
            if (trail.pickupPath != null) {
                if (flashVisible && pickupEffect != null && pickupEffect.reflow) {
                    snapshot.add(new RenderTrail(trail.pickupPath, trail.pickupMarks, fixedSource, trail.pickupAt, 1.0, flashProgress,
                            trail.pickupPosition, orbPosition, trail.startedAt, emissions, false));
                    continue;
                }
                trail.pickupPath = null;
                trail.pickupMarks = List.of();
                if (!flashVisible && liveOrb == null && !keepParticles) {
                    iterator.remove();
                    continue;
                }
            }
            if (!(drawFlight && liveOrb != null) && !flashVisible && !keepParticles
                    && ((trail.points.isEmpty() && now - trail.lastSeen > lifetime)
                    || disappearanceFade <= 0.001)) {
                iterator.remove();
                continue;
            }
            var marks = trail.fixed.snapshot(fadeNow - lifetime);
            if (trail.points.size() >= 2 || !marks.isEmpty() || flashVisible || !emissions.isEmpty() || (drawFlight && orbPosition != null)) {
                List<Sample> samples = needsCurve ? trail.samples(livePoint) : List.of();
                boolean replace = advancedTrail && AdvancedTrails.append(frame, samples, orbPosition, now,
                        trail.startedAt, disappearanceFade, cfg);
                snapshot.add(new RenderTrail(samples, marks, fixedSource, fadeNow, disappearanceFade, flashProgress, trail.pickupPosition,
                        orbPosition, trail.startedAt, emissions, replace));
            }
        }
        advancedFrame = frame;
        renderTrails = List.copyOf(snapshot);
    }

    public static void render(Vec3 camera) {
        if (!XpOrbTrailsClient.CONFIG.enabled) return;
        List<RenderTrail> snapshot = renderTrails;
        SceneOptics.render(camera);
        EffectFrame extra = advancedFrame;
        if (snapshot.isEmpty() && extra.size() == 0) return;

        TrailConfig cfg = XpOrbTrailsClient.CONFIG;
        RenderPipeline pipeline = cfg.additiveGlow ? WorldBuffers.ADDITIVE : WorldBuffers.ALPHA;
        StagedVertexBuffer buffer = WorldBuffers.BUFFER;
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        if (format == null) return;
        PrimitiveTopology topology = pipeline.getPrimitiveTopology();
        // Vertices are camera-relative, so vanilla's origin-distance sort orders all transparent quads.
        StagedVertexBuffer.Draw draw = buffer.appendDraw(format, topology, cfg.additiveGlow ? null : VertexSorting.DISTANCE_TO_ORIGIN);
        VertexConsumer vertices = buffer.getVertexBuilder(draw);

        long lifetime = (long) (cfg.lifetimeSeconds * 1_000_000_000L);
        CustomEffect flight = XpOrbTrailsClient.effect(AssetRole.ORB, cfg.flightEffect);
        CustomEffect pickup = XpOrbTrailsClient.effect(AssetRole.PICKUP, cfg.pickupFlashStyle);
        SpriteSheet trailSprite = XpOrbTrailsClient.sprite(AssetRole.TRAIL, cfg.trailSprite);
        SpriteSheet flightSprite = XpOrbTrailsClient.sprite(AssetRole.ORB, cfg.flightEffect);
        SpriteSheet pickupSprite = XpOrbTrailsClient.sprite(AssetRole.PICKUP, cfg.pickupFlashStyle);
        var fixedSource = FixedStampHistory.Source.selected(cfg);
        boolean drawSprites = extra.size() != 0 || trailSprite != null || flightSprite != null || pickupSprite != null
                || (fixedSource != null && fixedSource.vanilla());
        if (drawSprites) SpriteRenderer.begin(cfg.additiveGlow);
        extra.draw(vertices, camera);
        long now = System.nanoTime();
        double flightSeconds = now / 1.0E9;
        double flightProgress = TrailEffects.flightProgress(flightSeconds, cfg.pulseSpeed);
        boolean workshopTrail = Workshop.replacesTrail();
        boolean drawFlightSprite = flightSprite != null && !PixelWorkshop.replacesFlightSprite();
        for (RenderTrail trail : snapshot) {
            boolean flash = cfg.pickupFlash && (pickup != null || pickupSprite != null) && trail.flashProgress >= 0 && trail.flashProgress < 1
                    && trail.pickupPosition != null;
            double reflow = flash && pickup != null && pickup.reflow ? trail.flashProgress : -1;
            boolean pulse = cfg.flightPulse && trail.orbPosition != null && !flash;
            if (!trail.replacesTrail && !workshopTrail) {
                if ("body".equals(cfg.trailSprite) || fixedSource != null) {
                    if (fixedSource != null && fixedSource.equals(trail.fixedSource)) {
                        emitFixedStamps(trail.marks, trail.now, cfg, trail.opacity, reflow, pulse, (mark, scale, alpha) -> {
                            if (fixedSource.vanilla()) {
                                Vec3 origin = mark.position().add(0, 0.1 - fixedSource.yOffset(), 0);
                                SpriteRenderer.worldOrb(mark.icon(), FixedStampHistory.vanillaTint(mark.orbAge()),
                                        origin, camera, 0.30 * scale, alpha * (128.0 / 255));
                            }
                            if (fixedSource.sprite() != null) {
                                var sheet = fixedSource.sprite();
                                var frame = mark.spriteFrame(sheet, now, trail.startedAt, fixedSource.body(), fixedSource.vanilla());
                                if (frame != null) SpriteRenderer.world(sheet, frame, mark.position(), camera,
                                        (fixedSource.body() ? 2 * TrailEffects.flightSize(cfg) : 0.30) * scale, alpha);
                            } else if (fixedSource.vector() != null) {
                                double seconds = mark.time() / 1.0E9;
                                appendEffectPieces(vertices, mark.position(), camera, TrailEffects.flightProgress(seconds, cfg.pulseSpeed),
                                        cfg, seconds, fixedSource.vector(), TrailEffects.flightSize(cfg) * scale, alpha);
                            }
                        });
                    }
                } else if (trailSprite != null && "ribbon".equals(cfg.trailSpriteMode)) {
                    var frame = trailSprite.frameAt((now - trail.startedAt) / 1.0E9, trailSprite.loop());
                    if (frame != null && trail.samples.size() >= 2) {
                        SpriteRenderer.worldRibbon(trailSprite, frame, trail.samples, camera, trail.now, cfg,
                                trail.opacity, reflow, pulse);
                    }
                } else if (trailSprite != null) {
                    emitTrailStamps(trailSprite, trail.samples, trail.now, now, cfg, trail.opacity, reflow, pulse, 1,
                            (frame, position, size, alpha) -> SpriteRenderer.world(trailSprite, frame, position, camera, size, alpha));
                } else {
                    appendStyledTube(vertices, trail.samples, camera, trail.now, lifetime, cfg, cfg.effectStrength * trail.opacity,
                            reflow, pulse);
                }
            }
            if (drawFlightSprite && trail.orbPosition != null) {
                var frame = flightSprite.frameAt((now - trail.startedAt) / 1.0E9, !cfg.showVanillaOrb || flightSprite.loop());
                if (frame != null) SpriteRenderer.world(flightSprite, frame, trail.orbPosition.add(0, cfg.orbYOffset, 0),
                        camera, 2 * TrailEffects.flightSize(cfg), cfg.effectStrength);
            } else if (flight != null && trail.orbPosition != null) {
                appendEffectPieces(vertices, trail.orbPosition.add(0, cfg.orbYOffset, 0), camera,
                        flightProgress, cfg, flightSeconds, flight, TrailEffects.flightSize(cfg), cfg.effectStrength);
            }
            if (flash) {
                if (pickupSprite != null) {
                    var frame = pickupSprite.frameAt(trail.flashProgress * pickupSprite.durationMillis() / 1000.0, false);
                    if (frame != null) SpriteRenderer.world(pickupSprite, frame, trail.pickupPosition, camera,
                            cfg.pickupFlashSize, cfg.effectStrength * cfg.pickupFlashStrength);
                } else if (pickup != null) {
                    Vec3 center = reflow < 0 ? trail.pickupPosition
                            : reflowPosition(trail.samples, trail.pickupPosition, reflow);
                    appendEffectPieces(vertices, center, camera, trail.flashProgress, cfg, trail.now / 1.0E9,
                            pickup, 0.24 * cfg.pickupFlashSize, cfg.effectStrength * cfg.pickupFlashStrength);
                }
            }
            for (int i = 0; i < trail.emissions.size(); i++) {
                var emission = trail.emissions.get(i);
                double size = emission.pickup() ? 0.24 * cfg.pickupFlashSize : TrailEffects.flightSize(cfg);
                double strength = cfg.effectStrength * (emission.pickup() ? cfg.pickupFlashStrength : 1);
                var effect = emission.effect();
                int tail = TrailEffects.colorAt(cfg, 0, flightSeconds, effect.rainbowSaturation);
                int head = TrailEffects.colorAt(cfg, 1, flightSeconds, effect.rainbowSaturation);
                for (var particle : emission.particles()) {
                    effect.drawParticle(particle, strength, cfg, tail, head,
                            worldEffectSink(vertices, particle.position(), camera, size));
                }
            }
        }

        buffer.upload();
        StagedVertexBuffer.ExecuteInfo info = buffer.getExecuteInfo(draw);
        if (info != null) draw(info, pipeline);
        buffer.endFrame();
        if (drawSprites) SpriteRenderer.end();
    }

    interface StampSink {
        void stamp(SpriteSheet.Frame frame, Vec3 position, double size, double alpha);
    }

    interface FixedStampSink {
        void stamp(FixedStampHistory.Mark mark, double scale, double alpha);
    }

    static void emitFixedStamps(List<FixedStampHistory.Mark> marks, long fadeNow, TrailConfig cfg,
                                double fade, double reflow, boolean pulse, FixedStampSink sink) {
        double front = reflow >= 0 ? TrailEffects.reflowFront(reflow)
                : TrailEffects.pulseFront(fadeNow / 1.0E9, cfg.pulseSpeed);
        double bandWidth = reflow >= 0 ? TrailEffects.reflowBandWidth(cfg.pickupFlashSize) : 0.12;
        for (int i = 0; i < marks.size(); i++) {
            var mark = marks.get(i);
            // During reflow the final point is the pickup position, beyond the last crossing.
            double progress = reflow >= 0 ? i / (double) marks.size()
                    : marks.size() == 1 ? 1 : i / (double) (marks.size() - 1);
            if (reflow >= 0 && progress < front) continue;
            double life = 1 - TrailEffects.smooth((fadeNow - mark.time()) / (cfg.lifetimeSeconds * 1.0E9));
            if (life <= 0 || fade <= 0) continue;
            double light = reflow >= 0 || pulse ? TrailEffects.band(progress, front, bandWidth) : 0;
            sink.stamp(mark, stampSize(cfg, life, progress, light) / 0.30, stampOpacity(cfg, fade, life, progress, light));
        }
    }

    static void emitTrailStamps(SpriteSheet sheet, List<Sample> samples, long fadeNowNanos, long nowNanos,
                                TrailConfig cfg, double fade, double reflow, boolean pulse, double unitsPerBlock,
                                StampSink sink) {
        int last = samples.size() - 1;
        if (last < 1) return;
        double length = 0;
        for (int i = 1; i <= last; i++) length += samples.get(i - 1).position.distanceTo(samples.get(i).position);
        if (length < 0.006 * unitsPerBlock) return;
        int intervals = Math.clamp((int) Math.ceil(length / (stampSpacing(cfg) * unitsPerBlock)), 1, MAX_TRAIL_STAMPS - 1);
        double front = reflow >= 0 ? TrailEffects.reflowFront(reflow)
                : TrailEffects.pulseFront(fadeNowNanos / 1.0E9, cfg.pulseSpeed);
        double bandWidth = reflow >= 0 ? TrailEffects.reflowBandWidth(cfg.pickupFlashSize) : 0.12;
        int segment = 1;
        double start = 0, end = samples.get(0).position.distanceTo(samples.get(1).position);
        for (int i = 0; i <= intervals; i++) {
            double target = length * i / intervals;
            while (segment < last && end < target) {
                start = end;
                end += samples.get(segment).position.distanceTo(samples.get(segment + 1).position);
                segment++;
            }
            Sample a = samples.get(segment - 1), b = samples.get(segment);
            double t = end > start ? clamp((target - start) / (end - start)) : 0;
            // Reflow uses the original curve parameter, including on unevenly spaced samples.
            double progress = (segment - 1 + t) / last;
            if (reflow >= 0 && progress < front) continue;
            long time = a.time + (long) ((b.time - a.time) * t);
            double age = Math.max(0, (nowNanos - time) / 1.0E9);
            var frame = sheet.frameAt(age, sheet.loop());
            if (frame == null) continue;
            double life = 1 - TrailEffects.smooth((fadeNowNanos - time) / (cfg.lifetimeSeconds * 1.0E9));
            double light = reflow >= 0 || pulse ? TrailEffects.band(progress, front, bandWidth) : 0;
            double alpha = stampOpacity(cfg, fade, life, progress, light);
            sink.stamp(frame, a.position.lerp(b.position, t), unitsPerBlock * stampSize(cfg, life, progress, light), alpha);
        }
    }

    static double stampSpacing(TrailConfig cfg) {
        return 0.20 * cfg.stampSpacing;
    }

    static double stampSize(TrailConfig cfg, double life, double progress, double light) {
        return 0.30 * cfg.stampScale * widthScale(cfg, progress) * (0.65 + 0.35 * life) * (1 + 0.28 * light);
    }

    static double stampOpacity(TrailConfig cfg, double fade, double life, double progress, double light) {
        return cfg.effectStrength * fade * Math.pow(life, 0.72)
                * (0.18 + 0.82 * TrailEffects.smooth(progress * 1.25) + 0.70 * light);
    }

    static double widthScale(TrailConfig cfg, double p) {
        if (p < 0.5) {
            double t = TrailEffects.smooth(p * 2);
            return cfg.tailWidthScale + (cfg.middleWidthScale - cfg.tailWidthScale) * t;
        }
        return cfg.middleWidthScale + (cfg.headWidthScale - cfg.middleWidthScale) * TrailEffects.smooth((p - 0.5) * 2);
    }

    static void appendStyledTube(VertexConsumer out, List<Sample> samples, Vec3 camera,
                                 long now, long lifetime, TrailConfig config, double opacity,
                                 double reflowProgress, boolean flightPulse) {
        emitStyledTube((x, y, z, color) -> out.addVertex(x, y, z).setColor(color), samples, camera,
                now, lifetime, config, opacity, reflowProgress, flightPulse, RING_A, RING_B);
    }

    @FunctionalInterface
    interface TubeVertexSink {
        void vertex(float x, float y, float z, int color);
    }

    static void emitStyledTube(TubeVertexSink out, List<Sample> samples, Vec3 camera,
                               long now, long lifetime, TrailConfig config, double opacity,
                               double reflowProgress, boolean flightPulse, double[] previousRing, double[] ring) {
        if (samples.size() < 2) return;
        boolean custom = config.usesCustomCrossSection();
        int faces = custom ? config.crossSectionRadii.length : Math.clamp(config.crossSectionSides, 2, 32);
        int lastSample = samples.size() - 1;
        boolean solid = "solid".equals(config.colorMode);
        boolean rainbow = "rainbow".equals(config.colorMode);
        double seconds = now / 1_000_000_000.0;
        boolean reflow = reflowProgress >= 0.0;
        boolean extraColors = config.gradientStops.length > 0;
        double front = reflow ? TrailEffects.reflowFront(reflowProgress)
                : flightPulse ? TrailEffects.pulseFront(seconds, config.pulseSpeed) : 0;
        double bandWidth = reflow ? TrailEffects.reflowBandWidth(config.pickupFlashSize) : 0.12;
        int previousColor = 0;
        Vec3 normal = null, binormal = new Vec3(0, 0, 1);
        Vec3 previousRight = null;

        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            double progress = i / (double) lastSample;
            double age = clamp((now - sample.time) / (double) lifetime);
            double ageFade = Math.pow(1.0 - TrailEffects.smooth(age), 0.72);
            double headFade = TrailEffects.smooth(Math.min(1.0, progress * 1.25));
            double radius = config.width * 0.5 * widthScale(config, progress);
            int rgb;
            if (solid) {
                rgb = config.startColor;
            } else if (rainbow) {
                rgb = TrailEffects.hsvToRgb((float) ((progress * 0.72 + seconds * config.rainbowSpeed) % 1.0), 0.88F, 1.0F);
            } else if (extraColors) {
                rgb = config.gradientColor(progress);
            } else {
                rgb = mixColor(config.startColor, config.endColor, (float) progress);
            }
            int alpha = (int) (255.0 * opacity * ageFade * (0.18 + 0.82 * headFade));
            int color = (Math.max(0, Math.min(255, alpha)) << 24) | rgb;
            if (reflow || flightPulse) {
                double light = TrailEffects.band(progress, front, bandWidth);
                double visibility = reflow ? TrailEffects.reflowVisibility(progress, front) : 1;
                double strength = reflow ? config.pickupFlashStrength : 0.70;
                color = TrailEffects.bandColor(rgb, opacity * ageFade * (0.18 + 0.82 * headFade),
                        opacity * strength, light, visibility);
                radius *= 1 + 0.28 * light;
            }

            Vec3 direction = tangent(samples, i);
            if (faces == 2) {
                previousRight = billboardRight(direction, camera.subtract(sample.position), previousRight);
                Vec3 right = previousRight.scale(radius);
                ring[0] = sample.position.x + right.x - camera.x;
                ring[1] = sample.position.y + right.y - camera.y;
                ring[2] = sample.position.z + right.z - camera.z;
                ring[3] = sample.position.x - right.x - camera.x;
                ring[4] = sample.position.y - right.y - camera.y;
                ring[5] = sample.position.z - right.z - camera.z;
            } else {
                normal = normal == null ? initialNormal(direction)
                        : normalized(normal.subtract(direction.scale(normal.dot(direction))), initialNormal(direction));
                binormal = normalized(direction.cross(normal), binormal);
                double[] circle = CIRCLE_DIRECTIONS[faces];
                for (int face = 0; face < faces; face++) {
                    double vertexRadius = custom ? radius * config.crossSectionRadii[face] : radius;
                    double a = circle[face * 2] * vertexRadius, b = circle[face * 2 + 1] * vertexRadius;
                    int offset = face * 3;
                    ring[offset] = sample.position.x + normal.x * a + binormal.x * b - camera.x;
                    ring[offset + 1] = sample.position.y + normal.y * a + binormal.y * b - camera.y;
                    ring[offset + 2] = sample.position.z + normal.z * a + binormal.z * b - camera.z;
                }
            }
            if (i > 0 && (!reflow || progress > front)) {
                // Cut the crossing segment at the moving light even on a two-point path.
                double cut = reflow ? front * lastSample - (i - 1) : 0;
                if (cut > 0) {
                    for (int k = 0; k < faces * 3; k++) previousRing[k] += (ring[k] - previousRing[k]) * cut;
                    previousColor = color;
                }
                // A billboard is one double-sided quad, not two overlapping faces.
                for (int face = 0; face < (faces == 2 ? 1 : faces); face++) {
                    int next = (face + 1) % faces;
                    vertex(out, previousRing, face, previousColor);
                    vertex(out, ring, face, color);
                    vertex(out, ring, next, color);
                    vertex(out, previousRing, next, previousColor);
                }
            }
            double[] swap = previousRing; previousRing = ring; ring = swap;
            previousColor = color;
        }
    }

    private static double[][] circleDirections() {
        double[][] directions = new double[33][];
        for (int faces = 3; faces <= 32; faces++) {
            directions[faces] = new double[faces * 2];
            for (int face = 0; face < faces; face++) {
                double angle = Math.PI * 2.0 * face / faces;
                directions[faces][face * 2] = Math.cos(angle);
                directions[faces][face * 2 + 1] = Math.sin(angle);
            }
        }
        return directions;
    }

    private static void vertex(TubeVertexSink out, double[] ring, int face, int color) {
        int offset = face * 3;
        out.vertex((float) ring[offset], (float) ring[offset + 1], (float) ring[offset + 2], color);
    }

    static Vec3 billboardRight(Vec3 tangent, Vec3 view, Vec3 previous) {
        Vec3 fallback = previous == null ? initialNormal(tangent)
                : normalized(previous.subtract(tangent.scale(previous.dot(tangent))), initialNormal(tangent));
        Vec3 right = normalized(tangent.cross(view), fallback);
        return previous != null && right.dot(previous) < 0 ? right.scale(-1) : right;
    }

    private static Vec3 reflowPosition(List<Sample> samples, Vec3 fallback, double progress) {
        if (samples.isEmpty()) return fallback;
        double index = TrailEffects.reflowFront(progress) * (samples.size() - 1);
        int before = (int) index;
        if (before == samples.size() - 1) return samples.get(before).position;
        return samples.get(before).position.lerp(samples.get(before + 1).position, index - before);
    }

    private static void appendEffectPieces(VertexConsumer out, Vec3 center, Vec3 camera, double progress,
                                           TrailConfig config, double seconds, CustomEffect file, double size, double strength) {
        // Emitter layers describe a particle. Only the particle draw pass instantiates them.
        if (file.emitter != null) return;
        int tailColor = TrailEffects.colorAt(config, 0, seconds, file.rainbowSaturation);
        int headColor = TrailEffects.colorAt(config, 1, seconds, file.rainbowSaturation);
        file.draw(progress, strength, config, tailColor, headColor, worldEffectSink(out, center, camera, size));
    }

    private static TrailEffects.PieceSink worldEffectSink(VertexConsumer out, Vec3 center, Vec3 camera, double size) {
        Vec3 view = normalized(camera.subtract(center), new Vec3(0, 0, 1));
        Vec3 axis = Math.abs(view.y) < 0.92 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 right = axis.cross(view).normalize();
        Vec3 up = view.cross(right);
        Vec3 origin = center.subtract(camera);
        return (x, y, z, worldAxes, color) -> {
            double dx = worldAxes ? x : right.x * x + up.x * y;
            double dy = worldAxes ? y : right.y * x + up.y * y;
            double dz = worldAxes ? z : right.z * x + up.z * y;
            out.addVertex((float) (origin.x + dx * size), (float) (origin.y + dy * size),
                    (float) (origin.z + dz * size)).setColor(color);
        };
    }

    static Vec3 tangent(List<Sample> samples, int index) {
        Vec3 a = samples.get(Math.max(0, index - 1)).position;
        Vec3 b = samples.get(Math.min(samples.size() - 1, index + 1)).position;
        Vec3 result = b.subtract(a);
        if (result.lengthSqr() < 1.0E-8) {
            // At a reversal the centered difference cancels. Keep the incoming side at the turn.
            Vec3 center = samples.get(index).position;
            result = center.subtract(a);
            if (result.lengthSqr() < 1.0E-8) result = b.subtract(center);
        }
        return result.lengthSqr() < 1.0E-8 ? new Vec3(0, 1, 0) : result.normalize();
    }

    private static Vec3 initialNormal(Vec3 tangent) {
        Vec3 axis = Math.abs(tangent.y) < 0.92 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        return normalized(tangent.cross(axis), new Vec3(1, 0, 0));
    }

    private static Vec3 normalized(Vec3 value, Vec3 fallback) {
        double length = value.length();
        return length <= 1.0E-6 ? fallback : value.scale(1.0 / length);
    }

    private static double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }

    private static List<Sample> sampleCurve(List<Point> originalPoints, Vec3 livePoint, List<Sample> previous) {
        int lastIndex = originalPoints.size() - 1;
        Point last = originalPoints.get(lastIndex);
        if (livePoint != null) last = new Point(livePoint, last.time, last.birthDistance);
        if (previous != null && last.position.equals(previous.getLast().position)) return previous;
        List<Sample> result = new ArrayList<>(lastIndex * CURVE_STEPS + 1);
        // Only the final two Catmull-Rom segments depend on the interpolated head.
        int firstSegment = previous == null ? 0 : Math.max(0, lastIndex - 2);
        if (firstSegment > 0) result.addAll(previous.subList(0, firstSegment * CURVE_STEPS));
        for (int i = firstSegment; i < lastIndex; i++) {
            Point p0 = originalPoints.get(Math.max(0, i - 1));
            Point p1 = originalPoints.get(i);
            Point p2 = i + 1 == lastIndex ? last : originalPoints.get(i + 1);
            Point p3 = i + 2 >= lastIndex ? last : originalPoints.get(i + 2);
            for (int step = 0; step < CURVE_STEPS; step++) {
                double t = step / (double) CURVE_STEPS;
                result.add(new Sample(catmull(p0.position, p1.position, p2.position, p3.position, t),
                        (long) (p1.time + (p2.time - p1.time) * t),
                        p1.birthDistance + (p2.birthDistance - p1.birthDistance) * t, 1.0 / CURVE_STEPS));
            }
        }
        result.add(new Sample(last.position, last.time, last.birthDistance, 1.0 / CURVE_STEPS));
        return result;
    }

    private static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        return new Vec3(catmull(p0.x, p1.x, p2.x, p3.x, t),
                catmull(p0.y, p1.y, p2.y, p3.y, t),
                catmull(p0.z, p1.z, p2.z, p3.z, t));
    }

    private static double catmull(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2 * p1) + (-p0 + p2) * t
                + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    private static int mixColor(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static void draw(StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
        Minecraft client = Minecraft.getInstance();
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        GpuTextureView color = target.getColorTextureView();
        if (color == null) return;

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "XP Orb Trails", color, Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    private static RenderPipeline createPipeline(String name, BlendFunction blend) {
        return RenderPipelinesAccessor.xporbtrails$register(RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("xporbtrails", name))
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader(Identifier.withDefaultNamespace("core/position_color"))
                .withFragmentShader(Identifier.withDefaultNamespace("core/position_color"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                .withCull(false)
                .withColorTargetState(new ColorTargetState(blend))
                .build());
    }

    public static synchronized void close() {
        WorldBuffers.BUFFER.close();
        SpriteRenderer.close();
        SceneOptics.close();
        BodyPickupEffects.clear();
        SceneEffects.clear();
        Workshop.clear();
        advancedFrame = new EffectFrame();
        TRAILS.clear();
        renderTrails = List.of();
        lastLevel = null;
    }

    static final class Trail {
        AdvancedTrails.State advancedState;
        final List<Point> points = new ArrayList<>();
        private List<Sample> cachedSamples;
        final FixedStampHistory fixed = new FixedStampHistory();
        FixedStampHistory.Source fixedSource;
        List<FixedStampHistory.Mark> pickupMarks = List.of();
        final long startedAt = System.nanoTime();
        long lastSeen;
        long disappearedAt;
        long pickupAt;
        Object pickupSource;
        double tipDistance;
        Vec3 pickupPosition;
        List<Sample> pickupPath;
        EffectEmission flight;
        static final int MAX_PICKUPS = 8;
        List<EffectEmission> pickups = List.of();
        long pickupSequence;

        void updateFlight(CustomEffect effect, ExperienceOrb orb, Vec3 origin, long now) {
            if (flight == null || flight.effect != effect) {
                flight = EffectEmission.flight(effect, orbSeed(orb), now, origin);
            } else {
                flight.move(now, origin);
            }
        }

        void addPickup(EffectEmission event) {
            if (pickups.isEmpty()) pickups = new ArrayList<>(MAX_PICKUPS);
            if (pickups.size() == MAX_PICKUPS) pickups.removeFirst();
            pickups.add(event);
        }

        List<EffectEmission.Frame> emissions(long now, CustomEffect flightEffect, CustomEffect pickupEffect) {
            if (flight != null && flight.effect != flightEffect) flight = null;
            if (flight == null && pickups.isEmpty()) return List.of();
            var frames = new ArrayList<EffectEmission.Frame>();
            if (flight != null) {
                var frame = flight.sample(now);
                if (!frame.particles().isEmpty()) frames.add(frame);
            }
            // Stacked collections share one pickup geometry budget, newest events get first choice.
            int pickupStart = frames.size();
            int remaining = pickupEffect == null || pickupEffect.emitter == null ? 0 : pickupEffect.emitter.capacity();
            for (int i = pickups.size() - 1; i >= 0; i--) {
                var event = pickups.get(i);
                if (event.effect != pickupEffect || event.finishedPickup(now)) {
                    pickups.remove(i);
                } else if (remaining > 0) {
                    var frame = event.sample(now);
                    int count = frame.particles().size();
                    if (count == 0) continue;
                    if (count > remaining) {
                        frame = new EffectEmission.Frame(pickupEffect, true, frame.particles().subList(count - remaining, count));
                    }
                    frames.add(pickupStart, frame);
                    remaining -= frame.particles().size();
                }
            }
            return frames;
        }

        void useSource(FixedStampHistory.Source source) {
            if (Objects.equals(fixedSource, source)) return;
            fixedSource = source;
            fixed.reset();
            pickupMarks = List.of();
        }

        void recordStamps(ExperienceOrb orb, Vec3 position, long now, float partialTick) {
            fixed.append(position.add(0, fixedSource.yOffset(), 0), now, fixedSource.spacing(),
                    orb.tickCount + partialTick, orb.getIcon());
        }

        void expireBefore(long oldest) {
            int expired = 0;
            while (expired < points.size() && points.get(expired).time < oldest) expired++;
            if (expired > 0) {
                points.subList(0, expired).clear();
                cachedSamples = null;
            }
        }

        void expireAdvanced(long now, long lifetime, long seed, TrailConfig cfg) {
            if (advancedState != null && advancedState.usesExpiry()) {
                for (int i = 0; i < points.size(); i++) {
                    Point p = points.get(i);
                    if (p.time >= now - lifetime) break;
                    Vec3 tangent = i + 1 < points.size() ? points.get(i + 1).position.subtract(p.position)
                            : i > 0 ? p.position.subtract(points.get(i - 1).position) : Vec3.ZERO;
                    advancedState.expire(p.position, tangent, p.time + lifetime, now, seed, cfg);
                }
            }
            expireBefore(now - lifetime);
            if (points.isEmpty() && advancedState != null) advancedState.endPath();
        }

        List<Sample> samples(Vec3 livePoint) {
            if (points.size() < 2) return List.of();
            cachedSamples = sampleCurve(points, livePoint, cachedSamples);
            return cachedSamples;
        }

        void append(Vec3 position, long now, double spacing) {
            if (points.isEmpty()) {
                cachedSamples = null;
                tipDistance = 0;
                points.add(new Point(position, now, 0));
                return;
            }
            Point previous = points.get(points.size() - 1);
            double distance = previous.position.distanceTo(position);
            if (points.size() > 1) {
                Point before = points.get(points.size() - 2);
                if (before.position.distanceTo(position) < spacing) {
                    cachedSamples = null;
                    tipDistance += distance;
                    points.set(points.size() - 1, new Point(position, now, previous.birthDistance));
                    return;
                }
            }
            if (distance < 0.006) return;
            cachedSamples = null;
            int inserts = Math.min(8, Math.max(1, (int) Math.ceil(distance / spacing)));
            for (int i = 1; i <= inserts; i++) {
                double t = i / (double) inserts;
                points.add(new Point(previous.position.lerp(position, t),
                        (long) (previous.time + (now - previous.time) * t), tipDistance + distance * t));
            }
            tipDistance += distance;
            int excess = points.size() - MAX_POINTS;
            if (excess > 0) points.subList(0, excess).clear();
        }

        void flow(double strength) {
            cachedSamples = null;
            int lastPoint = points.size() - 1;
            double rate = 0.008 + strength * 0.055;
            for (int i = 0; i < lastPoint; i++) {
                Point point = points.get(i);
                Point next = points.get(i + 1);
                double trailFactor = 1.0 - i / (double) lastPoint;
                double amount = rate * (0.35 + trailFactor * 0.65);
                points.set(i, new Point(point.position.lerp(next.position, amount), point.time, point.birthDistance));
            }
        }
    }

    private record Point(Vec3 position, long time, double birthDistance) { }
    // Smoothing subdivides a retained segment without creating new texture repeats.
    record Sample(Vec3 position, long time, double birthDistance, double segmentStep) {
        Sample(Vec3 position, long time) { this(position, time, 0, 1); }
    }
    private record RenderTrail(List<Sample> samples, List<FixedStampHistory.Mark> marks, FixedStampHistory.Source fixedSource,
                               long now, double opacity, double flashProgress,
                               Vec3 pickupPosition, Vec3 orbPosition, long startedAt, List<EffectEmission.Frame> emissions, boolean replacesTrail) { }
}

package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BodyPickupEffects {
    private static final Map<Integer, Live> live = new LinkedHashMap<>();
    private static final List<Burst> bursts = new ArrayList<>();
    private static OrbEffectOptions body = OrbEffectOptions.EMPTY, pickup = OrbEffectOptions.EMPTY;

    private static final class Live {
        final ExperienceOrb orb;
        OrbBodyMotion motion;
        OrbPickupParticles drift;
        int lastTick = Integer.MIN_VALUE;

        Live(ExperienceOrb orb) { this.orb = orb; }
    }

    private static final class Burst {
        final OrbPickupParticles particles;
        final Vec3 location;
        long lastTick;

        Burst(OrbPickupParticles particles, Vec3 location, long tick) {
            this.particles = particles;
            this.location = location;
            lastTick = tick;
        }
    }

    private static OrbEffectOptions read(JsonObject advanced, String role, OrbEffectOptions current) {
        JsonElement section = advanced == null ? null : advanced.get(role);
        if (section == null || !section.isJsonObject()) return OrbEffectOptions.EMPTY;
        return section == current.values ? current : new OrbEffectOptions(section.getAsJsonObject());
    }

    private static void settings(TrailConfig config) {
        OrbEffectOptions nextBody = read(config.advanced, "body", body);
        OrbEffectOptions nextPickup = config.pickupFlash ? read(config.advanced, "pickup", pickup) : OrbEffectOptions.EMPTY;
        if (nextBody != body) live.values().forEach(state -> state.motion = null);
        if (nextPickup != pickup) {
            live.values().forEach(state -> state.drift = null);
            bursts.clear();
        }
        body = nextBody;
        pickup = nextPickup;
    }

    static boolean hasBody(TrailConfig config) {
        OrbEffectOptions o = read(config.advanced, "body", body);
        return switch (o.type) {
            case "firefly" -> XpOrbTrailsClient.sprite(AssetRole.ORB, o.text("sprite", "sprite:orb/atlas-firefly")) != null;
            case "bird" -> XpOrbTrailsClient.sprite(AssetRole.ORB, o.text("hoverSprite", "sprite:orb/atlas-bird-hover")) != null
                    && XpOrbTrailsClient.sprite(AssetRole.ORB, o.text("glideSprite", "sprite:orb/atlas-bird-glide")) != null;
            default -> OrbBodyMotion.supported(o.type);
        };
    }

    static void tick(ExperienceOrb orb) {
        TrailConfig config = XpOrbTrailsClient.CONFIG;
        settings(config);
        boolean tracksBody = OrbBodyMotion.supported(body.type), vortex = pickup.type.equals("vortex");
        if (!tracksBody && !vortex) return;
        Live state = live.get(orb.getId());
        if (state == null || state.orb != orb) {
            if (state == null && live.size() >= config.trailCap) return;
            state = new Live(orb);
            live.put(orb.getId(), state);
        }
        if (state.lastTick == orb.tickCount) return;
        Vec3 at = orb.position().add(0, config.orbYOffset, 0);
        long seed = orb.getUUID().getLeastSignificantBits() ^ orb.getUUID().getMostSignificantBits();
        if (tracksBody) {
            if (state.motion == null) state.motion = new OrbBodyMotion(body, at, seed);
            state.motion.step(at, orb.position().subtract(orb.getPosition(0)));
        }
        if (vortex) {
            if (state.drift == null) state.drift = new OrbPickupParticles(pickup, seed ^ orb.tickCount);
            state.drift.step(null);
            state.drift.emitDrift(at);
        }
        state.lastTick = orb.tickCount;
    }

    static void pickup(ExperienceOrb orb) {
        TrailConfig config = XpOrbTrailsClient.CONFIG;
        settings(config);
        if (!OrbPickupParticles.supported(pickup.type) || !(orb.level() instanceof ClientLevel level)) return;
        Vec3 at = orb.position().add(0, config.orbYOffset, 0);
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        if (camera.distanceToSqr(at) > config.renderRange * config.renderRange) return;
        long tick = TrailRenderer.effectTick();
        OrbPickupParticles particles;
        if (pickup.type.equals("vortex")) {
            Live state = live.get(orb.getId());
            if (state == null || state.orb != orb || state.drift == null) return;
            particles = state.drift;
            state.drift = null;
        } else {
            particles = new OrbPickupParticles(pickup, orb.getUUID().getLeastSignificantBits() ^ tick ^ bursts.size());
        }
        particles.start(at, terrain(level));
        if (particles.finished()) return;
        int cap = Math.min(24, config.trailCap);
        while (bursts.size() >= cap) bursts.removeFirst();
        bursts.add(new Burst(particles, at, tick));
    }

    private static OrbPickupParticles.Terrain terrain(ClientLevel level) {
        return (from, to) -> {
            var context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
            var hit = level.clip(context);
            return hit.getType() == HitResult.Type.BLOCK
                    ? new OrbPickupParticles.Surface(hit.getLocation(), hit.getDirection().getUnitVec3()) : null;
        };
    }

    static void extract(EffectFrame frame, ClientLevel level, float partialTick) {
        TrailConfig config = XpOrbTrailsClient.CONFIG;
        settings(config);
        if (live.isEmpty() && bursts.isEmpty()) return;
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        double range = config.renderRange * config.renderRange;
        float partial = Math.clamp(partialTick, 0, 1);
        var iterator = live.values().iterator();
        while (iterator.hasNext()) {
            Live state = iterator.next();
            ExperienceOrb orb = state.orb;
            if (orb.isRemoved() || !orb.isAlive() || orb != level.getEntity(orb.getId()) || live.size() > config.trailCap
                    || camera.distanceToSqr(orb.position()) > range || (state.motion == null && state.drift == null)) {
                iterator.remove();
                continue;
            }
            if (state.motion != null) state.motion.draw(frame, orb.getPosition(partial).add(0, config.orbYOffset, 0), partial);
            if (state.drift != null) state.drift.draw(frame, partial);
        }
        if (!bursts.isEmpty()) {
            var terrain = terrain(level);
            long now = TrailRenderer.effectTick();
            bursts.removeIf(burst -> {
                if (camera.distanceToSqr(burst.location) > range || now - burst.lastTick > 250) return true;
                while (burst.lastTick < now && !burst.particles.finished()) {
                    burst.particles.step(terrain);
                    burst.lastTick++;
                }
                if (burst.particles.finished()) return true;
                burst.particles.draw(frame, partial);
                return false;
            });
        }
    }

    static void clear() {
        live.clear();
        bursts.clear();
    }
}

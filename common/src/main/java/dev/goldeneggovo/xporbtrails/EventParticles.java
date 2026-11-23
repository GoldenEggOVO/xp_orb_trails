package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Random;
import java.util.Set;

final class EventParticles {
    static final int MAX_LIVE = 768, MAX_BIRTHS_PER_TICK = 128, MAX_DEPTH = 4;
    private static Engine ink, events;

    record Surface(Vec3 point, Vec3 normal, String material) {}

    interface Terrain {
        Surface cast(Vec3 start, Vec3 end, boolean fluids);
    }

    static final class Particle {
        final String name;
        final ParticleGraph.Emitter def;
        final double size;
        final int color, depth;
        Vec3 at, last, velocity, normal;
        long born;
        String material = "any";
        int hits, age;

        Particle(
                String name,
                ParticleGraph.Emitter def,
                Vec3 pos,
                Vec3 velocity,
                Vec3 normal,
                int color,
                double size,
                int depth) {
            this.name = name;
            this.def = def;
            last = at = pos;
            this.velocity = velocity;
            this.normal = normal;
            this.color = color;
            this.size = size;
            this.depth = depth;
        }
    }

    static final class Engine {
        final ParticleGraph graph;
        final ArrayList<Particle> live = new ArrayList<>();
        private long tick = Long.MIN_VALUE, budgetTick = Long.MIN_VALUE;
        private int births;
        private final Random random = new Random(0);

        Engine(ParticleGraph graph) {
            this.graph = graph;
        }

        private void budget(long now) {
            if (budgetTick != now) {
                budgetTick = now;
                births = 0;
            }
        }

        void emitRoot(Vec3 at, long now, long seed, Terrain terrain) {
            advance(now, terrain);
            budget(now);
            random.setSeed(seed);
            spawn(graph.root(), at, null, null, Set.of(), 0);
        }

        private void spawn(
                String name,
                Vec3 at,
                Vec3 normal,
                Particle parent,
                Set<ParticleGraph.Attribute> inherit,
                int depth) {
            var def = graph.emitters().get(name);
            if (depth > MAX_DEPTH || (def.decal() && normal == null)) return;
            if (def.decal() && !def.face().equals("any")) {
                String side = normal.y > .5 ? "floor" : normal.y < -.5 ? "ceiling" : "wall";
                if (!side.equals(def.face())) return;
            }
            for (int i = 0;
                    i < def.count() && births < MAX_BIRTHS_PER_TICK && live.size() < MAX_LIVE;
                    i++) {
                Vec3 v =
                        def.velocity()
                                .add(
                                        (random.nextDouble() * 2 - 1) * def.spread(),
                                        (random.nextDouble() * 2 - 1) * def.spread(),
                                        (random.nextDouble() * 2 - 1) * def.spread());
                if (parent != null && inherit.contains(ParticleGraph.Attribute.VELOCITY))
                    v = v.add(parent.velocity);
                int color =
                        parent != null && inherit.contains(ParticleGraph.Attribute.COLOR)
                                ? parent.color
                                : def.color();
                double size =
                        parent != null && inherit.contains(ParticleGraph.Attribute.SIZE)
                                ? parent.size
                                : def.size();
                var p = new Particle(name, def, at, v, normal, color, size, depth);
                p.born = budgetTick;
                if (parent != null) p.material = parent.material;
                live.add(p);
                births++;
                dispatch(ParticleGraph.Event.BIRTH, p);
            }
        }

        private void dispatch(ParticleGraph.Event event, Particle p) {
            var links = p.def.links().get(event);
            if (links == null || p.depth >= MAX_DEPTH) return;
            for (var link : links)
                if ((link.hit() == 0 || link.hit() == p.hits)
                        && (link.surface().equals("any") || link.surface().equals(p.material)))
                    spawn(link.emit(), p.at, p.normal, p, link.inherit(), p.depth + 1);
        }

        void advance(long now, Terrain terrain) {
            while (tick < now && !live.isEmpty()) {
                budget(++tick);
                // Children join at the collision point and get their first independent update next
                // tick.
                int original = live.size();
                for (int i = original - 1; i >= 0; i--) {
                    var p = live.get(i);
                    if (p.born >= tick) continue;
                    p.last = p.at;
                    p.age++;
                    boolean done = false;
                    if (!p.def.decal()) {
                        p.velocity = p.velocity.scale(p.def.drag()).add(0, -p.def.gravity(), 0);
                        Vec3 target = p.at.add(p.velocity);
                        Surface hit =
                                p.def.collide() && p.velocity.lengthSqr() > 1e-10
                                        ? terrain.cast(p.at, target, p.def.fluids())
                                        : null;
                        if (hit == null) {
                            p.at = target;
                            p.normal = null;
                            p.material = "any";
                        } else {
                            p.material = hit.material();
                            p.normal = hit.normal();
                            p.at = hit.point().add(p.normal.scale(.002));
                            p.hits++;
                            dispatch(ParticleGraph.Event.COLLISION, p);
                            double vn = p.velocity.dot(p.normal);
                            if (vn < 0)
                                p.velocity =
                                        p.velocity.subtract(
                                                p.normal.scale((1 + p.def.bounce()) * vn));
                            done = p.hits >= p.def.maxHits();
                        }
                    }
                    if (done || p.age >= p.def.life()) {
                        dispatch(ParticleGraph.Event.DEATH, p);
                        live.remove(i);
                    }
                }
            }
            tick = now;
        }

        void draw(EffectFrame out, Vec3 camera, double rangeSq, float partial) {
            for (var p : live) {
                if (p.at.distanceToSqr(camera) > rangeSq) continue;
                var at = p.last.lerp(p.at, partial);
                double remaining =
                        p.def.fadeTicks() == 0
                                ? 1
                                : Math.clamp(
                                        (p.def.life() - p.age - partial) / p.def.fadeTicks(), 0, 1);
                int color = OrbEffectOptions.fade(p.color, remaining);
                var sheet = sprite(p.def.sprite());
                if (p.def.decal()) {
                    if (p.def.ring()) {
                        Vec3 u = OrbEffectOptions.perpendicular(p.normal), v = p.normal.cross(u);
                        double radius = p.size * (.05 + .45 * (p.age + partial) / p.def.life());
                        Vec3 prev = at.add(u.scale(radius));
                        for (int i = 1; i <= 20; i++) {
                            Vec3 next =
                                    at.add(
                                            OrbEffectOptions.around(
                                                            u, v, i * OrbEffectOptions.TAU / 20)
                                                    .scale(radius));
                            out.line(prev, next, p.size * .025, color);
                            prev = next;
                        }
                    } else if (sheet != null)
                        out.decal(
                                sheet,
                                sheet.frameAt((p.age + partial) / 20., true),
                                at,
                                p.normal,
                                p.size,
                                color);
                    else {
                        Vec3 right = OrbEffectOptions.perpendicular(p.normal).scale(p.size * .5);
                        Vec3 up = p.normal.cross(right);
                        out.quad(
                                at.subtract(right).add(up),
                                at.subtract(right).subtract(up),
                                at.add(right).subtract(up),
                                at.add(right).add(up),
                                color);
                    }
                } else if (sheet != null)
                    out.sprite(sheet, at, p.size, (p.age + partial) / 20., color);
                else {
                    double half = p.size * .5;
                    out.line(at.add(0, -half, 0), at.add(0, half, 0), p.size * .65, color);
                    out.line(at.add(-half, 0, 0), at.add(half, 0, 0), p.size * .45, color);
                }
            }
        }
    }

    static void validate(JsonObject item) {
        ParticleGraph.read(item);
    }

    private static SpriteSheet sprite(String id) {
        if (id.isEmpty()) return null;
        if (AssetRole.PICKUP.accepts(id)) return XpOrbTrailsClient.sprite(AssetRole.PICKUP, id);
        if (AssetRole.ORB.accepts(id)) return XpOrbTrailsClient.sprite(AssetRole.ORB, id);
        if (AssetRole.TRAIL.accepts(id)) return XpOrbTrailsClient.sprite(AssetRole.TRAIL, id);
        return null;
    }

    private static void select() {
        if (ink == null) ink = load("ink");
        if (events == null) events = load("events");
    }

    private static Engine load(String key) {
        var o = Workshop.options(key);
        return o == null ? null : new Engine(ParticleGraph.read(o));
    }

    static void clear() {
        ink = events = null;
    }

    static void tick(ClientLevel level, long tick) {
        if (ink != null && !ink.live.isEmpty()) ink.advance(tick, terrain(level));
        if (events != null && !events.live.isEmpty()) events.advance(tick, terrain(level));
    }

    static void track(ExperienceOrb orb) {
        emit(orb, true);
    }

    static void pickup(ExperienceOrb orb) {
        if (XpOrbTrailsClient.CONFIG.pickupFlash) emit(orb, false);
    }

    private static void emit(ExperienceOrb orb, boolean flight) {
        select();
        emit(ink, orb, flight);
        emit(events, orb, flight);
    }

    private static void emit(Engine engine, ExperienceOrb orb, boolean flight) {
        if (engine == null || engine.graph.flight() != flight) return;
        long tick = TrailRenderer.effectTick();
        if (flight && Math.floorMod(tick + orb.getId(), engine.graph.interval()) != 0) return;
        engine.emitRoot(
                orb.position().add(0, XpOrbTrailsClient.CONFIG.orbYOffset, 0),
                tick,
                orb.getUUID().getLeastSignificantBits() ^ tick,
                terrain((ClientLevel) orb.level()));
    }

    static void extract(EffectFrame out, float partial) {
        select();
        draw(ink, out, partial);
        draw(events, out, partial);
    }

    private static Terrain terrain(ClientLevel level) {
        return (from, to, fluids) -> {
            var hit =
                    level.clip(
                            new ClipContext(
                                    from,
                                    to,
                                    ClipContext.Block.COLLIDER,
                                    ClipContext.Fluid.NONE,
                                    CollisionContext.empty()));
            if (fluids) {
                var wet =
                        level.clip(
                                new ClipContext(
                                        from,
                                        to,
                                        ClipContext.Block.COLLIDER,
                                        ClipContext.Fluid.ANY,
                                        CollisionContext.empty()));
                if (wet.getType() == HitResult.Type.BLOCK
                        && (hit.getType() != HitResult.Type.BLOCK
                                || from.distanceToSqr(wet.getLocation()) + 1e-8
                                        < from.distanceToSqr(hit.getLocation()))) {
                    var fluid = level.getFluidState(wet.getBlockPos());
                    String material =
                            fluid.is(net.minecraft.tags.FluidTags.WATER)
                                    ? "water"
                                    : fluid.is(net.minecraft.tags.FluidTags.LAVA)
                                            ? "lava"
                                            : "fluid";
                    return new Surface(
                            wet.getLocation(), wet.getDirection().getUnitVec3(), material);
                }
            }
            return hit.getType() == HitResult.Type.BLOCK
                    ? new Surface(hit.getLocation(), hit.getDirection().getUnitVec3(), "solid")
                    : null;
        };
    }

    private static void draw(Engine e, EffectFrame out, float partial) {
        if (e == null) return;
        var c = XpOrbTrailsClient.CONFIG;
        e.draw(
                out,
                Minecraft.getInstance().gameRenderer.mainCamera().position(),
                c.renderRange * c.renderRange,
                partial);
    }

    private EventParticles() {}
}

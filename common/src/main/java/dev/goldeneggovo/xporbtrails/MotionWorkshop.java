package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class MotionWorkshop {
    static final Set<String> TYPES = Set.of("rig", "brush", "morph", "hoops", "hand");
    private record Live(ExperienceOrb orb, MotionWorkshopPath path) {}
    private record Replay(MotionWorkshopPath path, double tick) {}
    private record Flight(int player, InteractionHand hand, Vec3 start, double tick) {}
    private static final Map<Integer, Live> live = new LinkedHashMap<>();
    private static final ArrayList<Replay> replays = new ArrayList<>();
    private static final ArrayList<Flight> flights = new ArrayList<>();
    private static final Map<String, OrbEffectOptions> options = new HashMap<>();
    private static MotionWorkshopRig rig;

    static void validate(JsonObject item) {
        String type = AdvancedSettings.requireType(item, TYPES, "workshop");
        switch (type) {
            case "rig" -> new MotionWorkshopRig(item);
            case "hoops" -> {
                var order = item.get("order");
                if (order != null && order.isJsonArray()) {
                    var numbers = MotionWorkshopRig.array(order, 1, 48, "hoops.order");
                    var seen = new java.util.HashSet<Integer>();
                    for (var value : numbers) {
                        double n = MotionWorkshopRig.scalar(value, 48);
                        if (n != (int)n || n >= 48 || !seen.add((int)n))
                            throw new IllegalArgumentException("Ring order requires unique integer indices from -48 to 47");
                    }
                } else if (order != null && (!order.isJsonPrimitive() || !Set.of("reverse", "forward").contains(order.getAsString())))
                    throw new IllegalArgumentException("Unknown ring replay order");
                String sprite = new OrbEffectOptions(item).text("sprite", "none");
                if (!sprite.equals("none") && !(SpriteLibrary.isSpriteId(sprite) && AssetRole.TRAIL.accepts(sprite)))
                    throw new IllegalArgumentException("Expected a trail sprite for hoops");
            }
            case "hand" -> {
                MotionWorkshopRig.vector(item, "mainPoint", Vec3.ZERO);
                MotionWorkshopRig.vector(item, "offPoint", Vec3.ZERO);
                if (!Set.of("auto", "main", "off").contains(new OrbEffectOptions(item).text("targetHand", "auto")))
                    throw new IllegalArgumentException("targetHand must be auto, main or off");
            }
        }
    }

    static OrbEffectOptions options(String key) {
        var v = Workshop.options(key);
        if (v == null) return OrbEffectOptions.EMPTY;
        OrbEffectOptions o = options.get(key);
        if (o == null || o.values != v) {
            o = new OrbEffectOptions(v);
            options.put(key, o);
        }
        return o;
    }

    static void clear() {
        live.clear();
        replays.clear();
        flights.clear();
        options.clear();
        MotionWorkshopHand.clear();
        rig = null;
    }

    static void track(ExperienceOrb orb) {
        var pen = options("brush");
        var hoops = options("hoops");
        if (pen == OrbEffectOptions.EMPTY && hoops == OrbEffectOptions.EMPTY
                && Workshop.options("rig") == null && Workshop.options("morph") == null) return;
        var cfg = XpOrbTrailsClient.CONFIG;
        var state = live.get(orb.getId());
        if (state == null || state.orb != orb) {
            if (state == null && live.size() >= cfg.trailCap) return;
            state = new Live(orb, new MotionWorkshopPath());
            live.put(orb.getId(), state);
        }
        state.path.sample(orb.position().add(0, cfg.orbYOffset, 0), TrailRenderer.effectTick(), pen, hoops, cfg.lifetimeSeconds * 20);
    }

    static void pickup(ExperienceOrb orb, int collector) {
        var cfg = XpOrbTrailsClient.CONFIG;
        double now = TrailRenderer.effectTick();
        Vec3 start = orb.position().add(0, cfg.orbYOffset, 0);
        var state = live.remove(orb.getId());
        if (Workshop.options("hoops") != null && state != null && state.orb == orb && !state.path.rings.isEmpty()) {
            state.path.sample(start, now, options("brush"), options("hoops"), cfg.lifetimeSeconds * 20);
            if (replays.size() >= cfg.trailCap) replays.removeFirst();
            replays.add(new Replay(state.path, now));
        }
        if (!cfg.pickupFlash || !(orb.level().getEntity(collector) instanceof Player player)) return;
        var hand = options("hand");
        if (hand != OrbEffectOptions.EMPTY) {
            if (flights.size() >= cfg.trailCap) {
                var first = flights.removeFirst();
                release(first);
            }
            flights.add(new Flight(collector, MotionWorkshopHand.select(player, hand), start, now));
        }
    }

    static boolean following(int entity, InteractionHand hand) {
        for (var flight : flights) if (flight.player == entity && flight.hand == hand) return true;
        return false;
    }

    private static void release(Flight f) {
        if (!following(f.player, f.hand)) MotionWorkshopHand.release(f.player, f.hand);
    }

    static void extract(EffectFrame out, ClientLevel level, float partial) {
        var cfg = XpOrbTrailsClient.CONFIG;
        var rigOptions = options("rig");
        if (rigOptions != OrbEffectOptions.EMPTY && rig == null) rig = new MotionWorkshopRig(rigOptions.values);
        var pen = options("brush");
        var morph = options("morph");
        var hoops = options("hoops");
        double now = TrailRenderer.effectTick() + partial;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        double range = cfg.renderRange * cfg.renderRange;
        for (var it = live.values().iterator(); it.hasNext();) {
            Live s = it.next();
            if (s.orb.isRemoved() || level.getEntity(s.orb.getId()) != s.orb || camera.distanceToSqr(s.orb.position()) > range) {
                it.remove();
                continue;
            }
            Vec3 center = s.orb.getPosition(partial).add(0, cfg.orbYOffset, 0);
            if (rig != null) {
                Vec3 heading = s.path.nodes.getLast().tangent();
                rig.draw(out, center, heading, s.orb.tickCount + partial + (s.orb.getId() & 15),
                        cfg.orbScale * rigOptions.number("scale", 1, .1, 4));
            }
            if (pen != OrbEffectOptions.EMPTY) s.path.drawBrush(out, pen, cfg, now, center);
            if (morph != OrbEffectOptions.EMPTY) s.path.drawMorph(out, morph, cfg, now, center);
            if (hoops != OrbEffectOptions.EMPTY) s.path.drawHoops(out, hoops, -1);
        }
        for (var it = replays.iterator(); it.hasNext();) {
            var replay = it.next();
            double elapsed = now - replay.tick;
            if (elapsed >= hoops.number("replayTicks", 20, 2, 200) + hoops.number("expandTicks", 8, 2, 80)) it.remove();
            else if (camera.distanceToSqr(replay.path.nodes.getLast().p()) <= range) replay.path.drawHoops(out, hoops, elapsed);
        }
        var hand = options("hand");
        for (Iterator<Flight> it = flights.iterator(); it.hasNext();) {
            var f = it.next();
            double u = (now - f.tick) / hand.number("durationTicks", 18, 2, 200);
            if (!cfg.pickupFlash || u >= 1 || !(level.getEntity(f.player) instanceof Player player)) {
                it.remove();
                release(f);
            } else {
                Vec3 target = MotionWorkshopHand.target(player, f.hand, partial);
                if (camera.distanceToSqr(target) <= range) drawHand(out, f.start, target, u, hand);
            }
        }
    }

    static void drawHand(EffectFrame out, Vec3 start, Vec3 target, double u, OrbEffectOptions o) {
        int color = o.color("color", 0xffffd27e);
        double height = o.number("arcHeight", .65, -4, 4), width = o.number("width", .032, .003, .3);
        Vec3 prev = handPoint(start, target, Math.max(0, u - .28), height);
        for (int i = 1; i <= 8; i++) {
            Vec3 p = handPoint(start, target, Math.max(0, u - .28 + .28 * i / 8), height);
            out.line(prev, p, width * i / 8, OrbEffectOptions.fade(color, i / 8.0));
            prev = p;
        }
        spark(out, prev, width * 1.9, color);
    }

    static Vec3 handPoint(Vec3 start, Vec3 target, double u, double height) {
        return start.lerp(target, u * u * (3 - 2 * u)).add(0, Math.sin(Math.PI * u) * height, 0);
    }

    static void spark(EffectFrame out, Vec3 p, double size, int color) {
        out.line(p.add(-size, 0, 0), p.add(size, 0, 0), size * .55, color);
        out.line(p.add(0, -size, 0), p.add(0, size, 0), size * .55, color);
        out.line(p.add(0, 0, -size), p.add(0, 0, size), size * .55, color);
    }

    private MotionWorkshop() {}
}

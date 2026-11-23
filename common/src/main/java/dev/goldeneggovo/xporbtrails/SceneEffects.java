package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

final class SceneEffects {
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final int MAX_EVENTS = 384;
    private static final LinkedHashMap<Integer, Node> nodes = new LinkedHashMap<>();
    private static final ArrayDeque<Event> events = new ArrayDeque<>();
    private static JsonObject selected;
    private static List<SceneRule> rules = List.of();
    private static int lastLevel = -1, combo;
    private static long lastPickup = Long.MIN_VALUE, lastSound = Long.MIN_VALUE;
    private static double probeRange;
    private static SoundManager soundManager;
    private static final SoundEventListener LISTENER = (sound, event, distance) -> onSound(sound);

    private record Mark(Vec3 pos, int color) {}

    private static final class Node {
        Vec3 pos, previous, velocity = Vec3.ZERO;
        long tick, nextTurn;
        boolean water;
        int value, terrainTint;
        double smoothedSpeed, rainExposure, rainEmission, dustEmission;
        final ArrayDeque<Mark> history = new ArrayDeque<>();

        Node(Vec3 pos) {
            this.pos = this.previous = pos;
        }

        Vec3 at(float partial) {
            return previous.lerp(pos, partial);
        }
    }

    private record Event(
            String kind,
            Vec3 pos,
            Vec3 vector,
            int color,
            double start,
            double life,
            double size,
            long seed) {}

    static void clear() {
        selected = null;
        rules = List.of();
        nodes.clear();
        events.clear();
        lastLevel = -1;
        combo = 0;
        lastPickup = lastSound = Long.MIN_VALUE;
        if (soundManager != null) {
            soundManager.removeListener(LISTENER);
            soundManager = null;
        }
    }

    private static void select() {
        JsonObject next = XpOrbTrailsClient.CONFIG.advanced;
        if (next == selected) return;
        clear();
        selected = next;
        if (next != null) rules = SceneRule.parse(next.get("scene"));
        probeRange =
                rules.stream()
                        .filter(r -> r.type().equals("groundcolor") || r.type().equals("dust"))
                        .mapToDouble(SceneRule::range)
                        .max()
                        .orElse(0);
        if (rules.stream().anyMatch(r -> r.type().equals("beat"))) {
            soundManager = Minecraft.getInstance().getSoundManager();
            soundManager.addListener(LISTENER);
        }
    }

    private static void onSound(SoundInstance sound) {
        Minecraft mc = Minecraft.getInstance();
        String id = sound.getIdentifier().toString();
        Vec3 pos = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        boolean relative = sound.isRelative();
        ClientLevel owner = mc.level;
        mc.execute(
                () -> {
                    if (owner == null
                            || mc.level != owner
                            || mc.player == null
                            || !XpOrbTrailsClient.CONFIG.enabled) return;
                    long tick = TrailRenderer.effectTick();
                    for (SceneRule r : rules)
                        if (r.type().equals("beat")
                                && r.sound().equals(id)
                                && (lastSound == Long.MIN_VALUE
                                        || tick - lastSound >= Math.max(1, r.threshold() * 20))) {
                            Vec3 origin = relative ? mc.player.position() : pos;
                            for (Node n : nodes.values())
                                if (n.pos.distanceToSqr(origin) <= r.range() * r.range())
                                    emit(
                                            "ring",
                                            n.pos,
                                            UP,
                                            r.primary(),
                                            tick,
                                            r.duration(),
                                            r.radius(),
                                            0);
                            lastSound = tick;
                        }
                });
    }

    static void track(ExperienceOrb orb) {
        select();
        if (rules.isEmpty()) return;
        ClientLevel level = (ClientLevel) orb.level();
        long tick = TrailRenderer.effectTick();
        Vec3 pos = orb.position().add(0, XpOrbTrailsClient.CONFIG.orbYOffset, 0);
        Node n = nodes.get(orb.getId());
        boolean first = n == null;
        if (first) {
            if (nodes.size() >= XpOrbTrailsClient.CONFIG.trailCap) return;
            n = new Node(pos);
            nodes.put(orb.getId(), n);
        }
        if (!first && tick == n.tick) return;
        n.previous = n.pos;
        n.pos = pos;
        n.tick = tick;
        n.value = orb.getValue();
        Vec3 old = n.velocity;
        n.velocity = pos.subtract(n.previous);
        boolean water = orb.isInWater();
        int terrainTint = 0, speedTint = 0;
        double groundDistance = Double.NaN;
        BlockPos ground = null;
        for (SceneRule r : rules) {
            switch (r.type()) {
                case "turn" -> {
                    if (tick >= n.nextTurn
                            && old.lengthSqr() > .0001
                            && n.velocity.lengthSqr() > .0001) {
                        double angle =
                                Math.toDegrees(
                                        Math.acos(
                                                Math.clamp(
                                                        old.normalize().dot(n.velocity.normalize()),
                                                        -1,
                                                        1)));
                        if (angle >= r.threshold()) {
                            emit(
                                    "crescent",
                                    pos,
                                    old.subtract(n.velocity).normalize(),
                                    r.primary(),
                                    tick,
                                    r.duration(),
                                    r.radius(),
                                    orb.getId());
                            n.nextTurn = tick + 4;
                        }
                    }
                }
                case "speed" -> {
                    n.smoothedSpeed += (n.velocity.length() - n.smoothedSpeed) * .5;
                    speedTint =
                            SceneRule.mix(
                                    r.primary(), r.secondary(), n.smoothedSpeed / r.threshold());
                }
                case "groundcolor", "dust" -> {
                    if (Double.isNaN(groundDistance)) {
                        var hit =
                                level.clip(
                                        new ClipContext(
                                                pos,
                                                pos.add(0, -probeRange, 0),
                                                ClipContext.Block.COLLIDER,
                                                ClipContext.Fluid.NONE,
                                                orb));
                        groundDistance =
                                hit.getType() == HitResult.Type.MISS
                                        ? Double.POSITIVE_INFINITY
                                        : pos.distanceTo(hit.getLocation());
                        if (Double.isFinite(groundDistance)) ground = hit.getBlockPos();
                    }
                    if (r.type().equals("groundcolor")) {
                        int sample =
                                ground == null || groundDistance > r.range()
                                        ? 0
                                        : level.getBlockState(ground)
                                                .getMapColor(level, ground)
                                                .col;
                        int target = sample == 0 ? r.primary() : sample | 0xff000000;
                        terrainTint =
                                n.terrainTint == 0
                                        ? target
                                        : SceneRule.mix(n.terrainTint, target, .4);
                        n.terrainTint = terrainTint;
                    } else if (ground != null
                            && groundDistance <= r.threshold()
                            && n.velocity.lengthSqr() > .0001) {
                        int sample = level.getBlockState(ground).getMapColor(level, ground).col;
                        n.dustEmission += 1 - groundDistance / r.threshold();
                        if (n.dustEmission >= 1) {
                            n.dustEmission -= 1;
                            scatter(
                                    pos.add(0, -groundDistance + .025, 0),
                                    sample == 0 ? r.primary() : sample | 0xff000000,
                                    tick,
                                    r,
                                    orb.getId() + tick,
                                    "dust");
                        }
                    }
                }
                case "underwater" -> {
                    if (water && tick % 3 == 0)
                        emit(
                                "bubble",
                                pos,
                                new Vec3(0, .035, 0),
                                r.primary(),
                                tick,
                                r.duration(),
                                r.width() * 2,
                                tick + orb.getId());
                    if (!first && water != n.water)
                        emit(
                                "flat_ring",
                                pos,
                                UP,
                                r.secondary(),
                                tick,
                                r.duration(),
                                r.radius(),
                                0);
                }
                case "rain" -> {
                    double exposure =
                            level.isRainingAt(orb.blockPosition()) ? level.getRainLevel(1) : 0;
                    n.rainExposure += (exposure - n.rainExposure) * .35;
                    n.rainEmission += n.rainExposure * .5;
                    if (n.rainEmission >= 1) {
                        n.rainEmission -= 1;
                        emit(
                                "drop",
                                pos,
                                new Vec3(-.012, -.055, 0),
                                r.primary(),
                                tick,
                                r.duration(),
                                r.width(),
                                orb.getId() + tick);
                    }
                }
                case "biome" -> {
                    if (tick % 3 == 0) {
                        var biome = level.getBiome(orb.blockPosition());
                        String id =
                                biome.unwrapKey().map(k -> k.identifier().toString()).orElse("");
                        var choice = r.biomes().get(id);
                        if (choice == null)
                            choice =
                                    biome.tags()
                                            .map(tag -> r.biomes().get("#" + tag.location()))
                                            .filter(java.util.Objects::nonNull)
                                            .findFirst()
                                            .orElse(null);
                        if (choice == null) choice = r.biomes().get("*");
                        String kind = "spark";
                        int color = r.primary();
                        if (choice != null && choice.isJsonObject()) {
                            var variant = new OrbEffectOptions(choice.getAsJsonObject());
                            kind = variant.text("shape", kind);
                            color = variant.color("color", color);
                        }
                        scatter(pos, color, tick, r, orb.getId() + tick, kind);
                    }
                }
                default -> {}
            }
        }
        int tint =
                terrainTint == 0
                        ? speedTint
                        : speedTint == 0 ? terrainTint : SceneRule.mix(terrainTint, speedTint, .5);
        if (tint != 0) {
            n.history.addLast(new Mark(pos, tint));
            while (n.history.size() > 14) n.history.removeFirst();
        }
        n.water = water;
    }

    private static void scatter(
            Vec3 pos, int color, long tick, SceneRule r, long seed, String shape) {
        var random = new java.util.Random(seed);
        for (int i = 0; i < Math.min(4, r.count()); i++) {
            var velocity =
                    new Vec3(
                            (random.nextDouble() - .5) * .045,
                            random.nextDouble() * .034,
                            (random.nextDouble() - .5) * .045);
            emit(
                    shape,
                    pos,
                    velocity,
                    color,
                    tick,
                    r.duration(),
                    r.width() * (1 + random.nextDouble()),
                    seed + i);
        }
    }

    static void pickup(ExperienceOrb orb, int collector) {
        select();
        if (rules.isEmpty()) return;
        var mc = Minecraft.getInstance();
        long tick = TrailRenderer.effectTick();
        Vec3 p = orb.position();
        boolean own = mc.player != null && mc.player.getId() == collector;
        for (SceneRule r : rules)
            switch (r.type()) {
                case "combo" -> {
                    if (!own) break;
                    combo =
                            lastPickup != Long.MIN_VALUE && tick - lastPickup <= r.threshold() * 20
                                    ? Math.min(r.count(), combo + 1)
                                    : 1;
                    lastPickup = tick;
                    emit(
                            "ring",
                            p,
                            UP,
                            r.primary(),
                            tick,
                            r.duration(),
                            r.radius() * (1 + .35 * (combo - 1)),
                            0);
                }
                case "chain" -> {
                    var seen = new HashSet<Integer>();
                    seen.add(orb.getId());
                    Vec3 from = p;
                    for (int i = 0; i < r.count(); i++) {
                        Node best = null;
                        int id = 0;
                        double radius = r.range() * r.range();
                        for (var e : nodes.entrySet()) {
                            if (seen.contains(e.getKey()) || tick - e.getValue().tick > 2) continue;
                            double d = e.getValue().pos.distanceToSqr(from);
                            if (d < radius) {
                                radius = d;
                                best = e.getValue();
                                id = e.getKey();
                            }
                        }
                        if (best == null) break;
                        double hop = (double) i / r.count();
                        int color =
                                SceneRule.mix(
                                        SceneRule.mix(r.primary(), r.secondary(), hop),
                                        0xff000000,
                                        .45 * hop);
                        emit(
                                "arc",
                                from,
                                best.pos,
                                color,
                                tick + 2 * i,
                                r.duration(),
                                r.width(),
                                i);
                        seen.add(id);
                        from = best.pos;
                    }
                }
                case "capture" -> {
                    if (own)
                        emit(
                                "orbit",
                                p,
                                Vec3.ZERO,
                                r.primary(),
                                tick,
                                r.duration(),
                                r.radius(),
                                orb.getId());
                }
                default -> {}
            }
    }

    private static void emit(
            String kind,
            Vec3 pos,
            Vec3 vector,
            int color,
            double tick,
            double seconds,
            double size,
            long seed) {
        if (events.size() >= MAX_EVENTS) events.removeFirst();
        events.addLast(new Event(kind, pos, vector, color, tick, seconds * 20, size, seed));
    }

    static void extract(EffectFrame out, ClientLevel level, float partial) {
        select();
        if (rules.isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long tick = TrailRenderer.effectTick();
        double now = tick + partial;
        nodes.entrySet()
                .removeIf(
                        e ->
                                tick - e.getValue().tick > 2
                                        || !(level.getEntity(e.getKey()) instanceof ExperienceOrb));
        Vec3 player = mc.player.getPosition(partial).add(0, .75, 0);
        Vec3 camera = mc.gameRenderer.mainCamera().position();
        double max = XpOrbTrailsClient.CONFIG.renderRange;
        max *= max;
        var active = new ArrayList<Node>();
        for (Node node : nodes.values())
            if (node.pos.distanceToSqr(camera) <= max) active.add(node);
        boolean history = false;
        for (SceneRule r : rules) {
            if (r.type().equals("level")) {
                if (lastLevel >= 0 && mc.player.experienceLevel > lastLevel) {
                    for (int i = 0; i < r.count(); i++) {
                        double a = i * 2 * Math.PI / r.count();
                        emit(
                                "celebrate",
                                player,
                                new Vec3(
                                        Math.cos(a) * .065,
                                        .085 + .025 * Math.cos(3 * a),
                                        Math.sin(a) * .065),
                                i % 2 == 0 ? r.primary() : r.secondary(),
                                tick,
                                r.duration(),
                                r.width(),
                                i);
                    }
                }
                lastLevel = mc.player.experienceLevel;
                continue;
            }
            if (r.type().equals("stars")) {
                connections(out, active, r, partial);
                continue;
            }
            if (r.type().equals("speed") || r.type().equals("groundcolor")) {
                if (history) continue;
                history = true;
            }
            for (Node n : active) drawOrb(out, n, r, player, now, partial, level);
        }
        for (var it = events.iterator(); it.hasNext(); ) {
            var e = it.next();
            double age = now - e.start;
            if (age >= e.life) {
                it.remove();
                continue;
            }
            if (age >= 0 && e.pos.distanceToSqr(camera) <= max)
                drawEvent(out, e, age / e.life, player);
        }
    }

    private static void drawOrb(
            EffectFrame out,
            Node n,
            SceneRule r,
            Vec3 player,
            double now,
            float partial,
            ClientLevel level) {
        Vec3 p = n.at(partial);
        switch (r.type()) {
            case "capture" -> {
                double distance = p.distanceTo(player);
                if (distance < r.range()) {
                    double f = 1 - distance / r.range(), a = now * .19;
                    Vec3 center = p.lerp(player, .35 + .35 * f);
                    Vec3 offset =
                            new Vec3(Math.cos(a), .3 * Math.sin(a), Math.sin(a))
                                    .scale(r.radius() * f);
                    cross(out, center.add(offset), r.width() * 2, r.primary());
                }
            }
            case "values" -> {
                int bands = Math.min(r.count(), (int) (n.value / r.threshold()));
                cross(out, p, r.width() * 2, r.primary());
                for (int i = 0; i < bands; i++)
                    ring(out, p, r.radius() * (.55 + .19 * i), r.width(), r.secondary(), false);
            }
            case "wake" -> {
                double opened = Math.clamp(1 - p.distanceTo(player) / r.range(), 0, 1);
                for (int i = 0; i < r.count(); i++) {
                    double a = i * 2 * Math.PI / r.count();
                    Vec3 radial = new Vec3(Math.cos(a), 0, Math.sin(a)),
                            side = radial.cross(UP).scale(r.radius() * .2);
                    Vec3 root = p.add(0, -r.radius() * .22, 0),
                            tip =
                                    p.add(radial.scale(r.radius() * (.12 + .9 * opened)))
                                            .add(0, r.radius() * (.85 - opened), 0);
                    Vec3 mid = root.lerp(tip, .55);
                    out.quad(
                            root,
                            mid.add(side),
                            tip,
                            mid.subtract(side),
                            i % 2 == 0 ? r.primary() : r.secondary());
                }
            }
            case "dusk" -> {
                double clock = Math.floorMod(level.getDefaultClockTime(), 24000L) / 24000.;
                double night = Math.clamp(.5 - .85 * Math.cos((clock - .25) * Math.PI * 2), 0, 1);
                int color = SceneRule.mix(r.primary(), r.secondary(), night);
                SpriteSheet sprite = XpOrbTrailsClient.sprite(AssetRole.ORB, r.sprite());
                if (sprite != null) out.sprite(sprite, p, r.radius() * 2, now / 20, color);
                else cross(out, p, r.radius() * .5, color);
            }
            case "speed", "groundcolor" -> {
                Mark last = null;
                int i = 0;
                for (Mark m : n.history) {
                    if (last != null)
                        out.line(
                                last.pos,
                                m.pos,
                                r.width(),
                                OrbEffectOptions.fade(m.color, .22 + .78 * i / n.history.size()));
                    last = m;
                    i++;
                }
                if (last != null) out.line(last.pos, p, r.width(), last.color);
            }
            default -> {}
        }
    }

    private static void connections(
            EffectFrame out, List<Node> nodes, SceneRule r, float partial) {
        int n = nodes.size();
        if (n < 2) return;
        double rangeSq = r.range() * r.range();
        int[] degree = new int[n];
        for (int i = 0; i < n; i++) {
            Vec3 a = nodes.get(i).at(partial);
            for (int j = i + 1; j < n && degree[i] < r.count(); j++) {
                if (degree[j] >= r.count()) continue;
                Vec3 b = nodes.get(j).at(partial);
                double d = a.distanceToSqr(b);
                if (d >= rangeSq) continue;
                out.line(
                        a,
                        b,
                        r.width(),
                        OrbEffectOptions.fade(r.primary(), 1 - .7 * Math.sqrt(d / rangeSq)));
                degree[i]++;
                degree[j]++;
            }
        }
    }

    private static void drawEvent(EffectFrame out, Event e, double p, Vec3 player) {
        double ticks = p * e.life;
        int color = OrbEffectOptions.fade(e.color, 1 - p);
        Vec3 at = e.pos.add(e.vector.scale(ticks));
        switch (e.kind) {
            case "arc" -> {
                Vec3 delta = e.vector.subtract(e.pos),
                        normal = OrbEffectOptions.perpendicular(delta.normalize());
                Vec3 mid = e.pos.lerp(e.vector, .5).add(normal.scale(.1));
                out.line(e.pos, mid, e.size, color);
                out.line(mid, e.vector, e.size, color);
            }
            case "ring", "flat_ring" ->
                    ring(out, e.pos, e.size * (.12 + p), .025, color, e.kind.equals("flat_ring"));
            case "bubble" -> ring(out, at, e.size * (1 + p), .014, color, false);
            case "drop" -> out.line(at, at.add(e.vector.scale(2)), e.size, color);
            case "crescent" -> {
                Vec3 tangent = e.vector.cross(UP);
                if (tangent.lengthSqr() < .01) tangent = new Vec3(1, 0, 0);
                tangent = tangent.normalize();
                Vec3 last = e.pos;
                for (int i = 1; i <= 8; i++) {
                    double a = i * Math.PI / 8;
                    Vec3 next =
                            e.pos.add(e.vector.scale(Math.sin(a) * (p + .3) * e.size))
                                    .add(tangent.scale((1 - Math.cos(a)) * e.size));
                    out.line(last, next, .03, color);
                    last = next;
                }
            }
            case "orbit" -> {
                double a = p * Math.PI * 1.45;
                Vec3 offset =
                        new Vec3(Math.cos(a), Math.sin(a) * .32, Math.sin(a))
                                .scale(e.size * (1 - p));
                cross(
                        out,
                        e.pos.lerp(player, p).add(offset.scale(Math.sin(p * Math.PI))),
                        .08,
                        color);
            }
            case "dust" -> {
                Vec3 c = at.add(0, -.0011 * ticks * ticks, 0),
                        side =
                                OrbEffectOptions.perpendicular(player.subtract(c).normalize())
                                        .scale(e.size);
                Vec3 up = UP.scale(e.size * .72);
                out.quad(
                        c.subtract(side).add(up),
                        c.subtract(side).subtract(up),
                        c.add(side).subtract(up),
                        c.add(side).add(up),
                        color);
            }
            case "snow" -> {
                for (int i = 0; i < 3; i++) {
                    double a = i * Math.PI / 3 + p;
                    Vec3 v = new Vec3(Math.cos(a), Math.sin(a), .15).scale(e.size * 2);
                    out.line(at.subtract(v), at.add(v), e.size * .33, color);
                }
            }
            case "celebrate" -> cross(out, at.add(0, -.0011 * ticks * ticks, 0), e.size, color);
            default -> cross(out, at, e.size, color);
        }
    }

    private static void ring(
            EffectFrame out, Vec3 p, double radius, double width, int color, boolean flat) {
        Vec3 last = p.add(radius, 0, 0);
        for (int i = 1; i <= 18; i++) {
            double a = i * Math.PI / 9, y = Math.sin(a) * radius;
            Vec3 next = p.add(Math.cos(a) * radius, flat ? 0 : y, flat ? y : 0);
            out.line(last, next, width, color);
            last = next;
        }
    }

    private static void cross(EffectFrame out, Vec3 p, double r, int c) {
        out.line(p.add(-r, 0, 0), p.add(r, 0, 0), r * .36, c);
        out.line(p.add(0, -r, 0), p.add(0, r, 0), r * .36, c);
        out.line(p.add(0, 0, -r), p.add(0, 0, r), r * .36, c);
    }

    private SceneEffects() {}
}

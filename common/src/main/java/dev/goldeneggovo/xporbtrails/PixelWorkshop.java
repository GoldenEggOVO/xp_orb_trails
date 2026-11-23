package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static dev.goldeneggovo.xporbtrails.PixelWorkshopImage.*;

final class PixelWorkshop {
    private static final Set<String> TYPES = Set.of("assemble", "shed", "directional", "relief", "fracture");
    private static final Map<Integer, Live> live = new LinkedHashMap<>();
    private static final ArrayDeque<Burst> bursts = new ArrayDeque<>();
    private static final Map<String, OrbEffectOptions> settings = new HashMap<>();
    private static final Identifier ORB = Identifier.withDefaultNamespace("textures/entity/experience/experience_orb.png");
    private static Object vanillaOwner;
    private static SpriteSheet vanillaSheet;

    static void validate(JsonObject o) {
        String type = AdvancedSettings.requireType(o, TYPES, "workshop");
        Set<String> allowed = switch (type) {
            case "assemble" -> Set.of("sprite", "mode", "captureTrail", "samples", "size", "formTicks", "holdTicks", "disperseTicks");
            case "shed" -> Set.of("mode", "count", "lifeTicks");
            case "directional" -> Set.of("sprite", "size", "fps", "heading");
            case "relief" -> Set.of("sprite", "heightMap", "size", "depth", "spin", "minResolution", "maxResolution", "pixelBudget");
            default -> Set.of("fractureMap", "shards", "lifeTicks", "spread");
        };
        for (var entry : o.entrySet()) {
            String key = entry.getKey();
            if (key.equals("type")) continue;
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown workshop." + type + "." + key);
            var value = entry.getValue();
            boolean valid = switch (key) {
                case "sprite", "heightMap", "fractureMap" -> value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isString() && SpriteLibrary.isSpriteId(value.getAsString())
                        && AssetRole.ORB.accepts(value.getAsString());
                case "captureTrail" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
                case "mode" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                        && (type.equals("assemble") ? Set.of("outline", "fill") : Set.of("edge", "all")).contains(value.getAsString());
                case "size" -> within(o, key, .05, 4, false);
                case "samples" -> within(o, key, 4, 512, true);
                case "minResolution", "maxResolution" -> within(o, key, 2, 64, true);
                case "pixelBudget" -> within(o, key, 4, 16384, true);
                case "formTicks", "disperseTicks", "lifeTicks" -> within(o, key, 1, 120, true);
                case "holdTicks" -> within(o, key, 0, 120, true);
                case "count" -> within(o, key, 1, 16, true);
                case "heading" -> within(o, key, -360, 360, false);
                case "fps" -> within(o, key, .1, 60, false);
                case "spin" -> within(o, key, -360, 360, false);
                case "depth", "spread" -> within(o, key, 0, 3, false);
                case "shards" -> within(o, key, 2, 32, true);
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Invalid workshop." + type + "." + key);
        }
        if (type.equals("relief")) {
            var settings = new OrbEffectOptions(o);
            if (settings.count("minResolution", 2, 2, 64) > settings.count("maxResolution", 32, 2, 64))
                throw new IllegalArgumentException("relief.minResolution exceeds maxResolution");
        }
    }

    private static boolean within(JsonObject o, String key, double min, double max, boolean integer) {
        var v = o.get(key);
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return false;
        double n = v.getAsDouble();
        return n >= min && n <= max && (!integer || n == Math.rint(n));
    }

    private static OrbEffectOptions options(String id) {
        JsonObject source = Workshop.options(id);
        if (source == null) return null;
        var o = settings.get(id);
        if (o == null || o.values != source) {
            o = new OrbEffectOptions(source);
            settings.put(id, o);
        }
        return o;
    }

    static void clear() {
        live.clear();
        bursts.clear();
        settings.clear();
        PixelWorkshopImage.clear();
    }

    record Axes(Vec3 right, Vec3 up) {
        Vec3 offset(double x, double y) { return right.scale(x).add(up.scale(y)); }
        static Axes camera() {
            var c = Minecraft.getInstance().gameRenderer.getMainCamera();
            return new Axes(new Vec3(c.leftVector()).scale(-1), new Vec3(c.upVector()));
        }
    }
    private record Art(SpriteSheet sheet, SpriteSheet.Frame frame, double size, double yOffset, int color) {
        Image image() { return new Image(sheet.image(), frame); }
        double unit() { return size / Math.max(frame.sourceWidth(), frame.sourceHeight()); }
    }
    private record Dot(Vec3 at, Vec3 velocity, long birth, int life, int color, double width) {
        Vec3 position(double now) { return at.add(velocity.scale(Math.max(0, now - birth))); }
    }
    private record Hole(SpriteSheet source, double x, double y, long birth) {}
    private record PeeledSprite(SpriteSheet sheet, SpriteSheet.Frame frame, List<PixelWorkshopImage.IntactStrip> strips) {}
    private static final class Live {
        final ExperienceOrb orb;
        final Random random;
        final ArrayDeque<Dot> drift = new ArrayDeque<>(), shed = new ArrayDeque<>();
        final ArrayDeque<Hole> holes = new ArrayDeque<>();
        PeeledSprite peeled;
        Vec3 last;
        double heading;
        int tick = Integer.MIN_VALUE;
        Live(ExperienceOrb orb) {
            this.orb = orb;
            random = new Random(orb.getUUID().getLeastSignificantBits());
            var o = options("directional");
            heading = Math.toRadians(o == null ? 0 : o.number("heading", 0, -360, 360));
        }
    }

    private static SpriteSheet sheet(String name) {
        return XpOrbTrailsClient.sprite(AssetRole.ORB, name);
    }

    static boolean replacesFlightSprite() {
        return Workshop.options("shed") != null;
    }

    static boolean replacesVanilla(ExperienceOrb orb) {
        var cfg = XpOrbTrailsClient.CONFIG;
        if (!cfg.showVanillaOrb || !replacesFlightSprite() || sheet(cfg.flightEffect) != null) return false;
        var state = live.get(orb.getId());
        return state != null && state.orb == orb && vanilla() != null
                && orb.position().distanceToSqr(Minecraft.getInstance().gameRenderer.getMainCamera().position())
                <= cfg.renderRange * cfg.renderRange;
    }

    private static Art body(Live state, Vec3 center, double age) {
        TrailConfig c = XpOrbTrailsClient.CONFIG;
        var d = options("directional");
        if (d != null) {
            var sheet = sheet(d.text("sprite", "sprite:orb/lab-skiff"));
            if (sheet != null && sheet.frameCount() % 8 == 0) {
                Vec3 view = Minecraft.getInstance().gameRenderer.getMainCamera().position().subtract(center);
                int n = sheet.frameCount() / 8;
                int facing = sector(view.x, view.z, state.heading);
                int index = facing * n + (int) (age * d.number("fps", 10, .1, 60) % n);
                return new Art(sheet, sheet.frame(index), d.number("size", .8, .05, 4), c.orbYOffset, -1);
            }
        }
        var relief = options("relief");
        String id = relief == null ? c.flightEffect : relief.text("sprite", c.flightEffect);
        var sheet = sheet(id);
        if (sheet != null) {
            var frame = sheet.frameAt(age, !c.showVanillaOrb || sheet.loop());
            if (frame == null) return null;
            double size = relief == null ? 2 * TrailEffects.flightSize(c) : relief.number("size", .8, .05, 4);
            return new Art(sheet, frame, size, c.orbYOffset, -1);
        }
        if (!c.showVanillaOrb) return null;
        var vanilla = vanilla();
        if (vanilla == null || state.orb.getIcon() >= vanilla.frameCount()) return null;
        int tint = 0x80000000 | FixedStampHistory.vanillaTint(state.orb.tickCount);
        return new Art(vanilla, vanilla.frame(state.orb.getIcon()), .30, .1, tint);
    }

    private static SpriteSheet vanilla() {
        var resource = Minecraft.getInstance().getResourceManager().getResource(ORB).orElse(null);
        if (resource == null) return null;
        if (resource.source() == vanillaOwner) return vanillaSheet;
        vanillaOwner = resource.source();
        vanillaSheet = null;
        var reader = ImageIO.getImageReadersByFormatName("png").next();
        try (var stream = resource.open(); var png = new MemoryCacheImageInputStream(stream)) {
            reader.setInput(png, true, true);
            int w = reader.getWidth(0) / 4, h = reader.getHeight(0) / 4;
            var descriptor = new JsonObject();
            descriptor.addProperty("frameWidth", w);
            descriptor.addProperty("frameHeight", h);
            var pivot = new com.google.gson.JsonArray();
            pivot.add(w * .5);
            pivot.add(h * .75);
            descriptor.add("pivot", pivot);
            vanillaSheet = SpriteSheet.read(reader, descriptor, "experience_orb", 4 * 1024 * 1024);
        } catch (IOException ignored) {
            // A pack may provide a non-grid replacement; its vanilla rendering still works.
        } finally { reader.dispose(); }
        return vanillaSheet;
    }

    static void track(ExperienceOrb orb) {
        Live state = live.get(orb.getId());
        if (state == null || state.orb != orb) {
            if (state == null && live.size() >= XpOrbTrailsClient.CONFIG.trailCap) return;
            state = new Live(orb);
            live.put(orb.getId(), state);
        }
        if (state.tick == orb.tickCount) return;
        state.tick = orb.tickCount;
        Vec3 pos = orb.position();
        Vec3 motion = state.last == null ? orb.getDeltaMovement() : pos.subtract(state.last);
        if (motion.x * motion.x + motion.z * motion.z > .000025) state.heading = Math.atan2(-motion.x, motion.z);
        state.last = pos;
        long tick = TrailRenderer.effectTick();
        expire(state.drift, tick);
        expire(state.shed, tick);
        while (!state.holes.isEmpty() && tick - state.holes.getFirst().birth >= 4) {
            state.holes.removeFirst();
            state.peeled = null;
        }
        var a = options("assemble");
        var shed = options("shed");
        if (captures(a) && shed == null && XpOrbTrailsClient.CONFIG.pickupFlash) {
            Vec3 origin = pos.add(0, XpOrbTrailsClient.CONFIG.orbYOffset, 0);
            for (int i = 0; i < 3; i++) {
                Vec3 v = jitter(state.random).scale(.012);
                add(state.drift, new Dot(origin.subtract(motion.scale(state.random.nextDouble())), v, tick, 48,
                        i == 0 ? 0xffdaffea : 0xff99d9b5, .021), 96);
            }
        }
        if (shed != null && motion.lengthSqr() > 1e-7) {
            double age = TrailRenderer.bodySpriteAge(orb, System.nanoTime());
            Art art = body(state, pos, age);
            if (art == null) return;
            boolean edge = "edge".equals(shed.text("mode", "edge"));
            var cells = PixelWorkshopImage.cells(art.image(), 32, edge);
            if (cells.isEmpty()) return;
            var axes = Axes.camera();
            var relief = options("relief");
            var heightMap = relief == null ? null : mask(relief, "heightMap", age);
            if (relief != null) axes = reliefAxes(state.heading, age, relief);
            int count = shed.count("count", 3, 1, 16);
            int life = shed.count("lifeTicks", 20, 1, 120);
            state.peeled = null;
            for (int i = 0; i < count; i++) {
                Cell cell = cells.get(state.random.nextInt(cells.size()));
                state.holes.addLast(new Hole(art.sheet, cell.x(), cell.y(), tick));
                Vec3 p = pos.add(0, art.yOffset, 0).add(axes.offset(cell.x() * art.unit(), cell.y() * art.unit()));
                if (relief != null) p = p.add(axes.right.cross(axes.up).scale(
                        reliefHeight(art, heightMap, cell, relief.number("depth", .23, 0, 3)) * .5));
                Vec3 velocity = jitter(state.random).scale(.012).subtract(motion.scale(.10));
                add(state.shed, new Dot(p, velocity, tick, life,
                        tint(cell.argb(), art.color, 1), art.unit() * Math.max(cell.width(), cell.height())), 128);
            }
        }
    }

    private static Vec3 jitter(Random rng) {
        return new Vec3(rng.nextDouble() - .5, rng.nextDouble() - .35, rng.nextDouble() - .5);
    }
    private static void expire(ArrayDeque<Dot> dots, long tick) {
        while (!dots.isEmpty() && tick - dots.getFirst().birth >= dots.getFirst().life) dots.removeFirst();
    }
    private static void add(ArrayDeque<Dot> dots, Dot next, int limit) {
        if (dots.size() >= limit) dots.removeFirst();
        dots.addLast(next);
    }

    private static boolean captures(OrbEffectOptions assembly) {
        return assembly != null && (!assembly.values.has("captureTrail") || assembly.values.get("captureTrail").getAsBoolean());
    }
    interface Playback { void draw(EffectFrame frame, double age, Axes axes, double strength); }
    private record Burst(Vec3 at, double start, double life, Playback playback) {}

    static void pickup(ExperienceOrb orb, int collector) {
        Live state = live.remove(orb.getId());
        if (state == null || state.orb != orb) state = new Live(orb);
        long tick = TrailRenderer.effectTick();
        var assemble = options("assemble");
        if (!state.shed.isEmpty() && !(captures(assemble) && XpOrbTrailsClient.CONFIG.pickupFlash)) {
            var remaining = state.shed;
            burst(orb.position(), tick, remaining.getLast().life,
                    (out, age, axes, strength) -> dots(out, remaining, tick + age, axes, strength));
        }
        if (!XpOrbTrailsClient.CONFIG.pickupFlash) return;
        if (assemble != null) startAssembly(state, tick, assemble);
        var fracture = options("fracture");
        if (fracture != null) startFracture(state, tick, fracture);
    }

    private static void burst(Vec3 p, long start, int life, Playback draw) {
        if (bursts.size() >= 24) bursts.removeFirst();
        bursts.addLast(new Burst(p, start, life, draw));
    }

    static double assemblyProgress(double age, int form) {
        double t = Math.clamp(age / form, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private record Mote(Vec3 start, int startColor, Cell cell, Vec3 fly, double bend) {}

    private static void startAssembly(Live state, long tick, OrbEffectOptions o) {
        var sheet = sheet(o.text("sprite", "sprite:orb/luna-moth"));
        if (sheet == null) return;
        var frame = sheet.frame(0);
        var cells = PixelWorkshopImage.cells(new Image(sheet.image(), frame), 32, "outline".equals(o.text("mode", "fill")));
        if (cells.isEmpty()) return;
        int form = o.count("formTicks", 14, 1, 120), hold = o.count("holdTicks", 10, 0, 120);
        int disperse = o.count("disperseTicks", 12, 1, 120), life = form + hold + disperse;
        Vec3 origin = state.orb.position().add(0, XpOrbTrailsClient.CONFIG.orbYOffset, 0);
        double unit = o.number("size", .9, .05, 4) / Math.max(frame.sourceWidth(), frame.sourceHeight());
        var trail = state.shed.isEmpty() ? state.drift : state.shed;
        expire(trail, tick);
        List<Dot> drift = captures(o) ? List.copyOf(trail) : List.of();
        int count = Math.min(cells.size(), o.count("samples", 256, 4, 512));
        var motes = new ArrayList<Mote>(count);
        for (int i = 0; i < count; i++) {
            Cell cell = cells.get(i * cells.size() / count);
            Dot old = drift.isEmpty() ? null : drift.get(i % drift.size());
            Vec3 start = old == null ? origin.add(jitter(state.random).scale(.85)) : old.position(tick);
            motes.add(new Mote(start, old == null ? cell.argb() : old.color, cell, jitter(state.random).scale(1.35),
                    (state.random.nextDouble() - .5) * .38));
        }
        burst(origin, tick, life, (out, age, axes, strength) -> {
            double t = assemblyProgress(age, form), fly = Math.clamp((age - form - hold) / disperse, 0, 1);
            double bend = Math.sin(t * Math.PI), driftScale = fly * fly;
            for (var m : motes) {
                var c = m.cell;
                Vec3 target = origin.add(axes.offset(c.x() * unit, c.y() * unit));
                Vec3 p = m.start.lerp(target, t).add(0, m.bend * bend, 0).add(m.fly.scale(driftScale));
                int rgb = TrailEffects.mix(m.startColor, c.argb(), t);
                int alpha = (int) Math.round((m.startColor >>> 24) * (1 - t) + (c.argb() >>> 24) * t);
                tile(out, p, axes, c.width() * unit, c.height() * unit, tint(alpha << 24 | rgb & 0xffffff, -1, strength * (1 - fly)));
            }
        });
    }

    private static Image mask(OrbEffectOptions o, String key, double age) {
        var sheet = sheet(o.text(key, ""));
        if (sheet == null) return null;
        return new Image(sheet.image(), sheet.frameAt(age, true));
    }

    private static void startFracture(Live state, long tick, OrbEffectOptions o) {
        double age = TrailRenderer.bodySpriteAge(state.orb, System.nanoTime());
        Art art = body(state, state.orb.position(), age);
        if (art == null) return;
        Vec3 origin = state.orb.position().add(0, art.yOffset, 0);
        var strips = PixelWorkshopImage.fracture(art.frame, mask(o, "fractureMap", age),
                o.count("shards", 7, 2, 32), state.random.nextLong());
        int groups = strips.stream().mapToInt(Strip::shard).max().orElse(0) + 1;
        double[] cx = new double[groups], cy = new double[groups], area = new double[groups], spin = new double[groups];
        Vec3[] velocity = new Vec3[groups];
        var basis = Axes.camera();
        for (var s : strips) {
            int i = s.shard();
            double a = (double) s.uv().width() * s.uv().height();
            cx[i] += a * s.x();
            cy[i] += a * s.y();
            area[i] += a;
        }
        for (int i = 0; i < groups; i++) {
            if (area[i] == 0) continue;
            cx[i] /= area[i];
            cy[i] /= area[i];
            Vec3 outward = basis.offset(cx[i], cy[i]).normalize().add(jitter(state.random).scale(.55));
            velocity[i] = outward.scale(o.number("spread", .7, 0, 3)).add(0, .3, 0);
            spin[i] = (state.random.nextDouble() - .5) * 3.2;
        }
        int life = o.count("lifeTicks", 22, 1, 120);
        burst(origin, tick, life, (out, elapsed, axes, strength) -> {
            double t = Math.clamp(elapsed / life, 0, 1);
            int color = tint(art.color, -1, strength * Math.clamp((1 - t) * 3, 0, 1));
            for (var s : strips) {
                int i = s.shard();
                double ang = t * spin[i], dx = s.x() - cx[i], dy = s.y() - cy[i];
                double x = cx[i] + dx * Math.cos(ang) - dy * Math.sin(ang);
                double y = cy[i] + dx * Math.sin(ang) + dy * Math.cos(ang);
                Vec3 p = origin.add(axes.offset(x * art.unit(), y * art.unit())).add(velocity[i].scale(t))
                        .add(0, -.65 * t * t, 0);
                out.spriteFrame(art.sheet, s.uv(), p, art.unit() * Math.max(s.uv().width(), s.uv().height()), color, -ang);
            }
        });
    }

    static void extract(EffectFrame out, ClientLevel level, float partial, long frameNanos) {
        double now = TrailRenderer.effectTick() + Math.clamp(partial, 0, 1);
        var cfg = XpOrbTrailsClient.CONFIG;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        var axes = Axes.camera();
        double range = cfg.renderRange * cfg.renderRange;
        var directional = options("directional");
        var relief = options("relief");
        boolean shed = replacesFlightSprite();
        int reliefResolution = relief == null ? 0 : reliefResolution(relief, live.size());
        var it = live.values().iterator();
        while (it.hasNext()) {
            var state = it.next();
            var orb = state.orb;
            if (orb.isRemoved() || orb != level.getEntity(orb.getId()) || camera.distanceToSqr(orb.position()) > range) {
                it.remove();
                continue;
            }
            dots(out, state.drift, now, axes, cfg.effectStrength);
            dots(out, state.shed, now, axes, cfg.effectStrength);
            if (directional == null && relief == null && !shed) continue;
            Vec3 pos = orb.getPosition(partial);
            double age = TrailRenderer.bodySpriteAge(orb, frameNanos);
            var art = body(state, pos, age);
            if (art == null) continue;
            var origin = pos.add(0, art.yOffset, 0);
            if (relief != null) relief(out, relief, art, origin, state.heading, age,
                    cfg.effectStrength, reliefResolution, state.holes);
            else if (shed && !state.holes.isEmpty()) peel(out, state, art, axes, origin, cfg.effectStrength);
            else out.spriteFrame(art.sheet, art.frame, origin, art.size, tint(art.color, -1, cfg.effectStrength), 0);
        }
        bursts.removeIf(b -> now - b.start >= b.life || camera.distanceToSqr(b.at) > range);
        for (var b : bursts) if (now >= b.start) b.playback.draw(out, now - b.start, axes, cfg.effectStrength);
    }

    private static void peel(EffectFrame out, Live state, Art art, Axes axes, Vec3 origin, double strength) {
        if (state.peeled == null || state.peeled.sheet != art.sheet || state.peeled.frame != art.frame) {
            int step = Math.ceilDiv(Math.max(art.frame.width(), art.frame.height()), 32);
            state.peeled = new PeeledSprite(art.sheet, art.frame,
                    PixelWorkshopImage.intactStrips(art.frame, step, holeMask(art, step, state.holes)));
        }
        int tint = tint(art.color, -1, strength);
        double unit = art.unit();
        for (var strip : state.peeled.strips) {
            var uv = strip.uv();
            Vec3 p = origin.add(axes.offset(strip.x() * unit, strip.y() * unit));
            out.spriteFrame(art.sheet, uv, p, unit * Math.max(uv.width(), uv.height()), tint, 0);
        }
    }

    private static boolean[] holeMask(Art art, int step, ArrayDeque<Hole> holes) {
        var f = art.frame;
        int cols = Math.ceilDiv(f.width(), step);
        boolean[] mask = new boolean[cols * Math.ceilDiv(f.height(), step)];
        for (var hole : holes) {
            if (hole.source != art.sheet) continue;
            double x = hole.x + f.pivotX() - f.offsetX(), y = f.pivotY() - hole.y - f.offsetY();
            if (x >= 0 && x < f.width() && y >= 0 && y < f.height()) mask[(int) y / step * cols + (int) x / step] = true;
        }
        return mask;
    }

    private static void dots(EffectFrame out, ArrayDeque<Dot> dots, double now, Axes axes, double strength) {
        for (var d : dots) {
            double fade = 1 - (now - d.birth) / d.life;
            if (fade > 0) tile(out, d.position(now), axes, d.width, d.width, tint(d.color, -1, strength * fade));
        }
    }

    private static void tile(EffectFrame out, Vec3 p, Axes axes, double width, double height, int color) {
        Vec3 r = axes.right.scale(width * .5), u = axes.up.scale(height * .5);
        out.quad(p.subtract(r).subtract(u), p.add(r).subtract(u), p.add(r).add(u), p.subtract(r).add(u), color);
    }

    private static Axes reliefAxes(double heading, double age, OrbEffectOptions o) {
        double yaw = heading + Math.toRadians(o.number("spin", 35, -360, 360) * age);
        return new Axes(new Vec3(Math.cos(yaw), 0, Math.sin(yaw)), new Vec3(0, 1, 0));
    }

    private static double reliefHeight(Art art, Image map, Cell cell, double depth) {
        var f = art.frame;
        double u = (f.pivotX() + cell.x()) / f.sourceWidth(), v = (f.pivotY() - cell.y()) / f.sourceHeight();
        return art.unit() * .3 + depth * brightness(map == null ? cell.argb() : map.relative(u, v));
    }

    static int reliefResolution(OrbEffectOptions options, int orbCount) {
        int min = options.count("minResolution", 2, 2, 64);
        int max = options.count("maxResolution", 32, 2, 64);
        int budget = options.count("pixelBudget", 1200, 4, 16384);
        return Math.clamp((int) Math.sqrt((double) budget / Math.max(1, orbCount)), min, max);
    }

    private static void relief(EffectFrame out, OrbEffectOptions o, Art art, Vec3 origin, double heading,
                               double age, double strength, int resolution, ArrayDeque<Hole> holes) {
        if (out.full()) return;
        var f = art.frame;
        var map = mask(o, "heightMap", age);
        var axes = reliefAxes(heading, age, o);
        var right = axes.right;
        var up = axes.up;
        var normal = right.cross(up);
        double depth = o.number("depth", .23, 0, 3);
        int step = Math.ceilDiv(Math.max(f.width(), f.height()), resolution);
        int cols = Math.ceilDiv(f.width(), step);
        boolean[] removed = holes.isEmpty() ? null : holeMask(art, step, holes);
        var horizontal = new Axes(right, normal);
        var vertical = new Axes(normal, up);
        for (var c : PixelWorkshopImage.cells(art.image(), resolution, false)) {
            if (out.full()) break;
            int x = (int) (f.pivotX() + c.x() - f.offsetX()), y = (int) (f.pivotY() - c.y() - f.offsetY());
            if (removed != null && removed[y / step * cols + x / step]) continue;
            double height = reliefHeight(art, map, c, depth);
            Vec3 base = origin.add(axes.offset(c.x() * art.unit(), c.y() * art.unit()));
            Vec3 front = base.add(normal.scale(height * .5)), back = base.subtract(normal.scale(height * .5));
            int color = tint(c.argb(), art.color, strength);
            double w = c.width() * art.unit(), h = c.height() * art.unit();
            tile(out, front, axes, w, h, color);
            tile(out, back, axes, w, h, tint(color, 0xffc7c7c7, 1));
            tile(out, base.add(up.scale(h * .5)), horizontal, w, height, tint(color, 0xffdedede, 1));
            tile(out, base.subtract(up.scale(h * .5)), horizontal, w, height, tint(color, 0xff939393, 1));
            tile(out, base.add(right.scale(w * .5)), vertical, height, h, tint(color, 0xffb4b4b4, 1));
            tile(out, base.subtract(right.scale(w * .5)), vertical, height, h, tint(color, 0xffa2a2a2, 1));
        }
    }
}

package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

import static dev.goldeneggovo.xporbtrails.OrbEffectOptions.*;

final class WorldWorkshop {
    private static final Map<Integer, Orb> orbs = new LinkedHashMap<>();
    private static final ArrayDeque<Segment> paths = new ArrayDeque<>();
    private static final ArrayDeque<Knot> knots = new ArrayDeque<>();
    private static final ArrayList<Mote> motes = new ArrayList<>();
    private static Map<String, WorldWorkshopStyle> styles;
    private static long lastMoteTick = Long.MIN_VALUE;

    private static final class Orb {
        final ExperienceOrb entity;
        final long birth;
        Vec3 previous, position, heading = new Vec3(0, 0, 1);
        long tick = Long.MIN_VALUE;
        int power;
        WorldWorkshopGeometry.Fish[] fish;

        Orb(ExperienceOrb entity, Vec3 position, long tick) {
            this.entity = entity;
            this.birth = tick;
            this.previous = this.position = position;
        }
    }

    private record Segment(UUID owner, Vec3 from, Vec3 to, long tick) {}
    private record Knot(UUID first, UUID second, Vec3 at, long tick) {}
    private static final class Mote {
        Vec3 before, at;
        final long born;

        Mote(Vec3 at, long born) {
            this.before = this.at = at;
            this.born = born;
        }
    }

    static void validate(JsonObject item) { new WorldWorkshopStyle(item); }

    static void clear() {
        orbs.clear();
        paths.clear();
        knots.clear();
        motes.clear();
        styles = null;
        lastMoteTick = Long.MIN_VALUE;
    }

    private static Map<String, WorldWorkshopStyle> settings() {
        if (styles == null) {
            var found = new HashMap<String, WorldWorkshopStyle>();
            for (String type : WorldWorkshopStyle.TYPES) {
                JsonObject item = Workshop.options(type);
                if (item != null) found.put(type, new WorldWorkshopStyle(item));
            }
            styles = found;
        }
        return styles;
    }

    static void track(ExperienceOrb orb) {
        var active = settings();
        if (active.isEmpty() || !(orb.level() instanceof ClientLevel level)) return;
        long tick = TrailRenderer.effectTick();
        Vec3 at = orb.position().add(0, XpOrbTrailsClient.CONFIG.orbYOffset, 0);
        Orb node = orbs.get(orb.getId());
        if (node == null || node.entity != orb || node.position.distanceToSqr(at) > 16) {
            if (node == null && orbs.size() >= Math.min(24, XpOrbTrailsClient.CONFIG.trailCap)) return;
            node = new Orb(orb, at, tick);
            orbs.put(orb.getId(), node);
        }
        if (node.tick == tick) return;
        node.tick = tick;
        node.previous = node.position;
        node.position = at;
        Vec3 motion = at.subtract(node.previous);
        if (motion.horizontalDistanceSqr() > .0004) node.heading = new Vec3(motion.x, 0, motion.z).normalize();
        var school = active.get("school");
        if (school != null) swim(node, school, level, tick);
        var crossing = active.get("crossing");
        if (crossing != null && motion.lengthSqr() > .0001)
            trace(orb.getUUID(), node.previous, at, tick, crossing);
        var current = active.get("current");
        if (current != null) {
            moveMotes(level, tick, current);
            Vec3 center = orb.position().add(0, .06, 0);
            for (int i = 0; i < current.perTick && motes.size() < 160; i++) {
                double angle = (orb.getId() * .37 + tick * 2.4 + i * 2);
                Vec3 birth = center.add(.10 * Math.cos(angle), .035 * Math.sin(3 * angle), .10 * Math.sin(angle));
                if (flow(level, birth).lengthSqr() > .001) motes.add(new Mote(birth, tick));
            }
        }
        if (active.containsKey("redstone")) node.power = signal(level, orb.blockPosition());
    }

    private static void swim(Orb node, WorldWorkshopStyle style, ClientLevel level, long tick) {
        if (node.fish == null) node.fish = new WorldWorkshopGeometry.Fish[style.count];
        BiPredicate<Vec3, Vec3> clear = (a, b) -> level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.MISS;
        Vec3 right = UP.cross(node.heading);
        for (int i = 0; i < node.fish.length; i++) {
            int row = i / 2 + 1, lane = i % 2 == 0 ? -1 : 1;
            double wave = Math.sin(tick * .17 + i * .93);
            Vec3 target = node.position.subtract(node.heading.scale(row * style.spacing))
                    .add(right.scale(lane * style.spacing * (.45 + row * .22) + wave * .045))
                    .add(0, .05 * Math.sin(i * 1.6 + tick * .11), 0);
            if (node.fish[i] == null) {
                Vec3 birth = clear.test(node.position, target) ? target : node.position;
                node.fish[i] = new WorldWorkshopGeometry.Fish(birth, node.heading);
            }
            node.fish[i].swim(target, right, lane, style.speed, clear);
        }
    }

    static Vec3 flow(ClientLevel level, Vec3 at) {
        BlockPos pos = BlockPos.containing(at);
        if (!level.hasChunkAt(pos)) return Vec3.ZERO;
        var liquid = level.getFluidState(pos);
        if (liquid.isEmpty() || at.y - pos.getY() >= liquid.getHeight(level, pos)) return Vec3.ZERO;
        Vec3 flow = liquid.getFlow(level, pos);
        var block = level.getBlockState(pos);
        if (block.is(Blocks.BUBBLE_COLUMN)) flow = flow.add(0, block.getValue(BubbleColumnBlock.DRAG_DOWN) ? -.85 : 1, 0);
        return flow;
    }

    private static void moveMotes(ClientLevel level, long tick, WorldWorkshopStyle style) {
        if (tick == lastMoteTick) return;
        lastMoteTick = tick;
        motes.removeIf(m -> {
            if (tick - m.born >= style.lifetimeTicks) return true;
            Vec3 current = flow(level, m.at);
            if (current.lengthSqr() < .001) return true;
            m.before = m.at;
            m.at = m.at.add(current.scale(style.speed));
            return false;
        });
    }

    static int signal(ClientLevel level, BlockPos cell) {
        var state = level.getBlockState(cell);
        if (state.is(Blocks.REDSTONE_WIRE)) return state.getValue(BlockStateProperties.POWER);
        return level.getBestNeighborSignal(cell);
    }

    private static void trace(UUID owner, Vec3 from, Vec3 to, long tick, WorldWorkshopStyle style) {
        while (!paths.isEmpty() && tick - paths.peekFirst().tick > style.memoryTicks) paths.removeFirst();
        for (var segment : paths) {
            if (owner.equals(segment.owner) || from.distanceToSqr(segment.from) > 81) continue;
            Vec3 at = WorldWorkshopGeometry.crossing(from, to, segment.from, segment.to, style.tolerance);
            if (at == null) continue;
            boolean repeat = knots.stream().anyMatch(k -> tick - k.tick < style.lifetimeTicks && k.at.distanceToSqr(at) < .16
                    && (owner.equals(k.first) && segment.owner.equals(k.second) || owner.equals(k.second) && segment.owner.equals(k.first)));
            if (!repeat) {
                if (knots.size() == 32) knots.removeFirst();
                knots.addLast(new Knot(owner, segment.owner, at, tick));
            }
            break;
        }
        if (paths.size() == 256) paths.removeFirst();
        paths.addLast(new Segment(owner, from, to, tick));
    }

    static void extract(EffectFrame out, ClientLevel level, float partial) {
        var active = settings();
        if (active.isEmpty()) return;
        long tick = TrailRenderer.effectTick();
        double now = tick + partial;
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        double limit = XpOrbTrailsClient.CONFIG.renderRange * XpOrbTrailsClient.CONFIG.renderRange;
        var school = active.get("school");
        var redstone = active.get("redstone");
        var fusion = active.get("fusion");
        SpriteSheet fishSprite = school != null && !school.sprite.equals("none")
                ? XpOrbTrailsClient.sprite(AssetRole.ORB, school.sprite) : null;
        var view = Minecraft.getInstance().gameRenderer.mainCamera();
        var partners = fusion == null ? null : new ArrayList<Vec3>();
        int bridges = 0;
        var it = orbs.values().iterator();
        while (it.hasNext()) {
            Orb node = it.next();
            if (node.entity.isRemoved() || level.getEntity(node.entity.getId()) != node.entity
                    || node.position.distanceToSqr(camera) > limit || tick - node.tick > 3) {
                it.remove();
                continue;
            }
            Vec3 p = node.previous.lerp(node.position, partial);
            if (school != null) {
                for (int i = 0; i < node.fish.length; i++) {
                    var fish = node.fish[i];
                    if (fishSprite == null) fish.draw(out, partial, now * .55 + i, school.size, school.color, school.secondary, camera);
                    else {
                        var heading = fish.heading.toVector3f();
                        double turn = Math.atan2(-view.upVector().dot(heading), -view.leftVector().dot(heading));
                        double duration = fishSprite.durationMillis() / 1000.;
                        double seconds = (now - node.birth) / 20 + duration * i / node.fish.length;
                        if (!fishSprite.loop()) seconds = Math.min(seconds, duration - .0005);
                        out.sprite(fishSprite, fish.previous.lerp(fish.position, partial), school.size * 2.5,
                                seconds, 0xffffffff, turn);
                    }
                }
            }
            if (redstone != null) WorldWorkshopGeometry.redstone(out, p, node.power, redstone);
            if (fusion != null && bridges < 12) {
                for (Vec3 other : partners) {
                    if (p.distanceToSqr(other) < fusion.range * fusion.range && p.distanceToSqr(other) > .0009) {
                        WorldWorkshopGeometry.fusion(out, other, p, fusion.range, fusion.radius, fusion.color, fusion.secondary);
                        if (++bridges >= 12) break;
                    }
                }
                partners.add(p);
            }
        }
        var crossing = active.get("crossing");
        if (crossing != null) {
            while (!knots.isEmpty() && now - knots.peekFirst().tick >= crossing.lifetimeTicks) knots.removeFirst();
            for (var knot : knots)
                if (knot.at.distanceToSqr(camera) <= limit)
                    WorldWorkshopGeometry.knot(out, knot.at, (now - knot.tick) / crossing.lifetimeTicks, crossing.color, crossing.secondary);
        }
        var current = active.get("current");
        if (current != null) {
            moveMotes(level, tick, current);
            for (Mote m : motes) {
                if (m.at.distanceToSqr(camera) > limit) continue;
                Vec3 p = m.before.lerp(m.at, partial);
                double fade = Math.clamp(1 - (now - m.born) / current.lifetimeTicks, 0, 1);
                out.line(p, p.subtract(m.at.subtract(m.before).scale(.8)).add(0, .02, 0), .042, fade(current.color, fade));
                out.line(p.add(-.019, 0, 0), p.add(.019, 0, 0), .020, fade(current.secondary, fade));
            }
        }
    }

    private WorldWorkshop() {}
}

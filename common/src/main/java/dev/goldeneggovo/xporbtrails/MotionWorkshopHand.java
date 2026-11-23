package dev.goldeneggovo.xporbtrails;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/** Receives the first fully transformed item layer for the hand a pickup is following. */
public final class MotionWorkshopHand {
    record Key(int entity, InteractionHand hand) {}
    private record Anchor(Vec3 local, boolean firstPerson, long tick) {}
    private record Request(Key key, Vec3 point, boolean firstPerson) {}
    private static final Map<Key, Anchor> anchors = new HashMap<>();
    private static Request pending;

    static void clear() {
        pending = null;
        anchors.clear();
    }

    public static void begin(int entity, InteractionHand hand, boolean firstPerson) {
        pending = null;
        if (!MotionWorkshop.following(entity, hand)) return;
        JsonObject o = Workshop.options("hand");
        if (o == null) return;
        Vec3 point = hand == InteractionHand.MAIN_HAND
                ? MotionWorkshopRig.vector(o, "mainPoint", new Vec3(.94, .94, .5))
                : MotionWorkshopRig.vector(o, "offPoint", new Vec3(.5, .38, .5));
        pending = new Request(new Key(entity, hand), point, firstPerson);
    }

    public static void end() { pending = null; }

    public static void capture(PoseStack pose) {
        Request req = pending;
        if (req == null) return;
        pending = null;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(req.key.entity) instanceof Player player)) return;
        CameraRenderState camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        Vec3 p = req.point;
        var worldOffset = pose.last().pose().transformPosition((float)p.x, (float)p.y, (float)p.z, new Vector3f());
        Vec3 local;
        if (req.firstPerson) {
            var view = camera.viewRotationMatrix.transformDirection(worldOffset);
            double s = 1 / (camera.projectionMatrix.m11() * Math.tan(Math.toRadians(camera.hudFov) / 2));
            if (!Double.isFinite(s) || s <= 0) s = 1;
            local = new Vec3(view.x * s, view.y * s, view.z);
        } else {
            float partial = mc.gameRenderer.mainCamera().getCameraEntityPartialTicks(mc.getDeltaTracker());
            local = camera.pos.add(new Vec3(worldOffset)).subtract(player.getPosition(partial))
                    .yRot(yaw(player, partial));
        }
        anchors.put(req.key, new Anchor(local, req.firstPerson, TrailRenderer.effectTick()));
    }

    static InteractionHand select(Player p, OrbEffectOptions o) {
        return switch (o.text("targetHand", "auto")) {
            case "main" -> InteractionHand.MAIN_HAND;
            case "off" -> InteractionHand.OFF_HAND;
            default -> {
                String main = BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath();
                String off = BuiltInRegistries.ITEM.getKey(p.getOffhandItem().getItem()).getPath();
                yield !main.endsWith("_sword") && (off.endsWith("lantern") || p.getMainHandItem().isEmpty() && !p.getOffhandItem().isEmpty())
                        ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            }
        };
    }

    static Vec3 target(Player player, InteractionHand hand, float partial) {
        var mc = Minecraft.getInstance();
        boolean first = player == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
        Anchor anchor = anchors.get(new Key(player.getId(), hand));
        if (anchor != null && anchor.firstPerson == first && TrailRenderer.effectTick() - anchor.tick <= 2
                && !player.getItemInHand(hand).isEmpty()) {
            if (!first) return player.getPosition(partial).add(anchor.local.yRot(-yaw(player, partial)));
            var camera = mc.gameRenderer.mainCamera();
            return camera.position().add(new Vec3(camera.rotation().transform(anchor.local.toVector3f())));
        }
        // Hidden or empty hands still have a physical grip. Mirror vanilla's animated lead position for the offhand.
        Vec3 pos = player.getRopeHoldPosition(partial);
        if (hand == InteractionHand.MAIN_HAND) return pos;
        Vec3 side = Vec3.X_AXIS.yRot(-yaw(player, partial));
        Vec3 center = player.getPosition(partial);
        return pos.subtract(side.scale(2 * pos.subtract(center).dot(side)));
    }

    static boolean captured(int id, InteractionHand hand) { return anchors.containsKey(new Key(id, hand)); }
    static void release(int id, InteractionHand hand) { anchors.remove(new Key(id, hand)); }
    private static float yaw(Player player, float partial) {
        return (float)Math.toRadians(Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot));
    }
    private MotionWorkshopHand() {}
}

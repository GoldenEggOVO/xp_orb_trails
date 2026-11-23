package dev.goldeneggovo.xporbtrails;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;

import java.util.ArrayList;
import java.util.List;

final class EffectLifecycleClientTest {
    private static final int ORB = 109599;

    static void run(ClientGameTestContext ctx, TestSingleplayerContext world) {
        world.getConnection().waitForChunksRender();
        var failures = new ArrayList<String>();
        try {
            ctx.runOnClient(mc -> {
                var config = XpOrbTrailsClient.CONFIG;
                var relief = EffectCollectionClientTest.profile("lab-relief").settings();
                relief.applyTo(config);
                var camera = mc.gameRenderer.mainCamera().position();
                var orb = new ExperienceOrb(mc.level, camera.x, camera.y + 1, camera.z + 3, 2);
                orb.setId(ORB);
                orb.setNoGravity(true);
                mc.level.addEntity(orb);
                TrailRenderer.track(orb);
                TrailRenderer.confirmPickup(orb, mc.player.getId());
                relief.applyTo(config);
                if (!hasFlash(mc)) throw new AssertionError("Expected the original ring pickup");
                EffectCollectionClientTest.profile("lab-rig").settings().applyTo(config);
                if (hasFlash(mc)) failures.add("New shards style replayed the old ring pickup");
                relief.applyTo(config);
                if (hasFlash(mc)) failures.add("Returning to the ring style resurrected its old pickup");
                TrailRenderer.confirmPickup(orb, mc.player.getId());
                if (!hasFlash(mc)) throw new AssertionError("Expected a new pickup after switching");
                EffectCollectionClientTest.profile("workshop-mirage").settings().applyTo(config);
            });
            ctx.waitFor(mc -> sceneCopy() != null);
            var enabledTarget = ctx.computeOnClient(mc -> sceneCopy());
            ctx.runOnClient(mc -> XpOrbTrailsClient.CONFIG.enabled = false);
            ctx.waitTicks(2);
            ctx.runOnClient(mc -> {
                if (enabledTarget.getColorTexture() != null) failures.add("Disabling retained the optics texture");
                XpOrbTrailsClient.CONFIG.enabled = true;
                TrailRenderer.track((ExperienceOrb) mc.level.getEntity(ORB));
            });
            ctx.waitFor(mc -> sceneCopy() != null);
            ctx.runOnClient(mc -> {
                var target = sceneCopy();
                TrailRenderer.updateWorld(null);
                if (target.getColorTexture() != null) failures.add("Unloading retained the optics texture");
            });
            if (!failures.isEmpty()) throw new AssertionError(String.join(", ", failures));
            System.out.println("XPORB_LIFECYCLE pickupStyleSwitch=passed opticsDisable=passed opticsUnload=passed");
        } finally {
            ctx.runOnClient(mc -> mc.level.removeEntity(ORB, Entity.RemovalReason.DISCARDED));
        }
    }

    private static boolean hasFlash(Minecraft mc) {
        TrailRenderer.extract(mc.level, mc.gameRenderer.mainCamera().position(), 1);
        return ((List<?>) TrailClientTest.field(null, "renderTrails")).stream()
                .mapToDouble(t -> (double) TrailClientTest.field(t, "flashProgress"))
                .anyMatch(progress -> progress >= 0 && progress < 1);
    }

    private static RenderTarget sceneCopy() {
        try {
            var field = SceneOptics.class.getDeclaredField("sceneCopy");
            field.setAccessible(true);
            return (RenderTarget) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}

package dev.goldeneggovo.xporbtrails.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.goldeneggovo.xporbtrails.OrbBodyVisibility;
import dev.goldeneggovo.xporbtrails.TrailRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ExperienceOrbRenderer;
import net.minecraft.client.renderer.entity.state.ExperienceOrbRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ExperienceOrbRenderer.class)
public abstract class ExperienceOrbRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/ExperienceOrb;Lnet/minecraft/client/renderer/entity/state/ExperienceOrbRenderState;F)V", at = @At("TAIL"))
    private void xporbtrails$extractVisibility(ExperienceOrb orb, ExperienceOrbRenderState state, float partialTick, CallbackInfo ci) {
        ((OrbBodyVisibility) state).xporbtrails$setShowVanillaBody(TrailRenderer.showsVanillaBody(orb));
    }

    @WrapWithCondition(method = "submit(Lnet/minecraft/client/renderer/entity/state/ExperienceOrbRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V"))
    private boolean xporbtrails$showOrb(SubmitNodeCollector collector, PoseStack pose, RenderType type,
                                       SubmitNodeCollector.CustomGeometryRenderer geometry,
                                       @Local(argsOnly = true) ExperienceOrbRenderState state) {
        return ((OrbBodyVisibility) state).xporbtrails$showVanillaBody();
    }
}

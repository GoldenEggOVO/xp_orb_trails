package dev.goldeneggovo.xporbtrails.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.goldeneggovo.xporbtrails.MotionWorkshopHand;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandLayer.class)
abstract class MotionThirdPersonHandMixin {
    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void xporbtrails$begin(ArmedEntityRenderState state, ItemStackRenderState itemState, ItemStack item,
                                   HumanoidArm arm, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (state instanceof AvatarRenderState avatar)
            MotionWorkshopHand.begin(avatar.id, arm == avatar.mainArm ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, false);
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void xporbtrails$end(CallbackInfo ci) { MotionWorkshopHand.end(); }
}

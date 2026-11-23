package dev.goldeneggovo.xporbtrails.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.goldeneggovo.xporbtrails.MotionWorkshopHand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
abstract class MotionFirstPersonHandMixin {
    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void xporbtrails$begin(PlayerRenderState player, FirstPersonHandsAndItemsRenderState state,
                                   float partial, float pitch, InteractionHand hand, float swing, ItemStack item,
                                   float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        var entity = Minecraft.getInstance().player;
        if (entity != null) MotionWorkshopHand.begin(entity.getId(), hand, true);
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void xporbtrails$end(CallbackInfo ci) { MotionWorkshopHand.end(); }
}

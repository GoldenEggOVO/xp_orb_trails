package dev.goldeneggovo.xporbtrails.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.goldeneggovo.xporbtrails.MotionWorkshopHand;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
abstract class MotionFirstPersonHandMixin {
    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void xporbtrails$begin(AbstractClientPlayer player,
                                   float partial, float pitch, InteractionHand hand, float swing, ItemStack item,
                                   float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        MotionWorkshopHand.begin(player.getId(), hand, true);
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void xporbtrails$end(CallbackInfo ci) { MotionWorkshopHand.end(); }
}

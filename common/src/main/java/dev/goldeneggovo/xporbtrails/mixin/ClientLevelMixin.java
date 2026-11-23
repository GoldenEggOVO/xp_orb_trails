package dev.goldeneggovo.xporbtrails.mixin;

import dev.goldeneggovo.xporbtrails.TrailRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "tickTime", at = @At("TAIL"))
    private void xporbtrails$tickEffects(CallbackInfo ci) {
        TrailRenderer.tickEffects((ClientLevel) (Object) this);
    }
}

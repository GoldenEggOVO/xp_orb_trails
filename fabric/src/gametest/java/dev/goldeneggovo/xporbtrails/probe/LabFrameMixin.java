package dev.goldeneggovo.xporbtrails.probe;
import dev.goldeneggovo.xporbtrails.LabTimings;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class LabFrameMixin {
    @Inject(method="render",at=@At("HEAD")) private void xporb$begin(CallbackInfo ci){LabTimings.beforeRender();}
    @Inject(method="render",at=@At("RETURN")) private void xporb$end(CallbackInfo ci){LabTimings.afterRender();}
}

package dev.goldeneggovo.xporbtrails.probe;
import dev.goldeneggovo.xporbtrails.LabTimings;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class LabTickMixin {
    @Inject(method="tick",at=@At("RETURN")) private void xporb$simulateFarm(CallbackInfo ci){LabTimings.step();}
}

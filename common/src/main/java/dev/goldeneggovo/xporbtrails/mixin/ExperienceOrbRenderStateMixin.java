package dev.goldeneggovo.xporbtrails.mixin;

import dev.goldeneggovo.xporbtrails.OrbBodyVisibility;
import net.minecraft.client.renderer.entity.state.ExperienceOrbRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ExperienceOrbRenderState.class)
public abstract class ExperienceOrbRenderStateMixin implements OrbBodyVisibility {
    @Unique private boolean xporbtrails$showVanillaBody = true;

    @Override
    public void xporbtrails$setShowVanillaBody(boolean visible) {
        xporbtrails$showVanillaBody = visible;
    }

    @Override
    public boolean xporbtrails$showVanillaBody() {
        return xporbtrails$showVanillaBody;
    }
}

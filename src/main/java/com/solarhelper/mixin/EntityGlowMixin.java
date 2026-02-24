package com.solarhelper.mixin;

import com.solarhelper.SolarHelperConfig;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityGlowMixin {
    @Inject(method = "isGlowing", at = @At("RETURN"), cancellable = true)
    private void onIsGlowing(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()
                && (Object) this instanceof AbstractClientPlayerEntity
                && SolarHelperConfig.get().playerOutlinesEnabled) {
            cir.setReturnValue(true);
        }
    }
}

package com.solarhelper.mixin;

import com.solarhelper.SolarHelperClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        if (SolarHelperClient.isInputFrozen()) {
            this.playerInput = PlayerInput.DEFAULT;
            this.movementVector = Vec2f.ZERO;
            // Block left click (attack key) during freeze
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.options != null) {
                // Release the attack key so the player stops mining/attacking
                while (client.options.attackKey.wasPressed()) {
                    // consume presses
                }
            }
        }

        if (SolarHelperClient.isAutoFarmActive()) {
            // Force forward + left + sprint + attack
            this.playerInput = new PlayerInput(
                true,   // forward
                false,  // backward
                true,   // left
                false,  // right
                false,  // jump
                false,  // sneak
                true    // sprint
            );
            // Movement vector: forward=1, left=1
            this.movementVector = new Vec2f(1.0f, 1.0f);
            // Force left click (attack)
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.options != null) {
                client.options.attackKey.setPressed(true);
            }
        } else {
            // Release attack key when auto-farm is off (prevent stuck keys)
            SolarHelperClient.releaseAutoFarmKeys();
        }
    }
}

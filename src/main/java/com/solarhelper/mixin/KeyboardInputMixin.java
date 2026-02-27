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
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.options != null) {
                while (client.options.attackKey.wasPressed()) { /* consume */ }
            }
        }

        // ── Dropper Solver input forcing ──
        if (SolarHelperClient.isDropperActive()) {
            int fwd = SolarHelperClient.getDropperForward();
            int sw  = SolarHelperClient.getDropperSideways();
            // Positive sideways = left (Minecraft Input convention)
            this.playerInput = new PlayerInput(
                fwd > 0,   // forward
                fwd < 0,   // backward
                sw  > 0,   // left
                sw  < 0,   // right
                false,     // jump
                false,     // sneak
                false      // sprint (not useful in air)
            );
            this.movementVector = new Vec2f(sw != 0 ? (float) sw : 0.0f, fwd != 0 ? (float) fwd : 0.0f);
            return;
        }

        // ── Head Seeker input forcing ──
        if (SolarHelperClient.isHeadSeekActive()) {
            MinecraftClient client = MinecraftClient.getInstance();
            SolarHelperClient.HeadSeekState state = SolarHelperClient.getHeadSeekState();
            boolean jumping = SolarHelperClient.isSeekJumping() || SolarHelperClient.getFlyDoubleTapTick() >= 0;

            switch (state) {
                case MOVING -> {
                    // Walk forward toward whatever we're looking at (waypoint or head).
                    // The look direction is set by tickHeadSeek, so forward = toward waypoint.
                    this.playerInput = new PlayerInput(
                        true,   // forward
                        false,  // backward
                        false,  // left
                        false,  // right
                        jumping, // jump (obstacles or fly toggle)
                        false,  // sneak
                        true    // sprint
                    );
                    this.movementVector = new Vec2f(0.0f, 1.0f);
                    if (client.options != null) {
                        client.options.useKey.setPressed(false);
                    }
                }
                case APPROACHING -> {
                    // Move toward head; allow jumping to clear last-step obstacles
                    this.playerInput = new PlayerInput(
                        true,    // forward
                        false,   // backward
                        false,   // left
                        false,   // right
                        jumping, // jump when stuck (seekJumping or fly toggle)
                        true,    // sneak
                        false    // no sprint
                    );
                    this.movementVector = new Vec2f(0.0f, 1.0f);
                    if (client.options != null) {
                        client.options.useKey.setPressed(false);
                    }
                }
                case INTERACTING -> {
                    // Stop, sneak, right-click
                    this.playerInput = new PlayerInput(
                        false, false, false, false,
                        false,  // jump
                        true,   // sneak
                        false   // sprint
                    );
                    this.movementVector = Vec2f.ZERO;
                    if (client.options != null) {
                        client.options.useKey.setPressed(true);
                    }
                }
                case ROTATING -> {
                    // Stand still while turning to face first waypoint
                    this.playerInput = PlayerInput.DEFAULT;
                    this.movementVector = Vec2f.ZERO;
                    if (client.options != null) {
                        client.options.useKey.setPressed(false);
                    }
                }
                default -> {
                    if (client.options != null) {
                        client.options.useKey.setPressed(false);
                    }
                }
            }
            return;
        }

        // ── Auto Farm input forcing ──
        if (SolarHelperClient.isAutoFarmActive()) {
            this.playerInput = new PlayerInput(
                true,   // forward
                false,  // backward
                true,   // left
                false,  // right
                false,  // jump
                false,  // sneak
                true    // sprint
            );
            this.movementVector = new Vec2f(1.0f, 1.0f);
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.options != null) {
                client.options.attackKey.setPressed(true);
            }
        } else {
            SolarHelperClient.releaseAutoFarmKeys();
            // Also stop attacking during brief rotation pauses (autoFarmPaused = true but not fully stopped)
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.options != null) {
                client.options.attackKey.setPressed(false);
            }
        }
    }
}

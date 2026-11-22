package dev.rongmc.client.mixin;

import dev.rongmc.client.RongMcClient;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void rongmc$blockMovement(CallbackInfo ci) {
        if (RongMcClient.tour().active() || RongMcClient.editor().previewActive()) {
            keyPresses = Input.EMPTY;
            moveVector = Vec2.ZERO;
            ci.cancel();
        }
    }
}

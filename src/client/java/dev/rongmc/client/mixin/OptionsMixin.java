package dev.rongmc.client.mixin;

import dev.rongmc.client.RongMcClient;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public abstract class OptionsMixin {
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void rongmc$keepTourOptionsTemporary(CallbackInfo ci) {
        if (RongMcClient.tour().temporaryOptions() || RongMcClient.editor().previewActive()) ci.cancel();
    }
}

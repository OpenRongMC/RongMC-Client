package dev.rongmc.client.mixin;

import dev.rongmc.client.RongMcClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private float fov;
    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Inject(method = "update", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;hudFov:F", opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void rongmc$tourCamera(DeltaTracker delta, CallbackInfo ci) {
        var tour = RongMcClient.tour();
        tour.frame();
        var pose = tour.pose();
        if (pose == null) { RongMcClient.editor().frame(); pose = RongMcClient.editor().pose(); }
        if (pose != null) {
            setPosition(pose.x(), pose.y(), pose.z());
            setRotation(pose.yaw(), pose.pitch());
            fov = pose.fov();
        }
    }
}

package dev.rongmc.client.tour;

import dev.rongmc.tour.QualityPolicy;
import net.minecraft.client.CameraType;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ParticleStatus;

public record OptionsSnapshot(int viewDistance, double entityDistance, CloudStatus clouds,
                              ParticleStatus particles, boolean hideGui, CameraType cameraType, boolean bobView) {
    public static OptionsSnapshot capture(Minecraft mc) {
        var o = mc.options;
        return new OptionsSnapshot(o.renderDistance().get(), o.entityDistanceScaling().get(), o.cloudStatus().get(),
                o.particles().get(), mc.gui.hud.isHidden(), o.getCameraType(), o.bobView().get());
    }
    public void apply(Minecraft mc, QualityPolicy.Tier tier) {
        var o = mc.options;
        o.renderDistance().set(Math.min(viewDistance, tier.viewDistance));
        o.entityDistanceScaling().set(Math.min(entityDistance, tier.entityDistance));
        o.cloudStatus().set(tier == QualityPolicy.Tier.LOW ? CloudStatus.OFF : clouds);
        o.particles().set(tier == QualityPolicy.Tier.LOW ? ParticleStatus.MINIMAL : particles);
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        o.setCameraType(CameraType.FIRST_PERSON);
        o.bobView().set(false);
        o.broadcastOptions();
    }
    public void restore(Minecraft mc) {
        var o = mc.options;
        o.renderDistance().set(viewDistance);
        o.entityDistanceScaling().set(entityDistance);
        o.cloudStatus().set(clouds);
        o.particles().set(particles);
        if (mc.gui.hud.isHidden() != hideGui) mc.gui.hud.toggle();
        o.setCameraType(cameraType);
        o.bobView().set(bobView);
        o.broadcastOptions();
    }
}

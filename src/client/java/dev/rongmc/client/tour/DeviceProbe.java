package dev.rongmc.client.tour;

import dev.rongmc.tour.QualityPolicy;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/** Local-only probe, called after Minecraft has created its graphics context. */
public record DeviceProbe(String renderer, int processors, long heapMiB, long pixels,
                          QualityPolicy.Tier ceiling, double frameBudgetMs) {
    public static DeviceProbe capture(Minecraft mc) {
        String renderer;
        try { renderer = GL11.glGetString(GL11.GL_RENDERER); }
        catch (IllegalStateException | LinkageError e) { renderer = "unknown"; }
        if (renderer == null) renderer = "unknown";
        int processors = Runtime.getRuntime().availableProcessors();
        long heap = Runtime.getRuntime().maxMemory() / 1_048_576L;
        long pixels = (long) mc.getWindow().getWidth() * mc.getWindow().getHeight();
        int cap = mc.options.framerateLimit().get();
        int target = cap > 0 ? Math.min(60, cap) : 60;
        if (mc.options.enableVsync().get()) {
            float refresh = mc.getWindow().getActiveVideoMode().getRefreshRate();
            if (refresh > 0) target = Math.min(target, Math.round(refresh));
        }
        return new DeviceProbe(renderer, processors, heap, pixels,
                QualityPolicy.ceiling(processors, heap, pixels, renderer), 1000.0 / Math.max(1, target));
    }
    public static boolean memoryPressure() {
        Runtime rt = Runtime.getRuntime();
        return (double) (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory() > .88;
    }
}

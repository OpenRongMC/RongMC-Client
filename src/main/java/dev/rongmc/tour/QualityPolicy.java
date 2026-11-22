package dev.rongmc.tour;

import java.util.Arrays;

/** Pure policy: no hardware-name rankings and no server/network timings masquerading as GPU timings. */
public final class QualityPolicy {
    private QualityPolicy() {}
    public enum Tier {
        LOW(6, 0.5), BALANCED(10, 0.75), HIGH(14, 1.0);
        public final int viewDistance;
        public final double entityDistance;
        Tier(int viewDistance, double entityDistance) { this.viewDistance = viewDistance; this.entityDistance = entityDistance; }
        public Tier lower() { return values()[Math.max(0, ordinal() - 1)]; }
        public Tier higher() { return values()[Math.min(values().length - 1, ordinal() + 1)]; }
    }

    public static Tier ceiling(int processors, long heapMiB, long framebufferPixels, String renderer) {
        String name = renderer.toLowerCase(java.util.Locale.ROOT);
        if (heapMiB < 2048 || processors < 4 || name.contains("llvmpipe") || name.contains("softpipe")) return Tier.LOW;
        if (heapMiB >= 4096 && processors >= 8 && framebufferPixels <= 4_200_000) return Tier.HIGH;
        return Tier.BALANCED;
    }

    public static double percentile95(double[] samples, int count) {
        if (count < 1) return Double.POSITIVE_INFINITY;
        double[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        return sorted[Math.max(0, (int) Math.ceil(count * .95) - 1)];
    }

    public static Tier calibrated(Tier current, Tier ceiling, double p95Ms, double budgetMs, boolean memoryPressure) {
        if (memoryPressure || p95Ms > budgetMs * 1.35) return current.lower();
        if (p95Ms < budgetMs * 1.15 && current.ordinal() < ceiling.ordinal()) return current.higher();
        return current;
    }
}

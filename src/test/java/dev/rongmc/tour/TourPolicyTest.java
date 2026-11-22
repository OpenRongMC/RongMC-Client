package dev.rongmc.tour;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TourPolicyTest {
    private TourDefinition definition(List<TourDefinition.Keyframe> keys) {
        return new TourDefinition(1, "test", List.of(new TourDefinition.Chapter("intro", "Intro", "Description", "minecraft:overworld",
                new TourDefinition.Anchor(0, 64, 0), keys)));
    }
    private TourDefinition.Keyframe key(double t, double x, float yaw) {
        return new TourDefinition.Keyframe(t, x, 70, 0, yaw, 10, 65);
    }
    @Test void shortestYawArcDoesNotSpinThroughZero() {
        var path = List.of(key(0, 0, 179), key(10, 10, -179));
        assertEquals(180, CameraPath.sample(path, 5).yaw(), .001);
        assertEquals(5, CameraPath.sample(path, 5).x(), .001);
    }
    @Test void endpointsAndEasingRemainInsideTheLoadedScene() {
        var path = List.of(key(0, -20, 0), key(10, 20, 80));
        assertEquals(-20, CameraPath.sample(path, -2).x());
        assertEquals(20, CameraPath.sample(path, 100).x());
        for (double t = 0; t <= 10; t += .01) assertTrue(Math.abs(CameraPath.sample(path, t).x()) <= 20);
        assertTrue(CameraPath.sample(path, .1).x() < -19.9);
    }
    @Test void rejectsInvalidWorldCoordinatesAndTimes() {
        assertThrows(IllegalArgumentException.class, () -> definition(List.of(key(0, 0, 0), key(10, 30_000_001, 0))).validate());
        assertThrows(IllegalArgumentException.class, () -> definition(List.of(key(1, 0, 0), key(10, 1, 0))).validate());
        assertThrows(IllegalArgumentException.class, () -> definition(List.of(key(0, 0, 0), key(0, 1, 0))).validate());
        assertThrows(IllegalArgumentException.class, () -> definition(List.of(key(0, Double.NaN, 0), key(10, 1, 0))).validate());
    }
    @Test void longRoutesRequireStreamingPeersButRemainValid() {
        var longRoute = definition(List.of(key(0, 0, 0), key(10, 4096, 0)));
        longRoute.validate(); assertTrue(longRoute.requiresStreaming());
        assertFalse(definition(List.of(key(0, 0, 0), key(10, 20, 0))).requiresStreaming());
    }
    @Test void acceptsAndRoundTripsAValidConfiguration() {
        var tour = definition(List.of(key(0, 0, 0), key(10, 20, 30)));
        tour.validate();
        var copy = JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(tour), TourDefinition.class);
        copy.validate();
        assertEquals(tour, copy);
    }
    @Test void rejectsDuplicateIdsAndUnboundedDescriptions() {
        var good = definition(List.of(key(0, 0, 0), key(10, 1, 0))).chapters().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new TourDefinition(1, "x", List.of(good, good)).validate());
        assertThrows(IllegalArgumentException.class, () -> new TourDefinition(1, "x", List.of(
                new TourDefinition.Chapter("a", "t", "x".repeat(1201), good.dimension(), good.anchor(), good.keyframes()))).validate());
    }
    @Test void hardwareProbeIsConservativeForLowMemoryAndSoftwareRendering() {
        assertEquals(QualityPolicy.Tier.LOW, QualityPolicy.ceiling(16, 1024, 1920 * 1080, "fast GPU"));
        assertEquals(QualityPolicy.Tier.LOW, QualityPolicy.ceiling(16, 8192, 1920 * 1080, "llvmpipe"));
        assertEquals(QualityPolicy.Tier.BALANCED, QualityPolicy.ceiling(8, 4096, 7680L * 4320, "unknown"));
    }
    @Test void pressureAndSlowFramesLowerQualityAndNeverRaiseItPastTheCeiling() {
        var low = QualityPolicy.Tier.LOW;
        var high = QualityPolicy.Tier.HIGH;
        assertEquals(low, QualityPolicy.calibrated(low, high, 10, 16.67, true));
        assertEquals(QualityPolicy.Tier.BALANCED, QualityPolicy.calibrated(high, high, 45, 16.67, false));
        assertEquals(low, QualityPolicy.calibrated(low, low, 3, 16.67, false));
    }
    @Test void percentileUsesSlowFramesRatherThanAnOptimisticAverage() {
        double[] frames = new double[100];
        java.util.Arrays.fill(frames, 10);
        for (int i = 90; i < 100; i++) frames[i] = 80;
        assertEquals(80, QualityPolicy.percentile95(frames, frames.length));
        assertTrue(Double.isInfinite(QualityPolicy.percentile95(frames, 0)));
    }
}

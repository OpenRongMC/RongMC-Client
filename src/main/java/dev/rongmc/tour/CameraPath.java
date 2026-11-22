package dev.rongmc.tour;

import java.util.List;

public final class CameraPath {
    private static final java.util.Map<TourDefinition.Chapter, ReplayPath> REPLAY_PATHS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private CameraPath() {}
    public record Pose(double x, double y, double z, float yaw, float pitch, float fov) {}

    public static Pose sample(TourDefinition.Chapter chapter, double seconds) {
        if (chapter.mode().equals("ease")) return sample(chapter.keyframes(), seconds);
        Pose p = REPLAY_PATHS.computeIfAbsent(chapter, c -> new ReplayPath(c.keyframes(), c.mode())).sample(seconds);
        // Preserve the actual curve, including overshoot; only Minecraft's absolute bounds apply.
        return new Pose(Math.clamp(p.x(), -29_999_000, 29_999_000), Math.clamp(p.y(), -2048, 2048),
                Math.clamp(p.z(), -29_999_000, 29_999_000), p.yaw(), p.pitch(), p.fov());
    }

    public static Pose sample(List<TourDefinition.Keyframe> keys, double seconds) {
        if (seconds <= 0) return pose(keys.getFirst());
        for (int i = 1; i < keys.size(); i++) {
            var b = keys.get(i);
            if (seconds <= b.seconds()) {
                var a = keys.get(i - 1);
                double t = Math.clamp((seconds - a.seconds()) / (b.seconds() - a.seconds()), 0, 1);
                t = t * t * (3 - 2 * t);
                float yawDelta = (float) (((b.yaw() - a.yaw()) % 360 + 540) % 360 - 180);
                return new Pose(lerp(a.x(), b.x(), t), lerp(a.y(), b.y(), t), lerp(a.z(), b.z(), t),
                        (float) (a.yaw() + yawDelta * t), (float) lerp(a.pitch(), b.pitch(), t),
                        (float) lerp(a.fov(), b.fov(), t));
            }
        }
        return pose(keys.getLast());
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static Pose pose(TourDefinition.Keyframe k) { return new Pose(k.x(), k.y(), k.z(), k.yaw(), k.pitch(), k.fov()); }
}

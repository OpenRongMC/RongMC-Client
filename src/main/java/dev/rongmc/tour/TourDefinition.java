package dev.rongmc.tour;

import java.util.List;
import java.util.HashSet;

/** Wire/config format. All camera positions are absolute eye positions. */
public record TourDefinition(int version, String name, List<Chapter> chapters) {
    public record Anchor(double x, double y, double z) {}
    public record Keyframe(double seconds, double x, double y, double z,
                           float yaw, float pitch, float fov) {}
    public record Chapter(String id, String title, String description, String dimension,
                          Anchor anchor, List<Keyframe> keyframes, String interpolation) {
        public Chapter(String id, String title, String description, String dimension, Anchor anchor, List<Keyframe> keyframes) {
            this(id, title, description, dimension, anchor, keyframes, null);
        }
        public String mode() { return interpolation == null ? "ease" : interpolation; }
        public double duration() { return keyframes.getLast().seconds(); }
    }

    public void validate() {
        require(version >= 1, "version must be positive");
        text(name, 80, "name");
        require(chapters != null && !chapters.isEmpty() && chapters.size() <= 16, "expected 1..16 chapters");
        var ids = new HashSet<String>();
        int frameCount = 0;
        double totalDuration = 0;
        for (Chapter c : chapters) {
            require(c != null, "null chapter");
            require(c.id() != null && c.id().matches("[a-z0-9_-]{1,40}") && ids.add(c.id()), "invalid/duplicate chapter id");
            text(c.title(), 80, "title");
            text(c.description(), 1200, "description");
            require(c.dimension() != null && c.dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"), "invalid dimension");
            require(c.anchor() != null, "missing anchor");
            require(java.util.Set.of("ease", "linear", "catmull_rom").contains(c.mode()), "unknown interpolation");
            coordinate(c.anchor().x(), c.anchor().y(), c.anchor().z());
            require(c.keyframes() != null && c.keyframes().size() >= 2, "need at least two keyframes");
            double previous = -1;
            for (Keyframe k : c.keyframes()) {
                require(k != null, "null keyframe");
                coordinate(k.x(), k.y(), k.z());
                require(Double.isFinite(k.seconds()) && k.seconds() > previous && k.seconds() <= 180, "invalid keyframe time");
                require(previous < 0 || Math.round(k.seconds() * 1000) > Math.round(previous * 1000), "keyframes need at least 1ms spacing");
                if (previous == -1) require(k.seconds() == 0, "first keyframe must start at zero");
                require(Float.isFinite(k.yaw()) && Math.abs(k.yaw()) <= 360_000, "invalid yaw");
                require(Float.isFinite(k.pitch()) && Math.abs(k.pitch()) <= 85, "pitch must be within -85..85");
                require(Float.isFinite(k.fov()) && k.fov() >= 30 && k.fov() <= 100, "fov must be within 30..100");
                previous = k.seconds();
            }
            frameCount += c.keyframes().size();
            totalDuration += c.duration();
        }
        require(frameCount <= 128 && totalDuration <= 900, "tour exceeds frame/duration budget");
    }

    /** Compatibility check only; newer peers stream around the moving camera instead. */
    public boolean requiresStreaming() {
        return chapters.stream().anyMatch(c -> c.keyframes().stream().anyMatch(k ->
                Math.hypot(k.x() - c.anchor().x(), k.z() - c.anchor().z()) > 24
                        || Math.abs(k.y() - c.anchor().y()) > 48));
    }

    private static void coordinate(double x, double y, double z) {
        require(Double.isFinite(x) && Math.abs(x) <= 29_999_000 && Double.isFinite(z) && Math.abs(z) <= 29_999_000
                && Double.isFinite(y) && y >= -2048 && y <= 2048, "invalid coordinates");
    }

    private static void text(String value, int max, String label) {
        require(value != null && !value.isBlank() && value.length() <= max, "invalid " + label);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalArgumentException(message);
    }
}

package dev.rongmc.tour;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import dev.rongmc.thirdparty.replaystudio.pathing.impl.TimelineImpl;
import dev.rongmc.thirdparty.replaystudio.pathing.interpolation.CatmullRomSplineInterpolator;
import dev.rongmc.thirdparty.replaystudio.pathing.interpolation.Interpolator;
import dev.rongmc.thirdparty.replaystudio.pathing.interpolation.LinearInterpolator;
import dev.rongmc.thirdparty.replaystudio.pathing.path.Path;
import dev.rongmc.thirdparty.replaystudio.pathing.property.AbstractPropertyPart;
import dev.rongmc.thirdparty.replaystudio.pathing.property.Property;
import dev.rongmc.thirdparty.replaystudio.pathing.property.PropertyGroup;
import dev.rongmc.thirdparty.replaystudio.pathing.property.PropertyPart;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

/** RongMC adapter for the actual ReplayStudio keyframe/pathing implementation. */
public final class ReplayPath {
    private static final Scalar X = new Scalar("x", Double.NaN), Y = new Scalar("y", Double.NaN), Z = new Scalar("z", Double.NaN);
    private static final Scalar YAW = new Scalar("yaw", 360), PITCH = new Scalar("pitch", Double.NaN), FOV = new Scalar("fov", Double.NaN);
    private static final List<Scalar> PROPERTIES = List.of(X, Y, Z, YAW, PITCH, FOV);
    private final Path path;
    private final long duration;
    public ReplayPath(List<TourDefinition.Keyframe> keys, String mode) {
        var timeline = new TimelineImpl();
        path = timeline.createPath();
        for (Scalar property : PROPERTIES) timeline.registerProperty(property);
        for (var key : keys) {
            var frame = path.insert(Math.round(key.seconds() * 1000));
            frame.setValue(X, key.x()); frame.setValue(Y, key.y()); frame.setValue(Z, key.z());
            frame.setValue(YAW, (double) key.yaw()); frame.setValue(PITCH, (double) key.pitch()); frame.setValue(FOV, (double) key.fov());
        }
        Interpolator interpolator = mode.equals("linear") ? new LinearInterpolator() : new CatmullRomSplineInterpolator(.5);
        for (Scalar property : PROPERTIES) interpolator.registerProperty(property);
        for (var segment : path.getSegments()) segment.setInterpolator(interpolator);
        path.updateAll();
        duration = Math.round(keys.getLast().seconds() * 1000);
    }
    public CameraPath.Pose sample(double seconds) {
        long time = Math.clamp(Math.round(seconds * 1000), 0, duration);
        return new CameraPath.Pose(value(X, time), value(Y, time), value(Z, time),
                (float) value(YAW, time), (float) Math.clamp(value(PITCH, time), -85, 85),
                (float) Math.clamp(value(FOV, time), 30, 100));
    }
    private double value(Scalar property, long time) { return path.getValue(property, time).orElseThrow(); }

    private static final class Scalar implements Property<Double> {
        private final String id;
        private final List<PropertyPart<Double>> parts;
        Scalar(String id, double bound) {
            this.id = id;
            parts = List.of(new AbstractPropertyPart<Double>(this, true, bound) {
                @Override public double toDouble(Double value) { return value; }
                @Override public Double fromDouble(Double value, double d) { return d; }
            });
        }
        @Override public String getLocalizedName() { return id; }
        @Override public PropertyGroup getGroup() { return null; }
        @Override public String getId() { return id; }
        @Override public Double getNewValue() { return 0.0; }
        @Override public Collection<PropertyPart<Double>> getParts() { return parts; }
        @Override public void applyToGame(Double value, Object replayHandler) { /* RongMC applies the sampled pose to its own camera. */ }
        @Override public void toJson(JsonWriter writer, Double value) throws IOException { writer.value(value); }
        @Override public Double fromJson(JsonReader reader) throws IOException { return reader.nextDouble(); }
    }
}

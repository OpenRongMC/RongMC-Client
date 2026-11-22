package dev.rongmc.tour;

import dev.rongmc.tour.editor.CameraDraft;
import dev.rongmc.tour.editor.CameraProjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CameraEditorTest {
    private CameraPath.Pose pose(double x) { return new CameraPath.Pose(x, 66, 0, 179, 12, 65); }
    private CameraDraft draft() { return new CameraDraft("minecraft:overworld", new TourDefinition.Anchor(0, 64, 0)); }
    private CameraDraft recorded() {
        var d = draft(); d.record("minecraft:overworld", new TourDefinition.Anchor(0, 64, 0), pose(0));
        d.record("minecraft:overworld", new TourDefinition.Anchor(10, 64, 0), pose(10)); return d;
    }
    @Test void recordingCapturesFirstAnchorAndValidMillisecondTimeline() {
        var d = recorded();
        assertEquals(0, d.chapter().anchor().x());
        assertEquals(5, d.selected().seconds());
        d.project().validate();
        assertEquals("catmull_rom", d.chapter().mode());
    }
    @Test void failedCaptureDoesNotCorruptDraftOrUndoHistory() {
        var d = recorded(); var before = d.project();
        assertThrows(IllegalArgumentException.class, () -> d.record("minecraft:overworld", new TourDefinition.Anchor(30_000_001, 64, 0), pose(30_000_001)));
        assertEquals(before, d.project());
        assertThrows(IllegalArgumentException.class, () -> d.record("minecraft:the_nether", new TourDefinition.Anchor(0, 64, 0), pose(0)));
        assertEquals(before, d.project());
    }
    @Test void inspectorEditsAreValidatedAndUndoable() {
        var d = recorded(); var before = d.project();
        d.edit("Welcome", "A building", 12.5, 60, 2);
        assertEquals(12.5, d.selected().seconds()); assertEquals(2, d.project().version());
        d.undo(); assertEquals(before, d.project());
        d.redo(); assertEquals(60, d.selected().fov());
        assertThrows(IllegalArgumentException.class, () -> d.edit("x", "y", 0, 60, 2));
    }
    @Test void deletingFirstKeyRebasesTimelineAndCanBeUndone() {
        var d = recorded(); d.select(0); var before = d.project();
        d.removeSelected(); assertEquals(0, d.chapter().keyframes().getFirst().seconds());
        assertEquals(1, d.chapter().keyframes().size());
        d.undo(); assertEquals(before, d.project());
    }
    @Test void wholeDragIsOneUndoOperation() {
        var d = recorded(); var before = d.project();
        d.beginGesture(); for (int i = 0; i < 40; i++) d.retimeSelected(6 + i * .1); d.endGesture();
        assertTrue(d.selected().seconds() > 9); d.undo(); assertEquals(before, d.project());
        d.redo(); assertTrue(d.selected().seconds() > 9);
    }
    @Test void chaptersHaveIndependentDimensionsAndUndoRetainsTheirData() {
        var d = recorded(); var first = d.chapter();
        d.newChapter("minecraft:the_nether", new TourDefinition.Anchor(0, 70, 0));
        assertEquals(1, d.chapterIndex()); assertEquals(2, d.project().chapters().size());
        assertEquals(first, d.project().chapters().getFirst());
        d.undo(); assertEquals(1, d.project().chapters().size());
        d.redo(); assertEquals("minecraft:the_nether", d.chapter().dimension());
    }
    @Test void projectBackupsAndDisabledExportsRoundTrip(@TempDir Path temp) throws Exception {
        var store = new CameraProjectStore(temp); var d = recorded();
        var path = store.save("intro", d.project()); var before = Files.readString(path);
        d.edit("New", "Copy", 6, 60, 2); store.save("intro", d.project());
        assertEquals(before, Files.readString(path.resolveSibling("intro.json.bak")));
        assertEquals(d.project(), store.load("intro"));
        var exported = JsonFiles.read(store.export("intro", d.project()), CameraProjectStore.Export.class);
        assertFalse(exported.enabled()); assertEquals(d.project(), exported.tour());
    }
    @Test void incompleteDraftCanBeSavedButCannotOverwriteAnExport(@TempDir Path temp) throws Exception {
        var store = new CameraProjectStore(temp); var d = recorded(); var out = store.export("intro", d.project());
        String original = Files.readString(out); d.newChapter("minecraft:overworld", new TourDefinition.Anchor(0, 64, 0));
        store.save("draft", d.project());
        assertThrows(IllegalArgumentException.class, () -> store.export("intro", d.project()));
        assertEquals(original, Files.readString(out));
        assertThrows(IllegalArgumentException.class, () -> store.projectPath("../../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.exportPath("/etc/example"));
    }
    @Test void actualReplayStudioLinearInterpolatorUsesTheShortestYawArc() {
        var keys = List.of(new TourDefinition.Keyframe(0, 0, 66, 0, 179, 10, 60), new TourDefinition.Keyframe(10, 10, 66, 0, -179, 20, 70));
        var p = new ReplayPath(keys, "linear").sample(5);
        assertEquals(5, p.x(), .001); assertEquals(180, p.yaw(), .001); assertEquals(65, p.fov(), .001);
    }
    @Test void actualReplayStudioSplineIsNotClippedToTheOldSceneRadius() {
        var keys = List.of(new TourDefinition.Keyframe(0, 0, 66, 0, 0, 10, 60),
                new TourDefinition.Keyframe(2, 24, 66, 0, 0, 10, 60), new TourDefinition.Keyframe(4, 24, 66, 0, 0, 10, 60),
                new TourDefinition.Keyframe(6, 0, 66, 0, 0, 10, 60));
        assertTrue(new ReplayPath(keys, "catmull_rom").sample(3).x() > 24);
        var chapter = new TourDefinition.Chapter("x", "x", "x", "minecraft:overworld", new TourDefinition.Anchor(0, 64, 0), keys, "catmull_rom");
        assertTrue(CameraPath.sample(chapter, 3).x() > 24);
        assertEquals(24, CameraPath.sample(chapter, 2).x());
    }
    @Test void oneChapterCanRecordAndExportDistantAndElevatedKeyframes(@TempDir Path temp) throws Exception {
        var d = recorded();
        d.record("minecraft:overworld", new TourDefinition.Anchor(2048, 500, -1024),
                new CameraPath.Pose(2048, 501.62, -1024, 70, 20, 80));
        d.project().validate();
        assertEquals(3, d.chapter().keyframes().size());
        var end = CameraPath.sample(d.chapter(), 10);
        assertEquals(2048, end.x(), .0001);
        assertEquals(501.62, end.y(), .0001);
        var store = new CameraProjectStore(temp);
        store.save("long-route", d.project());
        assertEquals(d.project(), store.load("long-route"));
        assertEquals(d.project(), JsonFiles.read(store.export("long-route", d.project()), CameraProjectStore.Export.class).tour());
    }
    @Test void rejectsUnknownCurvesAndMillisecondCollisions() {
        var d = recorded(); var c = d.chapter();
        assertThrows(IllegalArgumentException.class, () -> new TourDefinition(1, "x", List.of(
                new TourDefinition.Chapter(c.id(), c.title(), c.description(), c.dimension(), c.anchor(), c.keyframes(), "unknown"))).validate());
        assertThrows(IllegalArgumentException.class, () -> new TourDefinition(1, "x", List.of(
                new TourDefinition.Chapter(c.id(), c.title(), c.description(), c.dimension(), c.anchor(), List.of(
                        new TourDefinition.Keyframe(0, 0, 66, 0, 0, 0, 70), new TourDefinition.Keyframe(.0001, 1, 66, 0, 0, 0, 70))))).validate());
    }
}

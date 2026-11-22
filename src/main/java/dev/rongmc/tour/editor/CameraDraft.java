package dev.rongmc.tour.editor;

import dev.rongmc.tour.CameraPath;
import dev.rongmc.tour.TourDefinition;
import java.util.*;

/** Immutable project snapshots with a bounded undo history; world-independent and unit-testable. */
public final class CameraDraft {
    private TourDefinition project;
    private record State(TourDefinition project, int chapter, int selected) {}
    private final Deque<State> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private int chapter, selected = -1;
    private State gestureBefore;
    public CameraDraft(String dimension, TourDefinition.Anchor anchor) {
        project = new TourDefinition(1, "RongMC · 相机路线", List.of(emptyChapter(1, dimension, anchor)));
    }
    public TourDefinition project() { return project; }
    public int chapterIndex() { return chapter; }
    public int selectedIndex() { return selected; }
    public TourDefinition.Chapter chapter() { return project.chapters().get(chapter); }
    public TourDefinition.Keyframe selected() { return selected < 0 ? null : chapter().keyframes().get(selected); }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    private State state() { return new State(project, chapter, selected); }
    private void restore(State state) { project = state.project(); chapter = state.chapter(); selected = state.selected(); reconcile(); }
    public void beginGesture() { if (gestureBefore == null) gestureBefore = state(); }
    public void endGesture() {
        if (gestureBefore != null && !gestureBefore.project().equals(project)) {
            undo.push(gestureBefore); if (undo.size() > 64) undo.removeLast(); redo.clear();
        }
        gestureBefore = null;
    }
    public void select(int index) { selected = Math.clamp(index, -1, chapter().keyframes().size() - 1); }
    public void selectChapter(int index) {
        chapter = Math.clamp(index, 0, project.chapters().size() - 1);
        selected = chapter().keyframes().isEmpty() ? -1 : 0;
    }
    public void load(TourDefinition value) { validateDraft(value); commit(value); chapter = 0; selectChapter(0); }
    public void record(String dimension, TourDefinition.Anchor body, CameraPath.Pose pose) {
        var c = chapter();
        boolean first = c.keyframes().isEmpty();
        if (!first && !c.dimension().equals(dimension)) throw new IllegalArgumentException("维度不同，请新建章节后取景。");
        double seconds = first ? 0 : c.keyframes().getLast().seconds() + 5;
        var keys = new ArrayList<>(c.keyframes());
        keys.add(key(seconds, pose));
        update(new TourDefinition.Chapter(c.id(), c.title(), c.description(), first ? dimension : c.dimension(),
                first ? body : c.anchor(), List.copyOf(keys), c.mode()));
        selected = keys.size() - 1;
    }
    public void replace(CameraPath.Pose pose, String dimension) {
        if (selected() == null) throw new IllegalArgumentException("请先选中一个机位。");
        if (!chapter().dimension().equals(dimension)) throw new IllegalArgumentException("请回到机位所在维度。");
        var keys = new ArrayList<>(chapter().keyframes());
        keys.set(selected, key(selected().seconds(), pose));
        update(copy(chapter(), keys));
    }
    public void edit(String title, String description, double seconds, float fov, int version) {
        var c = chapter();
        var keys = new ArrayList<>(c.keyframes());
        TourDefinition.Keyframe replacement = null;
        if (selected() != null) {
            if (selected == 0 && seconds != 0) throw new IllegalArgumentException("第一个机位必须从 0 秒开始。");
            var old = selected();
            replacement = new TourDefinition.Keyframe(seconds, old.x(), old.y(), old.z(), old.yaw(), old.pitch(), fov);
            keys.set(selected, replacement);
            keys.sort(Comparator.comparingDouble(TourDefinition.Keyframe::seconds));
        }
        var chapters = new ArrayList<>(project.chapters());
        chapters.set(chapter, new TourDefinition.Chapter(c.id(), title, description, c.dimension(), c.anchor(), List.copyOf(keys), c.mode()));
        commit(new TourDefinition(version, project.name(), List.copyOf(chapters)));
        if (replacement != null) selected = keys.indexOf(replacement);
    }
    public void retimeSelected(double seconds) {
        if (selected() == null || selected == 0) return;
        var keys = chapter().keyframes();
        double min = keys.get(selected - 1).seconds() + .05;
        double max = selected + 1 < keys.size() ? keys.get(selected + 1).seconds() - .05 : 180;
        if (min > max) return;
        seconds = Math.clamp(Math.round(seconds * 20) / 20.0, min, max);
        edit(chapter().title(), chapter().description(), seconds, selected().fov(), project.version());
    }
    public void removeSelected() {
        if (selected() == null) return;
        var keys = new ArrayList<>(chapter().keyframes());
        keys.remove(selected);
        if (!keys.isEmpty() && keys.getFirst().seconds() != 0) {
            double offset = keys.getFirst().seconds();
            keys.replaceAll(k -> new TourDefinition.Keyframe(k.seconds() - offset, k.x(), k.y(), k.z(), k.yaw(), k.pitch(), k.fov()));
        }
        update(copy(chapter(), keys));
        selected = Math.min(selected, keys.size() - 1);
    }
    public void newChapter(String dimension, TourDefinition.Anchor body) {
        var chapters = new ArrayList<>(project.chapters());
        int index = 1;
        Set<String> ids = new HashSet<>();
        chapters.forEach(c -> ids.add(c.id()));
        while (ids.contains("chapter_" + index)) index++;
        chapters.add(emptyChapter(index, dimension, body));
        commit(new TourDefinition(project.version(), project.name(), List.copyOf(chapters)));
        selectChapter(chapters.size() - 1);
    }
    public void removeChapter() {
        if (project.chapters().size() == 1) throw new IllegalArgumentException("至少保留一个章节。");
        var chapters = new ArrayList<>(project.chapters()); chapters.remove(chapter);
        commit(new TourDefinition(project.version(), project.name(), List.copyOf(chapters)));
        selectChapter(Math.min(chapter, chapters.size() - 1));
    }
    public void cycleInterpolation() {
        var c = chapter();
        String next = switch (c.mode()) { case "catmull_rom" -> "linear"; case "linear" -> "ease"; default -> "catmull_rom"; };
        update(new TourDefinition.Chapter(c.id(), c.title(), c.description(), c.dimension(), c.anchor(), c.keyframes(), next));
    }
    public void undo() { endGesture(); if (canUndo()) { redo.push(state()); restore(undo.pop()); } }
    public void redo() { endGesture(); if (canRedo()) { undo.push(state()); restore(redo.pop()); } }
    private void reconcile() { chapter = Math.min(chapter, project.chapters().size() - 1); select(Math.min(selected, chapter().keyframes().size() - 1)); }
    private void update(TourDefinition.Chapter value) {
        var chapters = new ArrayList<>(project.chapters()); chapters.set(chapter, value);
        commit(new TourDefinition(project.version(), project.name(), List.copyOf(chapters)));
    }
    private void commit(TourDefinition value) {
        validateDraft(value);
        if (value.equals(project)) return;
        if (gestureBefore == null) { undo.push(state()); if (undo.size() > 64) undo.removeLast(); redo.clear(); }
        project = value;
    }
    public static void validateDraft(TourDefinition value) {
        if (value == null || value.chapters() == null || value.chapters().isEmpty() || value.chapters().size() > 16)
            throw new IllegalArgumentException("草稿需要 1–16 个章节。");
        Set<String> ids = new HashSet<>(); int count = 0; double duration = 0;
        for (var c : value.chapters()) {
            if (c == null || c.anchor() == null || c.keyframes() == null || !ids.add(c.id())) throw new IllegalArgumentException("草稿章节数据无效。");
            count += c.keyframes().size();
            var keys = new ArrayList<>(c.keyframes());
            if (keys.isEmpty()) {
                var a = c.anchor(); keys.add(new TourDefinition.Keyframe(0, a.x(), a.y(), a.z(), 0, 0, 70));
            }
            if (keys.size() == 1) { var k = keys.getFirst(); keys.add(new TourDefinition.Keyframe(.05, k.x(), k.y(), k.z(), k.yaw(), k.pitch(), k.fov())); }
            new TourDefinition(value.version(), value.name(), List.of(copy(c, keys))).validate();
            if (!c.keyframes().isEmpty()) duration += c.keyframes().getLast().seconds();
        }
        if (count > 128 || duration > 900) throw new IllegalArgumentException("项目超出机位数或总时长预算。");
    }
    private static TourDefinition.Chapter emptyChapter(int index, String dimension, TourDefinition.Anchor anchor) {
        return new TourDefinition.Chapter("chapter_" + index, "章节 " + index, "在这里介绍这处建筑或服务器功能。", dimension, anchor, List.of(), "catmull_rom");
    }
    private static TourDefinition.Keyframe key(double seconds, CameraPath.Pose p) {
        return new TourDefinition.Keyframe(seconds, p.x(), p.y(), p.z(), p.yaw(), p.pitch(), p.fov());
    }
    private static TourDefinition.Chapter copy(TourDefinition.Chapter c, List<TourDefinition.Keyframe> keys) {
        return new TourDefinition.Chapter(c.id(), c.title(), c.description(), c.dimension(), c.anchor(), List.copyOf(keys), c.mode());
    }
}

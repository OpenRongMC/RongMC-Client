package dev.rongmc.tour.editor;

import dev.rongmc.tour.JsonFiles;
import dev.rongmc.tour.TourDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class CameraProjectStore {
    public record Export(boolean enabled, boolean autoStartOnFirstJoin, int maxConcurrent, TourDefinition tour) {}
    private final Path root;
    public CameraProjectStore(Path configDirectory) { root = configDirectory.resolve("rongmc-camera"); }
    public Path projectPath(String slot) { return slotPath("projects", slot); }
    public Path exportPath(String slot) { return slotPath("exports", slot); }
    private Path slotPath(String folder, String slot) {
        if (slot == null || !slot.matches("[a-z0-9_-]{1,40}")) throw new IllegalArgumentException("文件名只允许 1–40 位小写字母、数字、下划线与短横线。");
        return root.resolve(folder).resolve(slot + ".json");
    }
    public Path save(String slot, TourDefinition project) throws IOException {
        CameraDraft.validateDraft(project); Path path = projectPath(slot); backup(path); JsonFiles.write(path, project); return path;
    }
    public TourDefinition load(String slot) throws IOException {
        TourDefinition project = JsonFiles.read(projectPath(slot), TourDefinition.class); CameraDraft.validateDraft(project); return project;
    }
    public void autosave(TourDefinition project) throws IOException { CameraDraft.validateDraft(project); JsonFiles.write(root.resolve("autosave.json"), project); }
    public TourDefinition loadAutosave() throws IOException {
        Path path = root.resolve("autosave.json"); if (!Files.exists(path)) return null;
        var value = JsonFiles.read(path, TourDefinition.class); CameraDraft.validateDraft(value); return value;
    }
    public Path export(String slot, TourDefinition project) throws IOException {
        project.validate();
        if (JsonFiles.GSON.toJson(project).length() > dev.rongmc.tour.TourPackets.MAX_JSON) throw new IllegalArgumentException("导览内容超出数据包大小预算。");
        Path path = exportPath(slot); backup(path); JsonFiles.write(path, new Export(false, true, 2, project)); return path;
    }
    private static void backup(Path path) throws IOException {
        if (Files.exists(path)) Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
    }
}

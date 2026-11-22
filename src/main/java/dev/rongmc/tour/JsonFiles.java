package dev.rongmc.tour;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private JsonFiles() {}
    public static <T> T read(Path path, Class<T> type) throws IOException {
        if (Files.size(path) > 2_000_000) throw new IOException("JSON file exceeds size budget: " + path.getFileName());
        try (var reader = Files.newBufferedReader(path)) {
            T value = GSON.fromJson(reader, type);
            if (value == null) throw new IOException("Empty JSON: " + path.getFileName());
            return value;
        }
    }
    public static void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value));
        try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
    }
}

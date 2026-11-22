package dev.rongmc.client.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

public final class DeviceSetup {
    private static final String[] TIERS = {"minimum", "low", "low_medium", "medium", "high"};
    private final Path catalog;
    private final Path mods;
    private final Path selection;

    public DeviceSetup() {
        var loader = FabricLoader.getInstance();
        catalog = loader.getGameDir().resolve("rongmc-optional-mods");
        mods = loader.getGameDir().resolve("mods");
        selection = loader.getConfigDir().resolve("rongmc-device-tier.txt");
    }

    public void showOnFirstLaunch(Minecraft client) {
        if (client.gui.screen() instanceof TitleScreen title
                && Files.isRegularFile(catalog.resolve("tiers.properties")) && Files.notExists(selection)) {
            client.gui.setScreen(new DeviceSetupScreen(title, this));
        }
    }

    boolean offersTouchController() {
        return Files.isRegularFile(catalog.resolve("touchcontroller.jar"));
    }

    void select(int tier, boolean touchController) throws IOException {
        Properties manifest = new Properties();
        try (var reader = Files.newBufferedReader(catalog.resolve("tiers.properties"))) {
            manifest.load(reader);
        }
        List<Path> sources = new ArrayList<>();
        for (int level = 1; level <= tier; level++) {
            String group = manifest.getProperty(TIERS[level]);
            if (group == null || group.isBlank()) {
                throw new IOException("Missing optional mod group " + TIERS[level]);
            }
            for (String name : group.split("\\|", -1)) {
                if (!name.endsWith(".jar") || name.contains("/") || name.contains("\\") || name.contains(":")) {
                    throw new IOException("Invalid optional mod filename");
                }
                if (touchController && name.startsWith("spatial-gui-")) {
                    continue;
                }
                Path source = catalog.resolve(name);
                Path destination = mods.resolve(name);
                if (!Files.isRegularFile(source)) {
                    throw new IOException("Missing optional mod " + name);
                }
                if (Files.exists(destination) && Files.mismatch(source, destination) != -1) {
                    throw new IOException("A different file is already installed: " + name);
                }
                sources.add(source);
            }
        }

        if (touchController) {
            Path source = catalog.resolve("touchcontroller.jar");
            Path destination = mods.resolve(source.getFileName());
            if (!offersTouchController()) throw new IOException("Missing TouchController");
            if (Files.exists(destination) && Files.mismatch(source, destination) != -1) {
                throw new IOException("A different TouchController file is already installed");
            }
            sources.add(source);
        }

        Files.createDirectories(mods);
        Files.createDirectories(selection.getParent());
        List<Path> installed = new ArrayList<>();
        Path marker = Files.createTempFile(selection.getParent(), "rongmc-tier-", ".tmp");
        try {
            Files.writeString(marker, TIERS[tier] + "\n");
            for (Path source : sources) {
                Path destination = mods.resolve(source.getFileName());
                if (Files.exists(destination)) continue;
                Path staged = Files.createTempFile(mods, "rongmc-mod-", ".tmp");
                try {
                    Files.copy(source, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Files.move(staged, destination);
                    installed.add(destination);
                } finally {
                    Files.deleteIfExists(staged);
                }
            }
            Files.move(marker, selection);
        } catch (IOException failure) {
            for (Path path : installed) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        } finally {
            Files.deleteIfExists(marker);
        }
    }
}

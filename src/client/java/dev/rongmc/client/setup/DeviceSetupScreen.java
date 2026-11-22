package dev.rongmc.client.setup;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import dev.rongmc.client.RongMcClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DeviceSetupScreen extends Screen {
    private static final String PREFIX = "rongmc.setup.";
    private final Screen previous;
    private final DeviceSetup setup;
    private int tier;
    private boolean touchController;
    private boolean applying;
    private boolean restart;
    private boolean failed;

    DeviceSetupScreen(Screen previous, DeviceSetup setup) {
        super(Component.translatable(PREFIX + "title"));
        this.previous = previous;
        this.setup = setup;
    }

    @Override protected void init() {
        int panel = Math.min(width - 24, 360);
        int left = (width - panel) / 2;
        if (restart) {
            addRenderableWidget(Button.builder(Component.translatable(PREFIX + "quit"), b -> minecraft.stop())
                    .bounds(left, height / 2 + 25, panel, 24).build());
            return;
        }
        int columns = height >= 210 ? 1 : height >= 160 ? 2 : 3;
        int buttonWidth = (panel - (columns - 1) * 6) / columns;
        for (int i = 0; i < 5; i++) {
            int option = i;
            Component label = Component.translatable(PREFIX + "tier." + i);
            addRenderableWidget(Button.builder(i == tier ? Component.literal("● ").append(label) : label, b -> {
                tier = option;
                failed = false;
                rebuildWidgets();
            }).bounds(left + i % columns * (buttonWidth + 6), 30 + i / columns * 24, buttonWidth, 22).build()).active = !applying;
        }
        int confirmWidth = panel;
        int confirmLeft = left;
        if (setup.offersTouchController()) {
            int toggleWidth = (panel - 6) / 2;
            addRenderableWidget(Button.builder(Component.translatable(PREFIX + (touchController ? "touch.on" : "touch.off")), b -> {
                touchController = !touchController;
                failed = false;
                rebuildWidgets();
            }).bounds(left, height - 29, toggleWidth, 23).build()).active = !applying;
            confirmLeft += toggleWidth + 6;
            confirmWidth -= toggleWidth + 6;
        }
        addRenderableWidget(Button.builder(Component.translatable(PREFIX + "confirm"), b -> apply())
                .bounds(confirmLeft, height - 29, confirmWidth, 23).build()).active = !applying;
    }

    private void apply() {
        applying = true;
        rebuildWidgets();
        CompletableFuture.supplyAsync(() -> {
            try {
                setup.select(tier, touchController);
                return true;
            } catch (IOException error) {
                RongMcClient.LOGGER.error("Could not apply RongMC optional mods", error);
                return false;
            }
        }).thenAccept(success -> minecraft.execute(() -> {
            applying = false;
            failed = !success;
            if (success && tier == 0 && !touchController) {
                minecraft.gui.setScreen(previous);
            } else {
                restart = success;
                rebuildWidgets();
            }
        }));
    }

    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        graphics.fill(0, 0, width, height, 0xFF10171B);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        super.extractRenderState(graphics, x, y, delta);
        drawCentered(graphics, getTitle(), restart ? height / 2 - 27 : 12, 0xFFE7C68C);
        Component detail = Component.translatable(PREFIX + (restart ? "restart" : applying ? "applying" : failed ? "error" : "detail." + tier));
        int available = Math.min(width - 24, 360);
        var lines = font.split(detail, available);
        int lineY = restart ? height / 2 - 8 : height - 32 - lines.size() * 11;
        for (var line : lines) {
            graphics.text(font, line, (width - font.width(line)) / 2, lineY, 0xFFF6F3ED, false);
            lineY += 11;
        }
    }

    private void drawCentered(GuiGraphicsExtractor graphics, Component text, int y, int color) {
        graphics.text(font, text, (width - font.width(text)) / 2, y, color, false);
    }
}

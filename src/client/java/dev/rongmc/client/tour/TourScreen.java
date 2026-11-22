package dev.rongmc.client.tour;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A non-pausing in-world screen: the existing Minecraft world is the only background renderer. */
public final class TourScreen extends Screen {
    private static final int GOLD = 0xFFE7C68C, WHITE = 0xFFF6F3ED, MUTED = 0xFFC2C9C8;
    private final TourController tour;
    private Button back, next, pause, skip, quality, motion, retry;
    private int descriptionScroll, maxScroll, lastChapter = -1;
    public TourScreen(TourController tour) { super(Component.literal("RongMC")); this.tour = tour; }

    @Override protected void init() {
        int edge = Math.max(12, Math.min(32, width / 24));
        int gap = 6;
        int buttonWidth = Math.min(100, (width - 2 * edge - 3 * gap) / 4);
        int y = height - 34;
        back = addRenderableWidget(new TourButton(edge, y, buttonWidth, "rongmc.tour.back", false, b -> tour.navigate(-1)));
        pause = addRenderableWidget(new TourButton(edge + buttonWidth + gap, y, buttonWidth, "rongmc.tour.pause", false, b -> tour.togglePause()));
        next = addRenderableWidget(new TourButton(edge + 2 * (buttonWidth + gap), y, buttonWidth, "rongmc.tour.next", true, b -> {
            if (tour.chapterIndex() == tour.definition().chapters().size() - 1) tour.exit(true); else tour.navigate(1);
        }));
        skip = addRenderableWidget(new TourButton(edge + 3 * (buttonWidth + gap), y, buttonWidth, "rongmc.tour.skip", false, b -> tour.exit(false)));
        quality = addRenderableWidget(new TourButton(edge, 42, Math.min(140, width / 2 - edge - gap), "rongmc.tour.quality.auto", false, b -> tour.cycleQuality()));
        motion = addRenderableWidget(new TourButton(edge + quality.getWidth() + gap, 42, Math.min(140, width / 2 - edge - gap), "rongmc.tour.motion", false, b -> tour.toggleReducedMotion()));
        retry = addRenderableWidget(new TourButton(edge, height - 65, buttonWidth + 20, "rongmc.tour.retry", true, b -> tour.retry()));
    }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) { /* Intentionally no vanilla blur/menu background. */ }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    @Override public void onClose() { tour.exit(false); }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (!tour.active()) return;
        boolean playing = tour.interactive();
        int edge = Math.max(12, Math.min(32, width / 24));
        if (playing) {
            g.fillGradient(0, 0, width, Math.min(height, 90), 0xC810171B, 0x0010171B);
            g.fillGradient(0, height / 3, width, height, 0x0010171B, 0xF010171B);
        } else g.fill(0, 0, width, height, 0xFF10171B);
        g.fill(edge, 21, edge + 3, 31, GOLD);
        g.text(font, "R O N G M C", edge + 11, 22, WHITE, false);
        String tag = String.format(java.util.Locale.ROOT, "%02d / %02d", tour.chapterIndex() + 1, tour.definition().chapters().size());
        g.text(font, tag, width - edge - font.width(tag), 22, GOLD, false);
        int textWidth = Math.max(100, Math.min(360, width - edge * 2));
        if (playing) {
            int headingY = Math.max(85, height - 148);
            g.text(font, "━━  " + tour.definition().name(), edge, headingY, GOLD, false);
            int lineY = headingY + 22;
            for (var line : font.split(Component.literal(tour.chapter().title()), textWidth)) {
                g.text(font, line, edge, lineY, WHITE, true); lineY += 12;
            }
            lineY += 8;
            if (lastChapter != tour.chapterIndex()) { descriptionScroll = 0; lastChapter = tour.chapterIndex(); }
            var lines = font.split(Component.literal(tour.chapter().description()), textWidth);
            int bodyBottom = height - 57;
            int visibleLines = Math.max(1, (bodyBottom - lineY) / 11);
            maxScroll = Math.max(0, lines.size() - visibleLines);
            descriptionScroll = Math.clamp(descriptionScroll, 0, maxScroll);
            g.enableScissor(edge, lineY, edge + textWidth, Math.max(lineY + 1, bodyBottom));
            for (int i = descriptionScroll; i < Math.min(lines.size(), descriptionScroll + visibleLines); i++) {
                g.text(font, lines.get(i), edge, lineY, MUTED, false); lineY += 11;
            }
            g.disableScissor();
            if (maxScroll > 0) g.text(font, Component.translatable("rongmc.tour.scroll"), edge, height - 56, MUTED, false);
            g.fill(edge, height - 47, width - edge, height - 45, 0x445E6B6B);
            g.fill(edge, height - 47, edge + (int) ((width - 2 * edge) * tour.progress()), height - 45, GOLD);
        } else {
            int y = height / 2 - 28;
            String key = switch (tour.phase()) {
                case WAITING -> "rongmc.tour.waiting";
                case LOADING -> "rongmc.tour.loading";
                case CALIBRATING -> "rongmc.tour.calibrating";
                case ERROR -> "rongmc.tour.error";
                case EXITING -> "rongmc.tour.exiting";
                default -> "rongmc.tour.loading";
            };
            g.text(font, Component.translatable(key), edge, y, WHITE, false);
            String details = tour.phase() == TourController.Phase.ERROR ? tour.error()
                    : Component.translatable("rongmc.tour.loading.detail").getString();
            int lineY = y + 23;
            for (var line : font.split(Component.literal(details), textWidth)) {
                g.text(font, line, edge, lineY, MUTED, false); lineY += 12;
            }
            if (tour.phase() != TourController.Phase.ERROR && tour.phase() != TourController.Phase.EXITING) {
                int available = Math.min(210, width - 2 * edge);
                int bar = (int) ((System.nanoTime() / 5_000_000L) % Math.max(1, available - 30));
                g.fill(edge, lineY + 14, edge + available, lineY + 16, 0xFF344145);
                g.fill(edge + bar, lineY + 14, edge + bar + 30, lineY + 16, GOLD);
            }
        }
        back.active = playing && tour.chapterIndex() > 0;
        pause.active = playing;
        next.active = playing;
        skip.active = tour.phase() != TourController.Phase.EXITING;
        quality.active = playing;
        motion.active = playing;
        retry.visible = tour.phase() == TourController.Phase.ERROR;
        retry.active = retry.visible;
        pause.setMessage(Component.translatable(tour.paused() ? "rongmc.tour.resume" : "rongmc.tour.pause"));
        next.setMessage(Component.translatable(tour.chapterIndex() == tour.definition().chapters().size() - 1 ? "rongmc.tour.finish" : "rongmc.tour.next"));
        var selectedTier = Component.translatable("rongmc.tour.quality." + tour.tier().name().toLowerCase(java.util.Locale.ROOT));
        quality.setMessage(Component.translatable("rongmc.tour.quality", tour.automatic()
                ? Component.translatable("rongmc.tour.quality.auto.selected", selectedTier) : selectedTier));
        motion.setMessage(Component.translatable(tour.reducedMotion() ? "rongmc.tour.motion.on" : "rongmc.tour.motion"));
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (tour.interactive() && maxScroll > 0) {
            descriptionScroll = Math.clamp(descriptionScroll - (int) Math.signum(vertical) * 2, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private static final class TourButton extends Button {
        private final boolean primary;
        TourButton(int x, int y, int width, String key, boolean primary, OnPress action) {
            super(x, y, width, 22, Component.translatable(key), action, DEFAULT_NARRATION);
            this.primary = primary;
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int background = primary ? (isHoveredOrFocused() ? 0xFFF5DCAB : GOLD) : (isHoveredOrFocused() ? 0xF0404C50 : 0xD01E292E);
            if (!active) background = 0xB020292D;
            g.fill(x, y, x + w, y + h, background);
            g.fill(x, y + h - 1, x + w, y + h, active ? (primary ? GOLD : 0xFF596466) : 0xFF344045);
            var font = net.minecraft.client.Minecraft.getInstance().font;
            String label = font.plainSubstrByWidth(getMessage().getString(), Math.max(1, w - 10));
            g.text(font, label, x + (w - font.width(label)) / 2, y + 7,
                    active ? (primary ? 0xFF152026 : WHITE) : 0xFF778385, false);
        }
    }
}

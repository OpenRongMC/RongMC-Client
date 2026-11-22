package dev.rongmc.client.editor;

import dev.rongmc.tour.CameraPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.sdl.SDLKeycode;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

/** Purpose-built live-world path editor; no ReplayMod GUI or replay world is required. */
public final class CameraEditorScreen extends Screen {
    private static final int GOLD = 0xFFE7C68C, WHITE = 0xFFF3F3ED, MUTED = 0xFFB5C4C9;
    private final CameraEditorController editor;
    private EditBox file, title, description, seconds, fov, version;
    private Button record, replace, play, stop, undo, redo, previousChapter, nextChapter, mode;
    private final List<Setting> settings = new ArrayList<>();
    private int edge, panelWidth, panelTop, panelBottom, scroll;
    private record Setting(net.minecraft.client.gui.components.AbstractWidget widget, int offset) {}
    public CameraEditorScreen(CameraEditorController editor) { super(Component.translatable("rongmc.camera.title")); this.editor = editor; }

    @Override protected void init() {
        settings.clear();
        edge = Math.max(10, Math.min(20, width / 32));
        panelWidth = Math.min(212, Math.max(158, width * 43 / 100));
        panelTop = 68; panelBottom = height - 87;
        int available = width - 2 * edge, gap = 4, topW = Math.min(88, (available - gap * 5) / 6);
        button(edge, 40, topW, "view", b -> { applyFields(); editor.closeScreen(); });
        record = button(edge + (topW + gap), 40, topW, "record", b -> { if (applyFields()) editor.record(); });
        replace = button(edge + 2 * (topW + gap), 40, topW, "replace", b -> { if (applyFields()) editor.replace(); });
        play = button(edge + 3 * (topW + gap), 40, topW, "play", b -> { if (editor.previewActive() || applyFields()) editor.togglePlay(); });
        stop = button(edge + 4 * (topW + gap), 40, topW, "stop", b -> editor.endPreview());
        button(edge + 5 * (topW + gap), 40, topW, "new_chapter", b -> { if (applyFields()) editor.newChapter(); });
        file = addRenderableWidget(new EditBox(font, width - edge - 124, 13, 124, 18, tr("file")));
        file.setMaxLength(40); file.setValue(editor.slot()); file.setHint(Component.literal("intro"));
        file.setResponder(editor::slot); file.setTooltip(Tooltip.create(tr("file.hint")));
        int x = edge + 8, w = panelWidth - 16, half = (w - 6) / 2;
        previousChapter = setting(button(x, 0, 58, "previous_chapter", b -> { if (applyFields()) editor.chapter(-1); }), 6);
        nextChapter = setting(button(x + w - 58, 0, 58, "next_chapter", b -> { if (applyFields()) editor.chapter(1); }), 6);
        title = setting(field(x, 0, w, "chapter_title", 80), 34);
        description = setting(field(x, 0, w, "description", 1200), 58);
        setting(button(x, 0, half, "previous_frame", b -> { if (applyFields()) editor.select(editor.draft().selectedIndex() - 1); }), 84);
        setting(button(x + half + 6, 0, half, "next_frame", b -> { if (applyFields()) editor.select(editor.draft().selectedIndex() + 1); }), 84);
        seconds = setting(field(x, 0, half, "seconds", 12), 122);
        fov = setting(field(x + half + 6, 0, half, "fov", 8), 122);
        setting(button(x, 0, half, "apply", b -> applyFields()), 148);
        setting(button(x + half + 6, 0, half, "delete_frame", b -> editor.removeKeyframe()), 148);
        mode = setting(button(x, 0, w, "mode", b -> { if (applyFields()) editor.mode(); }), 174);
        version = setting(field(x, 0, half, "version", 8), 212);
        setting(button(x + half + 6, 0, half, "delete_chapter", b -> editor.removeChapter()), 212);
        addRenderableWidget(new Timeline(edge, height - 68, available, 31));
        int bottomW = Math.min(90, (available - 5 * gap) / 6), bottom = height - 27;
        undo = button(edge, bottom, bottomW, "undo", b -> editor.undo());
        redo = button(edge + bottomW + gap, bottom, bottomW, "redo", b -> editor.redo());
        button(edge + 2 * (bottomW + gap), bottom, bottomW, "save", b -> { if (applyFields()) editor.save(); });
        button(edge + 3 * (bottomW + gap), bottom, bottomW, "load", b -> editor.load());
        button(edge + 4 * (bottomW + gap), bottom, bottomW, "export", b -> { if (applyFields()) editor.export(); });
        button(edge + 5 * (bottomW + gap), bottom, bottomW, "new_project", b -> editor.newProject());
        syncFields(); positionSettings();
    }
    private EditBox field(int x, int y, int w, String key, int max) {
        var field = addRenderableWidget(new EditBox(font, x, y, w, 18, tr(key)));
        field.setMaxLength(max); field.setHint(tr(key)); field.setTooltip(Tooltip.create(tr(key))); return field;
    }
    private <T extends net.minecraft.client.gui.components.AbstractWidget> T setting(T widget, int offset) { settings.add(new Setting(widget, offset)); return widget; }
    private Button button(int x, int y, int width, String key, Button.OnPress action) {
        return addRenderableWidget(new FlatButton(x, y, width, tr(key), action));
    }
    public void syncFields() {
        if (title == null) return;
        var draft = editor.draft(); var c = draft.chapter(); var key = draft.selected();
        title.setValue(c.title()); description.setValue(c.description());
        seconds.setValue(key == null ? "0" : format(key.seconds()));
        fov.setValue(key == null ? "70" : format(key.fov())); version.setValue(Integer.toString(draft.project().version()));
    }
    private boolean applyFields() {
        return title != null && editor.apply(title.getValue(), description.getValue(), seconds.getValue(), fov.getValue(), version.getValue());
    }
    private void positionSettings() {
        scroll = Math.clamp(scroll, 0, Math.max(0, 240 - (panelBottom - panelTop)));
        for (var item : settings) {
            var w = item.widget(); w.setY(panelTop + item.offset() - scroll);
            w.visible = w.getY() >= panelTop + 2 && w.getY() + w.getHeight() <= panelBottom - 2;
        }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    @Override public void extractBackground(GuiGraphicsExtractor g, int x, int y, float f) {}
    @Override public void onClose() { editor.closeScreen(); }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == SDLKeycode.SDLK_F8) { onClose(); return true; }
        if (!(getFocused() instanceof EditBox)) {
            boolean control = (event.modifiers() & (SDLKeycode.SDL_KMOD_CTRL | SDLKeycode.SDL_KMOD_GUI)) != 0;
            if (control && event.key() == SDLKeycode.SDLK_Z) {
                if (event.hasShiftDown()) editor.redo(); else editor.undo(); return true;
            }
            if (control && event.key() == SDLKeycode.SDLK_Y) { editor.redo(); return true; }
            if (control && event.key() == SDLKeycode.SDLK_S) { if (applyFields()) editor.save(); return true; }
            if (event.key() == SDLKeycode.SDLK_SPACE) { editor.togglePlay(); return true; }
            if (event.key() == SDLKeycode.SDLK_DELETE) { editor.removeKeyframe(); return true; }
        }
        return super.keyPressed(event);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= edge && x <= edge + panelWidth && y >= panelTop && y <= panelBottom) {
            scroll -= (int) (vertical * 20); positionSettings(); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        var draft = editor.draft();
        if (editor.loadingPreview()) g.fill(0, 0, width, height, 0xFF10171B);
        g.fillGradient(0, 0, width, 90, 0xED111B22, 0x00111B22);
        g.fillGradient(0, height - 106, width, height, 0x00111B22, 0xFA111B22);
        g.text(font, "RONGMC / CAMERA", edge, 18, GOLD, false);
        g.fill(edge, panelTop, edge + panelWidth, panelBottom, 0xE018252D);
        positionSettings();
        int labelY = panelTop + 110 - scroll;
        if (labelY >= panelTop && labelY + 9 < panelBottom) {
            g.text(font, tr("seconds"), edge + 8, labelY, MUTED, false);
            g.text(font, "FOV", edge + panelWidth / 2 + 3, labelY, MUTED, false);
        }
        labelY = panelTop + 200 - scroll;
        if (labelY >= panelTop && labelY + 9 < panelBottom) g.text(font, tr("version"), edge + 8, labelY, MUTED, false);
        if (previousChapter.visible) {
            String number = (draft.chapterIndex() + 1) + "/" + draft.project().chapters().size();
            g.text(font, number, edge + (panelWidth - font.width(number)) / 2, previousChapter.getY() + 7, GOLD, false);
        }
        int right = edge + panelWidth + 12, rightW = width - edge - right;
        int infoY = 76;
        g.text(font, tr("engine"), right, infoY, GOLD, false);
        infoY += 17;
        String selection = Component.translatable("rongmc.camera.selection", Math.max(0, draft.selectedIndex() + 1), draft.chapter().keyframes().size()).getString();
        g.text(font, selection, right, infoY, WHITE, false);
        infoY += 16;
        for (var line : font.split(Component.literal(editor.status()), Math.max(50, rightW))) {
            if (infoY > Math.min(height - 105, 170)) break;
            g.text(font, line, right, infoY, MUTED, false); infoY += 11;
        }
        if (width >= 600 && height >= 320) map(g, right, Math.max(160, infoY + 10), Math.min(160, rightW), Math.min(80, height - 108 - Math.max(160, infoY + 10)));
        g.text(font, Component.translatable("rongmc.camera.timeline", format(editor.time())), edge, height - 82, GOLD, false);
        record.active = !editor.previewActive(); replace.active = !editor.previewActive() && draft.selected() != null;
        stop.active = editor.previewActive(); play.active = draft.chapter().keyframes().size() >= 2;
        play.setMessage(tr(editor.playing() ? "pause" : "play"));
        undo.active = draft.canUndo(); redo.active = draft.canRedo();
        previousChapter.active = draft.chapterIndex() > 0;
        nextChapter.active = draft.chapterIndex() + 1 < draft.project().chapters().size();
        mode.setMessage(Component.translatable("rongmc.camera.curve", Component.translatable("rongmc.camera.mode." + draft.chapter().mode())));
        super.extractRenderState(g, mouseX, mouseY, partial);
        if (240 > panelBottom - panelTop) g.text(font, tr("scroll"), edge + 8, panelBottom - 10, 0xFF8F9DA5, false);
    }
    private void map(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (h < 45) return;
        g.fill(x, y, x + w, y + h, 0xBF102027);
        g.text(font, tr("map"), x + 6, y + 5, MUTED, false);
        var c = editor.draft().chapter(); var a = c.anchor();
        // Fit the complete route; a fixed 52-block scale could issue millions of draw calls for a long path.
        var samples = new java.util.ArrayList<CameraPath.Pose>();
        double minX = a.x(), maxX = a.x(), minZ = a.z(), maxZ = a.z();
        if (c.keyframes().size() >= 2)
            for (int i = 0; i <= 48; i++) samples.add(CameraPath.sample(c, c.duration() * i / 48.0));
        for (var p : samples) { minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x()); minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z()); }
        for (var p : c.keyframes()) { minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x()); minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z()); }
        double middleX = (minX + maxX) / 2, middleZ = (minZ + maxZ) / 2;
        double scale = Math.max(1, Math.max((maxX - minX) / Math.max(1, w - 16), (maxZ - minZ) / Math.max(1, h - 30)));
        int cx = x + w / 2, cy = y + 15 + (h - 15) / 2;
        g.fill(cx, y + 17, cx + 1, y + h - 3, 0xFF34464F); g.fill(x + 4, cy, x + w - 4, cy + 1, 0xFF34464F);
        if (c.keyframes().size() >= 2) {
            CameraPath.Pose previous = samples.getFirst();
            for (int i = 1; i < samples.size(); i++) {
                var p = samples.get(i);
                line(g, cx + (int) ((previous.x() - middleX) / scale), cy + (int) ((previous.z() - middleZ) / scale),
                        cx + (int) ((p.x() - middleX) / scale), cy + (int) ((p.z() - middleZ) / scale), 0xFF9DB1B7);
                previous = p;
            }
        }
        for (int i = 0; i < c.keyframes().size(); i++) {
            var p = c.keyframes().get(i);
            int px = cx + (int) ((p.x() - middleX) / scale), py = cy + (int) ((p.z() - middleZ) / scale);
            g.fill(px - 2, py - 2, px + 2, py + 2, i == editor.draft().selectedIndex() ? GOLD : WHITE);
        }
    }
    private static void line(GuiGraphicsExtractor g, int x, int y, int tx, int ty, int color) {
        int steps = Math.max(Math.abs(tx - x), Math.abs(ty - y));
        for (int i = 0; i <= steps; i++) {
            int px = steps == 0 ? x : x + (tx - x) * i / steps, py = steps == 0 ? y : y + (ty - y) * i / steps;
            g.fill(px, py, px + 1, py + 1, color);
        }
    }
    private static Component tr(String key) { return Component.translatable("rongmc.camera." + key); }
    private static String format(double n) { return java.math.BigDecimal.valueOf(Math.round(n * 1000) / 1000.0).stripTrailingZeros().toPlainString(); }

    private final class Timeline extends Button {
        private boolean draggingFrame;
        Timeline(int x, int y, int w, int h) { super(x, y, w, h, tr("timeline_label"), b -> {}, DEFAULT_NARRATION); }
        private double end() {
            var keys = editor.draft().chapter().keyframes();
            return keys.isEmpty() ? 10 : Math.max(10, Math.ceil(keys.getLast().seconds() / 10) * 10);
        }
        private int position(double seconds) { return getX() + 8 + (int) ((getWidth() - 16) * seconds / end()); }
        private double time(double x) { return Math.clamp((x - getX() - 8) / (getWidth() - 16) * end(), 0, 180); }
        @Override protected void extractContents(GuiGraphicsExtractor g, int x, int y, float partial) {
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xE018252D);
            g.fill(getX() + 8, getY() + 21, getX() + getWidth() - 8, getY() + 22, 0xFF5D727C);
            for (int i = 0; i <= 4; i++) {
                double t = end() * i / 4;
                int px = position(t);
                g.fill(px, getY() + 13, px + 1, getY() + 27, 0xFF3C515D);
                String label = String.format(java.util.Locale.ROOT, "%.1fs", t);
                g.text(font, label, Math.clamp(px - font.width(label) / 2, getX() + 3, getX() + getWidth() - font.width(label) - 3), getY() + 3, MUTED, false);
            }
            var keys = editor.draft().chapter().keyframes();
            for (int i = 0; i < keys.size(); i++) {
                int px = position(keys.get(i).seconds());
                int c = i == editor.draft().selectedIndex() ? GOLD : WHITE;
                g.fill(px - 3, getY() + 18, px + 4, getY() + 25, c);
            }
            int cursor = position(Math.min(end(), editor.time()));
            g.fill(cursor, getY() + 12, cursor + 1, getY() + 29, 0xFFE07C6A);
        }
        @Override public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (!applyFields()) return;
            var keys = editor.draft().chapter().keyframes(); int nearest = -1; double distance = 8;
            for (int i = 0; i < keys.size(); i++) {
                double d = Math.abs(position(keys.get(i).seconds()) - event.x());
                if (d < distance) { nearest = i; distance = d; }
            }
            draggingFrame = nearest >= 0;
            if (draggingFrame) { editor.select(nearest); editor.beginDrag(); editor.scrub(keys.get(nearest).seconds()); }
            else editor.scrub(time(event.x()));
        }
        @Override protected void onDrag(MouseButtonEvent event, double dx, double dy) {
            if (draggingFrame) editor.dragTime(time(event.x())); else editor.scrub(time(event.x()));
        }
        @Override public void onRelease(MouseButtonEvent event) { if (draggingFrame) editor.endDrag(); draggingFrame = false; }
    }
    private static final class FlatButton extends Button {
        FlatButton(int x, int y, int width, Component message, OnPress press) { super(x, y, width, 20, message, press, DEFAULT_NARRATION); }
        @Override protected void extractContents(GuiGraphicsExtractor g, int x, int y, float f) {
            int fill = !active ? 0xC01C282E : isHoveredOrFocused() ? 0xF0465D66 : 0xE0263842;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
            g.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), active ? 0xFF8D9B9D : 0xFF35434A);
            var font = net.minecraft.client.Minecraft.getInstance().font;
            String text = font.plainSubstrByWidth(getMessage().getString(), Math.max(1, getWidth() - 8));
            g.text(font, text, getX() + (getWidth() - font.width(text)) / 2, getY() + 6, active ? WHITE : 0xFF788991, false);
        }
    }
}

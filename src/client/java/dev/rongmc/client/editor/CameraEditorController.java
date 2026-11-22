package dev.rongmc.client.editor;

import dev.rongmc.client.RongMcClient;
import dev.rongmc.client.tour.OptionsSnapshot;
import dev.rongmc.tour.*;
import dev.rongmc.tour.editor.CameraDraft;
import dev.rongmc.tour.editor.CameraProjectStore;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.LinkedHashSet;

/** Local editing only. The server authorizes entry; projects never write server configuration. */
public final class CameraEditorController {
    private CameraDraft draft;
    private final CameraProjectStore store = new CameraProjectStore(FabricLoader.getInstance().getConfigDir());
    private OptionsSnapshot snapshot;
    private CameraEditorScreen screen;
    private CameraPath.Pose pose;
    private boolean authorized, playing;
    private UUID previewSession;
    private final LinkedHashSet<UUID> retiredPreviews = new LinkedHashSet<>();
    private boolean serverScene, loading;
    private long lastCursor, previewRequested;
    private double time;
    private long lastFrame;
    private String status = "F7 记录机位；F8 打开编辑器。";
    private String slot = "intro";

    public CameraDraft draft() { return draft; }
    public String status() { return status; }
    public void status(String text) { status = text; }
    public String slot() { return slot; }
    public void slot(String value) { slot = value; }
    public double time() { return time; }
    public boolean previewActive() { return snapshot != null; }
    public boolean playing() { return playing; }
    public boolean loadingPreview() { return previewActive() && loading; }
    public CameraPath.Pose pose() { return pose; }

    public void request(TourPackets.EditorAction action) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (action == TourPackets.EditorAction.OPEN && mc.gui.screen() instanceof CameraEditorScreen) { closeScreen(); return; }
        if (RongMcClient.tour().active()) { message("请先退出欢迎导览。"); return; }
        if (!ClientPlayNetworking.canSend(TourPackets.EditorRequest.TYPE)) { message("该世界需要安装 RongMC 服务端配套模组。"); return; }
        ClientPlayNetworking.send(new TourPackets.EditorRequest(action));
    }
    public void reply(TourPackets.EditorReply packet) {
        if (!packet.allowed()) { message(packet.reason()); return; }
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || RongMcClient.tour().active()) return;
        authorized = true;
        ensureDraft();
        if (packet.action() == TourPackets.EditorAction.RECORD) record();
        else {
            screen = new CameraEditorScreen(this);
            mc.gui.setScreen(screen); KeyMapping.releaseAll();
        }
    }
    private void ensureDraft() {
        if (draft != null) return;
        var mc = Minecraft.getInstance();
        draft = new CameraDraft(dimension(), anchor());
        try {
            var saved = store.loadAutosave();
            if (saved != null) { draft.load(saved); status = "已恢复上次草稿，请确认当前世界和章节。"; }
        } catch (Exception e) { status = "上次草稿未能载入，原文件保留：" + e.getMessage(); RongMcClient.LOGGER.warn("Camera autosave could not be read", e); }
    }
    public void record() {
        if (previewActive()) { message("请先停止预览，再取景记录。 "); return; }
        mutate(() -> { draft.record(dimension(), anchor(), currentPose()); time = draft.selected().seconds(); }, "已添加机位；下一个机位默认间隔 5 秒。");
    }
    public void replace() {
        if (previewActive()) { message("请先停止预览，再替换机位。"); return; }
        mutate(() -> draft.replace(currentPose(), dimension()), "已用当前视角替换选中机位。");
    }
    public boolean apply(String title, String description, String seconds, String fov, String version) {
        endPreview();
        try {
            draft.edit(title, description, Double.parseDouble(seconds), Float.parseFloat(fov), Integer.parseInt(version));
            status = "已应用机位参数和章节文字。"; autosave(); refresh(); return true;
        } catch (Exception e) { status = "参数未生效：" + e.getMessage(); return false; }
    }
    public void select(int index) {
        draft.select(index);
        if (draft.selected() != null) time = draft.selected().seconds();
        if (previewActive()) { playing = false; updatePose(); }
        refresh();
    }
    public void chapter(int delta) {
        endPreview(); draft.selectChapter(draft.chapterIndex() + delta); time = 0; refresh();
    }
    public void newChapter() { mutate(() -> { draft.newChapter(dimension(), anchor()); time = 0; }, "新章节已创建。先取景，再按 F7 记录。"); }
    public void removeChapter() { mutate(() -> draft.removeChapter(), "已删除章节，可以撤销。"); }
    public void removeKeyframe() { mutate(() -> draft.removeSelected(), "已删除机位，可以撤销。"); }
    public void mode() { mutate(draft::cycleInterpolation, "已切换曲线；导出和实时导览使用同一条路径。"); }
    public void undo() { mutate(draft::undo, "已撤销。"); }
    public void redo() { mutate(draft::redo, "已重做。"); }
    public void newProject() {
        mutate(() -> { draft.load(new CameraDraft(dimension(), anchor()).project()); time = 0; }, "已新建空项目；撤销可恢复上一份草稿。");
    }
    public void save() {
        try { var path = store.save(slot, draft.project()); status = "已保存：" + path.getFileName() + "（config/rongmc-camera/projects）"; }
        catch (Exception e) { status = "保存失败：" + e.getMessage(); }
    }
    public void load() { mutate(() -> {
        try { draft.load(store.load(slot)); time = 0; } catch (java.io.IOException e) { throw new IllegalArgumentException(e.getMessage(), e); }
    }, "已载入项目。请确认当前世界与章节维度。"); }
    public void export() {
        try {
            var path = store.export(slot, draft.project());
            status = "已导出：config/rongmc-camera/exports/" + path.getFileName() + "。默认关闭，确认后再在服务端启用。";
        } catch (Exception e) { status = "无法导出：每章需至少两个有效机位。" + e.getMessage(); }
    }
    private void mutate(Runnable operation, String success) {
        endPreview();
        try { operation.run(); status = success; autosave(); }
        catch (Exception e) { status = "操作未完成：" + e.getMessage(); }
        refresh();
    }
    public void beginDrag() { draft.beginGesture(); }
    public void dragTime(double value) {
        try {
            draft.retimeSelected(value); time = draft.selected() == null ? 0 : draft.selected().seconds();
            if (previewActive()) { playing = false; updatePose(); }
        } catch (Exception e) { status = e.getMessage(); }
        refresh();
    }
    public void endDrag() { draft.endGesture(); autosave(); }
    public void scrub(double value) {
        if (!startPreview(false)) return;
        time = Math.clamp(value, 0, draft.chapter().duration()); playing = false; updatePose();
    }
    public void togglePlay() {
        if (previewActive() && playing) { playing = false; return; }
        if (startPreview(true) && time >= draft.chapter().duration()) time = 0;
    }
    private boolean startPreview(boolean play) {
        var mc = Minecraft.getInstance();
        try {
            if (!authorized || mc.player == null || mc.level == null) throw new IllegalArgumentException("请重新打开管理员编辑器。");
            if (snapshot == null && !mc.player.isCreative() && !mc.player.isSpectator()) throw new IllegalArgumentException("请先进入创造或旁观模式再预览。");
            var chapter = draft.chapter();
            var route = new TourDefinition(draft.project().version(), draft.project().name(), java.util.List.of(chapter));
            route.validate();
            if (!dimension().equals(chapter.dimension())) throw new IllegalArgumentException("请切换到当前章节所在维度。");
            boolean streaming = ClientPlayNetworking.canSend(TourPackets.PreviewRequest.TYPE);
            if (!streaming) for (var key : chapter.keyframes())
                if (!mc.level.hasChunk(((int) Math.floor(key.x())) >> 4, ((int) Math.floor(key.z())) >> 4))
                    throw new IllegalArgumentException("当前服务端版本只支持已加载区块中的预览；跨区块预览需更新配套服务端，录制和导出没有距离限制。");
            if (snapshot == null) {
                snapshot = OptionsSnapshot.capture(mc);
                if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle(); mc.options.setCameraType(CameraType.FIRST_PERSON); mc.options.bobView().set(false);
                if (streaming) {
                    previewSession = UUID.randomUUID(); serverScene = false; loading = true;
                    previewRequested = System.nanoTime(); lastCursor = 0;
                    ClientPlayNetworking.send(new TourPackets.PreviewRequest(previewSession, JsonFiles.GSON.toJson(route)));
                } else { serverScene = true; loading = false; }
            }
            playing = play; lastFrame = System.nanoTime();
            time = Math.clamp(time, 0, chapter.duration()); updatePose();
            status = loading ? "正在准备路线区块…" : "ReplayStudio 实时预览；拖动时间轴可查看任意时刻。";
            return true;
        } catch (Exception e) { status = "无法预览：" + e.getMessage(); return false; }
    }
    public void frame() {
        if (!previewActive()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !dimension().equals(draft.chapter().dimension())) { endPreview(); return; }
        long now = System.nanoTime();
        double delta = lastFrame == 0 ? 0 : (now - lastFrame) / 1_000_000_000.0;
        lastFrame = now;
        if (!serverScene) return;
        if (playing && !loading && mc.isWindowActive()) {
            time = Math.min(draft.chapter().duration(), time + delta);
            if (time >= draft.chapter().duration()) playing = false;
        }
        updatePose();
    }
    private void updatePose() {
        if (previewSession != null && !serverScene) return;
        var next = CameraPath.sample(draft.chapter(), time);
        var mc = Minecraft.getInstance();
        // Render the destination behind an opaque cover so its visible meshes can actually be built.
        pose = next;
        int cx = ((int) Math.floor(next.x())) >> 4, cz = ((int) Math.floor(next.z())) >> 4;
        boolean ready = mc.level != null;
        for (int x = cx - 1; ready && x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++)
            if (!mc.level.hasChunk(x, z)) { ready = false; break; }
        if (!ready || !mc.levelRenderer.hasRenderedAllSections()) {
            loading = true; status = previewSession != null ? "正在加载路线区块，镜头就绪后继续…" : "预览等待目标区块加载。"; return;
        }
        if (loading) status = "ReplayStudio 实时预览；拖动时间轴可查看任意时刻。";
        loading = false;
    }
    public boolean handles(UUID id) { return id.equals(previewSession) || retiredPreviews.contains(id); }
    public void offer(TourPackets.Offer packet) {
        if (!packet.session().equals(previewSession)) {
            ClientPlayNetworking.send(new TourPackets.Control(packet.session(), TourPackets.Action.CANCEL, 0)); return;
        }
        ClientPlayNetworking.send(new TourPackets.Control(previewSession, TourPackets.Action.READY, 0));
    }
    public void scene(TourPackets.Scene packet) {
        if (!packet.session().equals(previewSession)) return;
        serverScene = true; lastCursor = 0; updatePose();
    }
    public void stopped(TourPackets.Stop packet) {
        if (!packet.session().equals(previewSession)) return;
        endPreview();
        if (!packet.reason().isEmpty()) status = packet.reason();
    }
    public void tick(Minecraft mc) {
        if (previewActive() && (mc.gui.screen() != screen || mc.level == null || RongMcClient.tour().active())) endPreview();
        if (previewSession != null) {
            long now = System.nanoTime();
            if (!serverScene && now - previewRequested > 15_000_000_000L) { endPreview(); status = "预览准备超时，已取消。"; }
            else if (serverScene && now - lastCursor > 100_000_000L) {
                ClientPlayNetworking.send(new TourPackets.Cursor(previewSession, 0, time)); lastCursor = now;
            }
        }
    }
    public void endPreview() {
        var mc = Minecraft.getInstance();
        if (!mc.isSameThread()) { mc.execute(this::endPreview); return; }
        if (previewSession != null) {
            if (ClientPlayNetworking.canSend(TourPackets.Control.TYPE))
                ClientPlayNetworking.send(new TourPackets.Control(previewSession, TourPackets.Action.CANCEL, 0));
            retiredPreviews.add(previewSession);
            if (retiredPreviews.size() > 32) retiredPreviews.remove(retiredPreviews.iterator().next());
            previewSession = null;
        }
        serverScene = false; loading = false;
        pose = null; playing = false; lastFrame = 0;
        if (snapshot != null) { try { snapshot.restore(mc); } finally { snapshot = null; } }
    }
    public void closeScreen() {
        endPreview();
        if (draft != null) { draft.endGesture(); autosave(); }
        var mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof CameraEditorScreen) mc.gui.setScreen(null);
        screen = null; KeyMapping.releaseAll();
    }
    public void disconnect() { closeScreen(); authorized = false; }
    private void autosave() {
        try { if (draft != null) store.autosave(draft.project()); }
        catch (Exception e) { status = "草稿仍在内存中，但自动保存失败：" + e.getMessage(); RongMcClient.LOGGER.warn("Camera autosave failed", e); }
    }
    private void refresh() { if (screen != null) screen.syncFields(); }
    private void message(String text) {
        status = text;
        var p = Minecraft.getInstance().player;
        if (p != null) p.sendOverlayMessage(Component.literal(text));
    }
    private static String dimension() { return Minecraft.getInstance().level.dimension().identifier().toString(); }
    private static TourDefinition.Anchor anchor() { var p = Minecraft.getInstance().player; return new TourDefinition.Anchor(p.getX(), p.getY(), p.getZ()); }
    private static CameraPath.Pose currentPose() {
        var mc = Minecraft.getInstance(); var c = mc.gameRenderer.mainCamera(); var p = c.position();
        return new CameraPath.Pose(p.x(), p.y(), p.z(), c.yRot(), c.xRot(), Math.clamp(mc.options.fov().get(), 30, 100));
    }
}

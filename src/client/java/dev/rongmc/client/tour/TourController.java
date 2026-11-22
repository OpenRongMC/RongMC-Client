package dev.rongmc.client.tour;

import dev.rongmc.client.RongMcClient;
import dev.rongmc.tour.*;
import dev.rongmc.tour.QualityPolicy.Tier;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.network.chat.Component;

public final class TourController {
    public enum Phase { WAITING, LOADING, CALIBRATING, PLAYING, ERROR, EXITING }
    private UUID session;
    private TourDefinition definition;
    private OptionsSnapshot snapshot;
    private TourScreen screen;
    private DeviceProbe device;
    private Phase phase = Phase.WAITING;
    private Tier tier = Tier.LOW;
    private CameraPath.Pose pose;
    private int chapter, samples, calibrationAttempts;
    private final double[] frameTimes = new double[2048];
    private long phaseStart, lastFrame, readySince, lastHeartbeat, measureStart, lastQualityChange, lastCursor;
    private double elapsed, p95;
    private boolean paused, reducedMotion, automatic = true, calibrated, serverScene, streaming;
    private String error = "";

    public boolean active() { return session != null; }
    public boolean temporaryOptions() { return snapshot != null; }
    public CameraPath.Pose pose() { return pose; }
    public Phase phase() { return phase; }
    public Tier tier() { return tier; }
    public boolean automatic() { return automatic; }
    public boolean paused() { return paused; }
    public boolean reducedMotion() { return reducedMotion; }
    public String error() { return error; }
    public double p95() { return p95; }
    public DeviceProbe device() { return device; }
    public int chapterIndex() { return chapter; }
    public TourDefinition definition() { return definition; }
    public TourDefinition.Chapter chapter() { return definition.chapters().get(chapter); }
    public double progress() { return definition == null ? 0 : Math.clamp(elapsed / chapter().duration(), 0, 1); }
    public boolean interactive() { return active() && phase == Phase.PLAYING; }

    public void offer(TourPackets.Offer packet) {
        Minecraft mc = Minecraft.getInstance();
        if (active()) return;
        try {
            TourDefinition tour = JsonFiles.GSON.fromJson(packet.json(), TourDefinition.class);
            if (tour == null) throw new IllegalArgumentException("Empty tour");
            tour.validate();
            if (mc.level == null || mc.player == null) throw new IllegalStateException("World is not ready");
            definition = tour;
            session = packet.session();
            device = DeviceProbe.capture(mc);
            snapshot = OptionsSnapshot.capture(mc);
            tier = Tier.LOW; chapter = 0; elapsed = 0; samples = 0; p95 = 0;
            paused = false; reducedMotion = false; automatic = true; calibrated = false; serverScene = false;
            streaming = ClientPlayNetworking.canSend(TourPackets.Cursor.TYPE); lastCursor = 0;
            calibrationAttempts = 0; pose = null; lastFrame = 0; readySince = 0;
            snapshot.apply(mc, tier);
            screen = new TourScreen(this);
            mc.gui.setScreen(screen);
            KeyMapping.releaseAll();
            setPhase(Phase.WAITING);
            lastHeartbeat = System.nanoTime();
            send(TourPackets.Action.READY);
            RongMcClient.LOGGER.info("Tour preflight: {} cores, heap={} MiB, pixels={}, ceiling={}; hardware stays local",
                    device.processors(), device.heapMiB(), device.pixels(), device.ceiling());
        } catch (RuntimeException e) {
            RongMcClient.LOGGER.error("Cannot start welcome tour", e);
            if (ClientPlayNetworking.canSend(TourPackets.Control.TYPE))
                ClientPlayNetworking.send(new TourPackets.Control(packet.session(), TourPackets.Action.CANCEL, 0));
            dispose();
        }
    }

    public void scene(TourPackets.Scene packet) {
        if (!active() || !session.equals(packet.session()) || packet.chapter() != chapter || phase == Phase.EXITING) return;
        serverScene = true;
        elapsed = 0; paused = false; readySince = 0; lastFrame = 0; samples = 0;
        pose = CameraPath.sample(chapter(), 0);
        setPhase(Phase.LOADING);
        Minecraft.getInstance().gui.setScreen(screen);
    }

    public void stop(TourPackets.Stop packet) {
        if (!active() || !session.equals(packet.session())) return;
        dispose();
        var mc = Minecraft.getInstance();
        if (mc.player != null && !packet.reason().isEmpty()) mc.player.sendSystemMessage(Component.literal(packet.reason()));
    }

    public void tick(Minecraft mc) {
        if (!active()) return;
        long now = System.nanoTime();
        if (mc.getConnection() == null || !ClientPlayNetworking.canSend(TourPackets.Control.TYPE)) { dispose(); return; }
        if (now - lastHeartbeat > 2_000_000_000L) { send(TourPackets.Action.HEARTBEAT); lastHeartbeat = now; }
        if (phase == Phase.EXITING) {
            if (now - phaseStart > 8_000_000_000L) dispose();
            return;
        }
        if (streaming && serverScene && now - lastCursor > 100_000_000L) {
            ClientPlayNetworking.send(new TourPackets.Cursor(session, chapter, reducedMotion ? 0 : elapsed));
            lastCursor = now;
        }
        if (mc.level == null || mc.player == null) {
            if (now - phaseStart > 30_000_000_000L) cancel();
            return;
        }
        if (mc.gui.screen() != screen) {
            if (mc.gui.screen() instanceof LevelLoadingScreen || phase == Phase.WAITING || phase == Phase.LOADING) {
                if (!(mc.gui.screen() instanceof LevelLoadingScreen)) mc.gui.setScreen(screen);
            } else { cancel(); return; }
        }
        if (phase == Phase.PLAYING && !dimensionReady(mc)) { cancel(); return; }
        if ((phase == Phase.WAITING || phase == Phase.LOADING) && now - phaseStart > 30_000_000_000L)
            fail("场景准备超时。可能是区块未到达、路线超出视距或渲染队列繁忙。请重试或跳过。");
        KeyMapping.releaseAll();
    }

    /** Called once per rendered world frame, from the camera hook; screen draws never count as benchmark samples. */
    public void frame() {
        if (!active() || !serverScene || phase == Phase.EXITING) { pose = null; return; }
        var mc = Minecraft.getInstance();
        if (mc.level == null || !dimensionReady(mc)) { lastFrame = 0; return; }
        long now = System.nanoTime();
        double frameMs = lastFrame == 0 ? 0 : (now - lastFrame) / 1_000_000.0;
        lastFrame = now;
        boolean focused = mc.isWindowActive();
        pose = CameraPath.sample(chapter(), reducedMotion ? 0 : elapsed);
        if (phase == Phase.LOADING) {
            if (chunksReady(mc) && mc.levelRenderer.hasRenderedAllSections()) {
                if (readySince == 0) readySince = now;
                if (now - readySince > 1_000_000_000L) {
                    samples = 0; measureStart = now;
                    setPhase(!calibrated && automatic ? Phase.CALIBRATING : Phase.PLAYING);
                    RongMcClient.LOGGER.info("Tour scene ready: chapter={}, tier={}", chapter, tier);
                }
            } else readySince = 0;
            return;
        }
        if (phase == Phase.ERROR || !focused) return;
        boolean stable = chunksReady(mc) && mc.levelRenderer.hasRenderedAllSections();
        if (phase == Phase.CALIBRATING) {
            if (!stable) { samples = 0; measureStart = now; }
            else if (frameMs > 0 && frameMs < 2000 && samples < frameTimes.length) frameTimes[samples++] = frameMs;
            if (now - phaseStart > 12_000_000_000L) {
                calibrated = true; setPhase(Phase.PLAYING); samples = 0; measureStart = now;
            } else if (stable && now - measureStart > 2_000_000_000L && samples >= 20) {
                p95 = QualityPolicy.percentile95(frameTimes, samples);
                Tier next = QualityPolicy.calibrated(tier, device.ceiling(), p95, device.frameBudgetMs(), DeviceProbe.memoryPressure());
                if (next != tier && calibrationAttempts++ < 2) {
                    changeTier(next);
                } else {
                    calibrated = true; samples = 0; measureStart = now; lastQualityChange = now;
                    setPhase(Phase.PLAYING);
                    RongMcClient.LOGGER.info("Tour calibration finished: tier={}, p95={}ms", tier, Math.round(p95));
                }
            }
            return;
        }
        if (phase == Phase.PLAYING) {
            if (!stable) { samples = 0; measureStart = now; }
            if (!chunksReady(mc)) { setPhase(Phase.LOADING); readySince = 0; return; }
            if (!paused && frameMs > 0) elapsed = Math.min(chapter().duration(), elapsed + frameMs / 1000.0);
            if (stable && frameMs > 0 && frameMs < 2000 && samples < frameTimes.length) frameTimes[samples++] = frameMs;
            if (now - measureStart > 5_000_000_000L && samples >= 30) {
                p95 = QualityPolicy.percentile95(frameTimes, samples);
                if (automatic && now - lastQualityChange > 10_000_000_000L
                        && (p95 > device.frameBudgetMs() * 1.5 || DeviceProbe.memoryPressure()) && tier != Tier.LOW)
                    changeTier(tier.lower());
                // Automatic upgrades only happen behind the initial loading cover; no in-tour render-distance oscillation.
                samples = 0; measureStart = now;
            }
        }
    }

    private boolean dimensionReady(Minecraft mc) {
        return mc.level != null && mc.level.dimension().identifier().toString().equals(chapter().dimension());
    }
    private boolean chunksReady(Minecraft mc) {
        if (!dimensionReady(mc)) return false;
        if (streaming) {
            var p = CameraPath.sample(chapter(), reducedMotion ? 0 : elapsed);
            int cx = ((int) Math.floor(p.x())) >> 4, cz = ((int) Math.floor(p.z())) >> 4;
            for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++)
                if (!mc.level.hasChunk(x, z)) return false;
            return true;
        }
        for (var key : chapter().keyframes()) {
            int x = ((int) Math.floor(key.x())) >> 4, z = ((int) Math.floor(key.z())) >> 4;
            if (!mc.level.hasChunk(x, z)) return false;
        }
        return true;
    }
    private void setPhase(Phase phase) { this.phase = phase; phaseStart = System.nanoTime(); }
    private void changeTier(Tier next) {
        tier = next;
        snapshot.apply(Minecraft.getInstance(), next);
        samples = 0; readySince = 0; lastQualityChange = System.nanoTime();
        setPhase(Phase.LOADING);
    }
    public void cycleQuality() {
        if (!interactive()) return;
        if (automatic) { automatic = false; changeTier(Tier.LOW); }
        else if (tier == Tier.HIGH) { automatic = true; calibrated = false; calibrationAttempts = 0; changeTier(Tier.LOW); }
        else changeTier(tier.higher());
    }
    public void togglePause() { if (interactive()) paused = !paused; }
    public void toggleReducedMotion() { if (interactive()) reducedMotion = !reducedMotion; }
    public void navigate(int delta) {
        if (!interactive()) return;
        int next = chapter + delta;
        if (next < 0 || next >= definition.chapters().size()) return;
        chapter = next; serverScene = false; pose = null; elapsed = 0; readySince = 0;
        setPhase(Phase.WAITING);
        send(TourPackets.Action.CHAPTER);
    }
    public void retry() {
        if (phase != Phase.ERROR || !serverScene) return;
        automatic = true; calibrated = false; calibrationAttempts = 0; readySince = 0;
        changeTier(Tier.LOW);
    }
    public void exit(boolean finish) {
        if (!active() || phase == Phase.EXITING) return;
        boolean complete = finish && phase == Phase.PLAYING && chapter == definition.chapters().size() - 1;
        send(complete ? TourPackets.Action.FINISH : phase == Phase.ERROR ? TourPackets.Action.CANCEL : TourPackets.Action.SKIP);
        pose = null;
        setPhase(Phase.EXITING);
    }
    public void cancel() {
        if (active()) send(TourPackets.Action.CANCEL);
        dispose();
    }
    private void send(TourPackets.Action action) {
        if (session != null && ClientPlayNetworking.canSend(TourPackets.Control.TYPE))
            ClientPlayNetworking.send(new TourPackets.Control(session, action, chapter));
    }
    private void fail(String message) { error = message; setPhase(Phase.ERROR); }

    public void dispose() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) { mc.execute(this::dispose); return; }
        if (snapshot != null) {
            try { snapshot.restore(mc); }
            finally { snapshot = null; }
        }
        boolean closeScreen = mc.gui.screen() == screen && screen != null;
        session = null; pose = null; definition = null; serverScene = false; phase = Phase.WAITING;
        if (closeScreen) mc.gui.setScreen(null);
        screen = null;
        KeyMapping.releaseAll();
    }
}

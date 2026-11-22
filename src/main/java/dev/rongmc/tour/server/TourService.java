package dev.rongmc.tour.server;

import dev.rongmc.tour.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

/** Server-thread confined. Camera clients cannot request arbitrary coordinates or commands. */
public final class TourService {
    public record Configuration(boolean enabled, boolean autoStartOnFirstJoin, int maxConcurrent, TourDefinition tour) {}
    public record ReturnPoint(String dimension, double x, double y, double z, float yaw, float pitch,
                              String gameMode, boolean flying, boolean invulnerable, boolean mayfly, boolean instabuild) {
        public void validate() {
            if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 29_999_000 || Math.abs(z) > 29_999_000 || Math.abs(y) > 2048
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                    || gameMode == null || !Set.of("survival", "creative", "adventure", "spectator").contains(gameMode))
                throw new IllegalArgumentException("Invalid interrupted-tour return point; recovery state preserved");
        }
    }
    public static final class SavedState {
        public Map<UUID, Integer> completed = new HashMap<>();
        public Map<UUID, ReturnPoint> returns = new HashMap<>();
    }
    private static final class Session {
        final UUID id;
        final TourDefinition tour;
        final boolean preview, follow;
        final long created = System.nanoTime();
        long heartbeat = created;
        int chapter;
        boolean started;
        double cursor;
        long lastCursor;
        Session(TourDefinition tour, UUID id, boolean preview, boolean follow) {
            this.tour = tour; this.id = id; this.preview = preview; this.follow = follow;
        }
    }
    private final MinecraftServer server;
    private final Path statePath;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Integer> joinDelay = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private SavedState saved;
    private Configuration configuration;

    public TourService(MinecraftServer server) throws IOException {
        this.server = server;
        statePath = server.getWorldPath(LevelResource.ROOT).resolve("rongmc-tour-state.json");
        saved = Files.exists(statePath) ? JsonFiles.read(statePath, SavedState.class) : new SavedState();
        if (saved.completed == null || saved.returns == null) throw new IOException("Invalid tour recovery state; refusing to overwrite it");
        for (var entry : saved.returns.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) throw new IOException("Null tour recovery entry");
            entry.getValue().validate();
        }
        reload();
    }

    public void reload() throws IOException {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("rongmc-tour-server.json");
        if (!Files.exists(configPath)) JsonFiles.write(configPath, new Configuration(false, true, 2, null));
        Configuration next = JsonFiles.read(configPath, Configuration.class);
        if (next.maxConcurrent() < 1 || next.maxConcurrent() > 8) throw new IllegalArgumentException("maxConcurrent must be 1..8");
        if (next.enabled()) {
            if (next.tour() == null) throw new IllegalArgumentException("enabled tour requires a definition");
            validate(next.tour());
        }
        configuration = next;
    }

    private void validate(TourDefinition tour) {
        tour.validate();
        if (JsonFiles.GSON.toJson(tour).length() > TourPackets.MAX_JSON) throw new IllegalArgumentException("Tour payload too large");
        for (var chapter : tour.chapters()) {
            ServerLevel level = level(chapter.dimension());
            var a = chapter.anchor();
            if (level == null || !level.getWorldBorder().isWithinBounds(BlockPos.containing(a.x(), a.y(), a.z()))) {
                throw new IllegalArgumentException("Invalid/unavailable scene anchor: " + chapter.id());
            }
            for (var key : chapter.keyframes())
                if (!level.getWorldBorder().isWithinBounds(BlockPos.containing(key.x(), key.y(), key.z())))
                    throw new IllegalArgumentException("Camera route crosses the world border: " + chapter.id());
        }
    }

    public void join(ServerPlayer player) {
        ReturnPoint recovery = saved.returns.get(player.getUUID());
        if (recovery != null) {
            if (restore(player, recovery)) {
                saved.returns.remove(player.getUUID());
                persist();
                RongMcTour.LOGGER.info("Recovered interrupted tour for {}", player.getUUID());
            } else return; // Preserve recovery record until its original dimension becomes available.
        }
        joinDelay.put(player.getUUID(), 60);
    }

    public String startConfigured(ServerPlayer player) {
        if (!configuration.enabled()) return "导览尚未配置。管理员可编辑 config/rongmc-tour-server.json 后执行 /rongmctour reload。";
        return offer(player, configuration.tour(), false);
    }

    /** Public for an integrated-world preview and the isolated client game test, never a network operation. */
    public String offer(ServerPlayer player, TourDefinition tour, boolean bypassCooldown) {
        return offer(player, tour, bypassCooldown, false, UUID.randomUUID());
    }

    public void preview(ServerPlayer player, TourPackets.PreviewRequest request) {
        try {
            if (!player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                    || (!player.isCreative() && !player.isSpectator()))
                throw new IllegalArgumentException("跨区块预览需要管理员权限及创造或旁观模式。");
            var tour = JsonFiles.GSON.fromJson(request.json(), TourDefinition.class);
            if (tour == null || tour.chapters() == null || tour.chapters().size() != 1)
                throw new IllegalArgumentException("预览需要一个有效章节。");
            String result = offer(player, tour, true, true, request.session());
            var session = sessions.get(player.getUUID());
            if (session == null || !session.id.equals(request.session()))
                ServerPlayNetworking.send(player, new TourPackets.Stop(request.session(), result));
        } catch (RuntimeException e) {
            String reason = "预览未启动：" + e.getMessage();
            ServerPlayNetworking.send(player, new TourPackets.Stop(request.session(), reason.substring(0, Math.min(240, reason.length()))));
        }
    }

    private String offer(ServerPlayer player, TourDefinition tour, boolean bypassCooldown, boolean preview, UUID id) {
        validate(tour);
        boolean follow = ServerPlayNetworking.canSend(player, TourPackets.CursorAck.TYPE);
        if (tour.requiresStreaming() && !follow) return "这条长路线需要新版 RongMC 客户端，请更新后再观看。";
        UUID uuid = player.getUUID();
        if (saved.returns.containsKey(uuid)) return "上一次导览状态尚未恢复，请联系管理员。";
        if (sessions.containsKey(uuid)) return "你已经在导览中。";
        if (sessions.size() >= configuration.maxConcurrent()) return "导览准备名额已满，请稍后再试。";
        if (!ServerPlayNetworking.canSend(player, TourPackets.Offer.TYPE)) return "请在客户端安装 RongMC 导览模组。";
        if (!player.isAlive() || player.isPassenger() || (!player.onGround() && !player.isCreative() && !player.isSpectator()))
            return "请先站在安全地面上再开始导览。";
        if (!bypassCooldown && cooldowns.containsKey(uuid) && System.nanoTime() - cooldowns.get(uuid) < 60_000_000_000L)
            return "请稍等一分钟后再重新观看。";
        Session session = new Session(tour, id, preview, follow);
        sessions.put(uuid, session);
        if (!preview) cooldowns.put(uuid, System.nanoTime());
        ServerPlayNetworking.send(player, new TourPackets.Offer(session.id, JsonFiles.GSON.toJson(tour)));
        RongMcTour.LOGGER.info("Tour offered: player={}, version={}, chapters={}, preview={}, follow={}", uuid, tour.version(), tour.chapters().size(), preview, follow);
        return "正在准备实时导览。";
    }

    public void control(ServerPlayer player, TourPackets.Control control) {
        Session session = sessions.get(player.getUUID());
        if (session == null || !session.id.equals(control.session())) return;
        session.heartbeat = System.nanoTime();
        switch (control.action()) {
            case READY -> {
                if (session.started) return;
                if (session.preview && (!player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                        || (!player.isCreative() && !player.isSpectator()))) {
                    finish(player, false, true, "预览权限或模式已改变。"); return;
                }
                var a = player.getAbilities();
                var point = new ReturnPoint(player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(),
                        player.getYRot(), player.getXRot(), player.gameMode().getName(), a.flying, a.invulnerable, a.mayfly, a.instabuild);
                saved.returns.put(player.getUUID(), point);
                if (!persist()) {
                    saved.returns.remove(player.getUUID());
                    finish(player, false, true, "无法保存返回位置，已取消导览。");
                    return;
                }
                session.started = true;
                player.setGameMode(GameType.SPECTATOR);
                moveScene(player, session, 0);
            }
            case CHAPTER -> {
                if (session.started && control.chapter() >= 0 && control.chapter() < session.tour.chapters().size()
                        && Math.abs(control.chapter() - session.chapter) == 1) moveScene(player, session, control.chapter());
            }
            case FINISH -> {
                if (session.started && session.chapter == session.tour.chapters().size() - 1 && control.chapter() == session.chapter)
                    finish(player, !session.preview, true, "欢迎来到 RongMC，开始建造吧。");
            }
            case SKIP -> finish(player, !session.preview, true, "已跳过导览，可以随时重新观看。");
            case CANCEL -> finish(player, false, true, "已退出导览。");
            case HEARTBEAT -> { }
        }
    }

    public void cursor(ServerPlayer player, TourPackets.Cursor packet) {
        var s = sessions.get(player.getUUID());
        if (s == null || !s.started || !s.follow || !s.id.equals(packet.session()) || packet.chapter() != s.chapter
                || !Double.isFinite(packet.seconds()) || packet.seconds() < 0
                || packet.seconds() > s.tour.chapters().get(s.chapter).duration()) return;
        long now = System.nanoTime();
        if (now - s.lastCursor < 50_000_000L) return;
        s.lastCursor = now; s.heartbeat = now; s.cursor = packet.seconds();
        if (pin(player, s)) ServerPlayNetworking.send(player, new TourPackets.CursorAck(s.id, s.chapter, s.cursor));
    }

    private void moveScene(ServerPlayer player, Session session, int index) {
        var chapter = session.tour.chapters().get(index);
        var a = chapter.anchor();
        var first = chapter.keyframes().getFirst();
        ServerLevel target = level(chapter.dimension());
        if (target == null || !player.teleportTo(target, a.x(), a.y(), a.z(), Set.of(), first.yaw(), first.pitch(), false)) {
            finish(player, false, true, "场景切换失败，已返回。");
            return;
        }
        player.resetFallDistance();
        session.chapter = index;
        session.cursor = 0;
        if (session.follow && !pin(player, session)) return;
        ServerPlayNetworking.send(player, new TourPackets.Scene(session.id, index));
    }

    private boolean pin(ServerPlayer player, Session session) {
        var c = session.tour.chapters().get(session.chapter);
        var a = c.anchor();
        var pose = session.follow ? CameraPath.sample(c, session.cursor) : null;
        double x = pose == null ? a.x() : pose.x(), y = pose == null ? a.y() : pose.y() - 1.62;
        double z = pose == null ? a.z() : pose.z();
        var target = level(c.dimension());
        if (target == null || !target.getWorldBorder().isWithinBounds(BlockPos.containing(x, y, z))) {
            finish(player, false, true, "路线超出世界边界，已返回。"); return false;
        }
        if (player.position().distanceToSqr(x, y, z) > .04
                && !player.teleportTo(target, x, y, z, Set.of(), player.getYRot(), player.getXRot(), false)) {
            finish(player, false, true, "路线区块切换失败，已返回。"); return false;
        }
        player.resetFallDistance();
        return true;
    }

    public void tick() {
        for (UUID uuid : List.copyOf(joinDelay.keySet())) {
            int delay = joinDelay.get(uuid) - 1;
            if (delay > 0) { joinDelay.put(uuid, delay); continue; }
            joinDelay.remove(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null && configuration.enabled() && configuration.autoStartOnFirstJoin()
                    && saved.completed.getOrDefault(uuid, 0) < configuration.tour().version()
                    && ServerPlayNetworking.canSend(player, TourPackets.Offer.TYPE)) startConfigured(player);
        }
        long now = System.nanoTime();
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            Session s = sessions.get(uuid);
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p == null) { sessions.remove(uuid); continue; }
            if (now - s.heartbeat > 15_000_000_000L || now - s.created > 1_200_000_000_000L) {
                finish(p, false, true, "导览连接超时，已恢复玩家状态。");
            } else if (s.started) {
                var c = s.tour.chapters().get(s.chapter);
                if (p.level() != level(c.dimension()) || !p.isAlive() || p.gameMode() != GameType.SPECTATOR) {
                    finish(p, false, true, "玩家状态改变，已退出导览。");
                } else if (s.preview && !p.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                    finish(p, false, true, "预览权限已撤销，已返回。");
                else pin(p, s);
            }
        }
    }

    public void finish(ServerPlayer player, boolean completed, boolean notify, String reason) {
        Session s = sessions.remove(player.getUUID());
        joinDelay.remove(player.getUUID());
        if (s == null) return;
        ReturnPoint point = saved.returns.get(player.getUUID());
        if (point != null && restore(player, point)) saved.returns.remove(player.getUUID());
        if (completed) saved.completed.merge(player.getUUID(), s.tour.version(), Math::max);
        persist();
        if (notify && ServerPlayNetworking.canSend(player, TourPackets.Stop.TYPE))
            ServerPlayNetworking.send(player, new TourPackets.Stop(s.id, reason));
        RongMcTour.LOGGER.info("Tour closed: player={}, completed={}, recoveryPending={}", player.getUUID(), completed, saved.returns.containsKey(player.getUUID()));
    }

    public void leave(ServerPlayer player) {
        finish(player, false, false, "");
        joinDelay.remove(player.getUUID());
        cooldowns.remove(player.getUUID());
    }

    public void shutdown() {
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) finish(player, false, false, "");
        }
    }

    private boolean restore(ServerPlayer player, ReturnPoint point) {
        ServerLevel level = level(point.dimension());
        if (level == null) { RongMcTour.LOGGER.error("Cannot restore tour player: original dimension unavailable: {}", point.dimension()); return false; }
        if (!player.teleportTo(level, point.x(), point.y(), point.z(), Set.of(), point.yaw(), point.pitch(), false)) return false;
        player.setGameMode(GameType.byName(point.gameMode()));
        var a = player.getAbilities();
        a.flying = point.flying(); a.invulnerable = point.invulnerable(); a.mayfly = point.mayfly(); a.instabuild = point.instabuild();
        player.onUpdateAbilities();
        player.resetFallDistance();
        return true;
    }

    private boolean persist() {
        try { JsonFiles.write(statePath, saved); return true; }
        catch (IOException e) { RongMcTour.LOGGER.error("Could not save tour recovery state", e); return false; }
    }

    private ServerLevel level(String dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
    }
    public boolean protectedPlayer(UUID uuid) { Session s = sessions.get(uuid); return s != null && s.started; }
    public boolean hasSession(UUID uuid) { return sessions.containsKey(uuid); }
    public int currentChapter(UUID uuid) { return sessions.containsKey(uuid) ? sessions.get(uuid).chapter : -1; }
    public int completedVersion(UUID uuid) { return saved.completed.getOrDefault(uuid, 0); }
    public int recoveryCount() { return saved.returns.size(); }
    public String status() { return "导览=" + configuration.enabled() + "，活动会话=" + sessions.size() + "，待恢复=" + saved.returns.size(); }

    public static TourDefinition demo(ServerPlayer player) {
        double x = player.getX(), y = player.getY(), z = player.getZ();
        String dim = player.level().dimension().identifier().toString();
        var anchor = new TourDefinition.Anchor(x, y, z);
        return new TourDefinition(1, "RongMC · 实时建筑导览", List.of(
                new TourDefinition.Chapter("welcome", "每一块方块，都有你的故事。", "欢迎来到 RongMC。眼前的世界正由你的设备实时渲染。这里是镜头与交互原型，正式建筑路线将在实景选点后配置。", dim, anchor,
                        List.of(new TourDefinition.Keyframe(0, x + 14, y + 9, z + 18, 145, 18, 66),
                                new TourDefinition.Keyframe(18, x - 4, y + 7, z + 20, 190, 15, 60))),
                new TourDefinition.Chapter("create", "从一处灵感，开始一座世界。", "用鼠标切换章节、暂停镜头，或者选择开始游玩。正式版本会在这里介绍建筑区域、代表作品和共建规则。", dim, anchor,
                        List.of(new TourDefinition.Keyframe(0, x - 18, y + 8, z + 10, 235, 16, 65),
                                new TourDefinition.Keyframe(18, x - 10, y + 6, z - 12, 310, 13, 62)))));
    }
}

package dev.rongmc.tour;

import dev.rongmc.tour.server.TourService;
import java.util.IdentityHashMap;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RongMcTour implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("rongmc_tour");
    private static final Map<MinecraftServer, TourService> SERVICES = new IdentityHashMap<>();
    public static TourService service(MinecraftServer server) { return SERVICES.get(server); }

    @Override public void onInitialize() {
        TourPackets.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { SERVICES.put(server, new TourService(server)); }
            catch (Exception e) { LOGGER.error("Tour disabled: configuration/recovery state could not be loaded", e); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (service(server) != null) service(server).shutdown(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(SERVICES::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (service(server) != null) service(server).tick(); });
        ServerPlayerEvents.JOIN.register(player -> {
            var service = service(player.level().getServer());
            if (service != null) service.join(player);
        });
        ServerPlayerEvents.LEAVE.register(player -> {
            var service = service(player.level().getServer());
            if (service != null) service.leave(player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, player, alive) -> {
            var service = service(player.level().getServer());
            if (service != null) service.finish(player, false, true, "玩家已重生，导览结束。");
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !protectedEntity(entity));
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> !protectedEntity(entity));
        ServerPlayNetworking.registerGlobalReceiver(TourPackets.Control.TYPE, (packet, context) -> {
            var service = service(context.server());
            if (service != null) service.control(context.player(), packet);
        });
        ServerPlayNetworking.registerGlobalReceiver(TourPackets.EditorRequest.TYPE, (packet, context) -> editor(context.player(), packet.action()));
        ServerPlayNetworking.registerGlobalReceiver(TourPackets.PreviewRequest.TYPE, (packet, context) -> {
            var service = service(context.server());
            if (service != null) service.preview(context.player(), packet);
            else ServerPlayNetworking.send(context.player(), new TourPackets.Stop(packet.session(), "预览服务未初始化。"));
        });
        ServerPlayNetworking.registerGlobalReceiver(TourPackets.Cursor.TYPE, (packet, context) -> {
            var service = service(context.server());
            if (service != null) service.cursor(context.player(), packet);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(
                Commands.literal("rongmctour")
                        .executes(context -> {
                            var service = service(context.getSource().getServer());
                            String result = service == null ? "导览不可用，请检查服务端日志。" : service.startConfigured(context.getSource().getPlayerOrException());
                            context.getSource().sendSuccess(() -> Component.literal(result), false);
                            return 1;
                        })
                        .then(Commands.literal("editor").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                                .executes(context -> editor(context.getSource().getPlayerOrException(), TourPackets.EditorAction.OPEN) ? 1 : 0))
                        .then(Commands.literal("demo").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                                .executes(context -> {
                                    if (context.getSource().getServer().isDedicatedServer()) {
                                        context.getSource().sendFailure(Component.literal("demo 仅供单人世界测试使用。"));
                                        return 0;
                                    }
                                    var player = context.getSource().getPlayerOrException();
                                    var service = service(context.getSource().getServer());
                                    if (service == null) return 0;
                                    String result = service.offer(player, TourService.demo(player), true);
                                    context.getSource().sendSuccess(() -> Component.literal(result), false);
                                    return 1;
                                }))
                        .then(Commands.literal("reload").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                                .executes(context -> {
                                    var service = service(context.getSource().getServer());
                                    try {
                                        if (service == null) throw new IllegalStateException("服务未初始化，请检查启动日志");
                                        service.reload();
                                        context.getSource().sendSuccess(() -> Component.literal("导览配置已载入。"), false);
                                        return 1;
                                    } catch (Exception e) {
                                        context.getSource().sendFailure(Component.literal("配置未生效：" + e.getMessage()));
                                        return 0;
                                    }
                                }))
                        .then(Commands.literal("status").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                                .executes(context -> {
                                    var service = service(context.getSource().getServer());
                                    context.getSource().sendSuccess(() -> Component.literal(service == null ? "导览未初始化" : service.status()), false);
                                    return 1;
                                }))));
    }

    private static boolean protectedEntity(net.minecraft.world.entity.LivingEntity entity) {
        if (!(entity instanceof ServerPlayer player)) return false;
        var service = service(player.level().getServer());
        return service != null && service.protectedPlayer(player.getUUID());
    }

    private static boolean editor(ServerPlayer player, TourPackets.EditorAction action) {
        if (!ServerPlayNetworking.canSend(player, TourPackets.EditorReply.TYPE)) return false;
        var service = service(player.level().getServer());
        boolean permitted = player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        boolean available = service == null || !service.hasSession(player.getUUID());
        String reason = !permitted ? "运镜编辑器仅供管理员使用。" : !available ? "请先退出欢迎导览，再打开运镜编辑器。" : "";
        ServerPlayNetworking.send(player, new TourPackets.EditorReply(action, permitted && available, reason));
        return permitted && available;
    }
}

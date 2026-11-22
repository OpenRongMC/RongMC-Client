package dev.rongmc.test;

import dev.rongmc.client.RongMcClient;
import dev.rongmc.client.tour.OptionsSnapshot;
import dev.rongmc.client.tour.TourController;
import dev.rongmc.client.tour.TourScreen;
import dev.rongmc.tour.*;
import dev.rongmc.tour.server.TourService;
import java.nio.file.Files;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Exercises real client/server payloads, live world rendering and mouse-operated controls. */
public final class TourGameTest implements FabricClientGameTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void click(ClientGameTestContext c, String key) {
        c.waitFor(mc -> mc.screen instanceof TourScreen, 400);
        c.waitTicks(1);
        double[] point = c.computeOnClient(mc -> {
            for (var child : mc.screen.children()) {
                if (child instanceof net.minecraft.client.gui.components.Button button
                        && button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().equals(key)) {
                    check(button.active && button.visible, "Button is disabled: " + key);
                    return new double[]{(button.getX() + button.getWidth() / 2.0) * mc.getWindow().getScreenWidth() / mc.screen.width,
                            (button.getY() + button.getHeight() / 2.0) * mc.getWindow().getScreenHeight() / mc.screen.height};
                }
            }
            throw new AssertionError("Button not found: " + key);
        });
        c.getInput().setCursorPos(point[0], point[1]);
        c.getInput().pressMouse(0);
    }
    private static TourDefinition route(net.minecraft.server.level.ServerPlayer player) {
        var demo = TourService.demo(player);
        var second = demo.chapters().get(1);
        var a = second.anchor();
        return new TourDefinition(demo.version(), demo.name(), java.util.List.of(demo.chapters().getFirst(),
                new TourDefinition.Chapter(second.id(), second.title(), second.description(), second.dimension(),
                        new TourDefinition.Anchor(a.x() + 192, a.y(), a.z()), second.keyframes().stream()
                        .map(k -> new TourDefinition.Keyframe(k.seconds(), k.x() + 192, k.y(), k.z(), k.yaw(), k.pitch(), k.fov())).toList())));
    }
    private static void playing(ClientGameTestContext c) {
        c.waitFor(mc -> RongMcClient.tour().interactive(), 3600);
    }
    @Override public void runTest(ClientGameTestContext c) {
        c.getInput().resizeWindow(1280, 720);
        c.runOnClient(mc -> {
            mc.options.renderDistance().set(6);
            mc.options.guiScale().set(2);
            mc.options.framerateLimit().set(60);
            mc.options.enableVsync().set(false);
            mc.getLanguageManager().setSelected("zh_cn");
            mc.options.languageCode = "zh_cn";
            mc.reloadResourcePacks();
        });
        c.waitFor(mc -> mc.getOverlay() == null, 1200);
        net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave save;
        UUID savedUuid;
        TourService.ReturnPoint simulatedRecovery;
        TourDefinition automaticTour;
        try (var world = c.worldBuilder().create()) {
            save = world.getWorldSave();
            world.getClientWorld().waitForChunksRender();
            UUID uuid = c.computeOnClient(mc -> mc.player.getUUID());
            savedUuid = uuid;
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayer(uuid);
                var level = server.overworld();
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0) - 1;
                // A clearly labelled test pavilion, built only in this throwaway world.
                for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++)
                    for (int offset : new int[]{0, 192}) level.setBlockAndUpdate(new BlockPos(x + offset, ground, z), (Math.abs(x) == 9 || Math.abs(z) == 9 ? Blocks.POLISHED_DEEPSLATE : Blocks.SMOOTH_QUARTZ).defaultBlockState());
                for (int x : new int[]{-5, 5}) for (int z : new int[]{-5, 5}) for (int y = 1; y <= 7; y++)
                    for (int offset : new int[]{0, 192}) level.setBlockAndUpdate(new BlockPos(x + offset, ground + y, z), Blocks.QUARTZ_PILLAR.defaultBlockState());
                for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++)
                    for (int offset : new int[]{0, 192}) level.setBlockAndUpdate(new BlockPos(x + offset, ground + 8, z), (Math.abs(x) == 6 || Math.abs(z) == 6 ? Blocks.CHISELED_QUARTZ_BLOCK : Blocks.DARK_PRISMARINE).defaultBlockState());
                p.setGameMode(GameType.CREATIVE);
                p.teleportTo(level, 0, ground + 1, 0, java.util.Set.of(), 0, 0, false);
            });
            c.waitTicks(30);
            world.getClientWorld().waitForChunksRender();
            simulatedRecovery = world.getServer().computeOnServer(server -> {
                var p = server.getPlayerList().getPlayer(uuid);
                return new TourService.ReturnPoint(p.level().dimension().identifier().toString(), p.getX() + 2, p.getY(), p.getZ(), 0, 0,
                        "adventure", false, false, false, false);
            });
            automaticTour = world.getServer().computeOnServer(server -> route(server.getPlayerList().getPlayer(uuid)));
            OptionsSnapshot original = c.computeOnClient(OptionsSnapshot::capture);
            var optionsPath = c.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("options.txt"));
            String originalDisk = Files.exists(optionsPath) ? read(optionsPath) : null;
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayer(uuid);
                var service = RongMcTour.service(server);
                String offered = service.offer(p, route(p), true);
                check(service.hasSession(uuid), "Offer failed: " + offered);
            });
            c.waitForScreen(TourScreen.class);
            c.takeScreenshot("rongmc-01-loading");
            playing(c);
            c.takeScreenshot("rongmc-02-live-intro");
            c.runOnClient(mc -> {
                check(RongMcClient.tour().device() != null, "Missing actual device preflight");
                check(!mc.mouseHandler.isMouseGrabbed(), "Mouse must be available for clicking");
                mc.options.save();
            });
            if (originalDisk != null) check(originalDisk.equals(read(optionsPath)), "Temporary options leaked to options.txt");
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayer(uuid);
                check(p.isSpectator(), "Server must protect the player");
                check(RongMcTour.service(server).protectedPlayer(uuid), "Missing server protection");
                int before = RongMcTour.service(server).currentChapter(uuid);
                RongMcTour.service(server).control(p, new TourPackets.Control(UUID.randomUUID(), TourPackets.Action.CHAPTER, 1));
                check(RongMcTour.service(server).currentChapter(uuid) == before, "Stale session packet was accepted");
            });
            c.runOnClient(mc -> ClientPlayNetworking.send(new TourPackets.Control(UUID.randomUUID(), TourPackets.Action.SKIP, -99)));
            c.waitTicks(3);
            c.runOnClient(mc -> check(RongMcClient.tour().active(), "Invalid-session network packet cancelled a valid tour"));
            var first = c.computeOnClient(mc -> RongMcClient.tour().pose());
            c.waitTicks(30);
            check(!first.equals(c.computeOnClient(mc -> RongMcClient.tour().pose())), "Camera did not move through the live world");
            click(c, "rongmc.tour.pause");
            double pausedProgress = c.computeOnClient(mc -> RongMcClient.tour().progress());
            c.waitTicks(20);
            check(Math.abs(pausedProgress - c.computeOnClient(mc -> RongMcClient.tour().progress())) < .0001, "Paused camera advanced");
            click(c, "rongmc.tour.resume");
            click(c, "rongmc.tour.next");
            c.waitFor(mc -> RongMcClient.tour().chapterIndex() == 1 && RongMcClient.tour().interactive(), 1200);
            c.takeScreenshot("rongmc-03-second-chapter");
            c.runOnClient(mc -> RongMcClient.tour().togglePause()); c.waitTicks(5);
            var camera = c.computeOnClient(mc -> RongMcClient.tour().pose());
            world.getServer().runOnServer(server -> check(Math.abs(server.getPlayerList().getPlayer(uuid).getX() - camera.x()) < .5,
                    "Server chunk anchor did not follow the second scene camera"));
            c.runOnClient(mc -> RongMcClient.tour().togglePause());
            click(c, "rongmc.tour.back");
            c.waitFor(mc -> RongMcClient.tour().chapterIndex() == 0 && RongMcClient.tour().interactive(), 1200);
            click(c, "rongmc.tour.quality");
            playing(c);
            c.runOnClient(mc -> check(!RongMcClient.tour().automatic() && RongMcClient.tour().tier() == QualityPolicy.Tier.LOW, "Manual quality button did not apply"));
            click(c, "rongmc.tour.motion");
            c.waitTicks(2);
            var staticPose = c.computeOnClient(mc -> RongMcClient.tour().pose());
            c.waitTicks(20);
            check(staticPose.equals(c.computeOnClient(mc -> RongMcClient.tour().pose())), "Reduced-motion view is not static");
            c.getInput().resizeWindow(854, 480);
            c.waitTicks(5);
            c.takeScreenshot("rongmc-04-small-window");
            click(c, "rongmc.tour.skip");
            c.waitFor(mc -> !RongMcClient.tour().active(), 400);
            c.runOnClient(mc -> check(original.equals(OptionsSnapshot.capture(mc)), "Game options were not restored exactly"));
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayer(uuid);
                var s = RongMcTour.service(server);
                check(p.gameMode() == GameType.CREATIVE && Math.abs(p.getX()) < .1 && Math.abs(p.getZ()) < .1, "Return position/mode mismatch");
                check(!s.hasSession(uuid) && s.recoveryCount() == 0 && s.completedVersion(uuid) == 1, "Session cleanup/completion failed");
                s.offer(p, route(p), true);
            });
            playing(c);
            click(c, "rongmc.tour.next");
            c.waitFor(mc -> RongMcClient.tour().chapterIndex() == 1 && RongMcClient.tour().interactive(), 1200);
            click(c, "rongmc.tour.finish");
            c.waitFor(mc -> !RongMcClient.tour().active(), 400);
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayer(uuid);
                check(p.gameMode() == GameType.CREATIVE && RongMcTour.service(server).recoveryCount() == 0, "Finish did not restore server state");
                RongMcTour.service(server).offer(p, route(p), true);
            });
            playing(c);
            // Closing the world while touring exercises the real disconnect/LEAVE cleanup path.
        }
        c.runOnClient(mc -> check(!RongMcClient.tour().active() && !RongMcClient.tour().temporaryOptions(), "Disconnect left a tour or temporary options active"));
        var journal = save.getSaveDirectory().resolve("rongmc-tour-state.json");
        try {
            var state = JsonFiles.read(journal, TourService.SavedState.class);
            check(state.returns.isEmpty() && state.completed.getOrDefault(savedUuid, 0) == 1, "Disconnect did not persist a clean state");
            // Simulate an interrupted session's durable journal without killing a process.
            state.returns.put(savedUuid, simulatedRecovery);
            JsonFiles.write(journal, state);
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        var config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("rongmc-tour-server.json");
        String priorConfig = read(config);
        try {
            JsonFiles.write(config, new TourService.Configuration(true, true, 2, automaticTour));
            try (var reopened = save.open()) {
                reopened.getServer().runOnServer(server -> {
                    var player = server.getPlayerList().getPlayer(savedUuid);
                    var service = RongMcTour.service(server);
                    check(service.recoveryCount() == 0 && service.completedVersion(savedUuid) == 1, "Recovery journal was not consumed or completion was lost");
                    check(player.gameMode() == GameType.ADVENTURE && Math.abs(player.getX() - simulatedRecovery.x()) < .1,
                            "Interrupted session did not restore its saved position and mode");
                });
                c.waitTicks(80);
                c.runOnClient(mc -> check(!RongMcClient.tour().active(), "Already-completed intro replayed on join"));
            }
            var newerTour = new TourDefinition(2, automaticTour.name(), automaticTour.chapters());
            JsonFiles.write(config, new TourService.Configuration(true, true, 2, newerTour));
            try (var reopened = save.open()) {
                playing(c);
                c.takeScreenshot("rongmc-05-first-join-auto");
                click(c, "rongmc.tour.skip");
                c.waitFor(mc -> !RongMcClient.tour().active(), 400);
                reopened.getServer().runOnServer(server -> {
                    var player = server.getPlayerList().getPlayer(savedUuid);
                    var service = RongMcTour.service(server);
                    check(service.completedVersion(savedUuid) == 2 && service.recoveryCount() == 0, "New intro version was not recorded");
                    check(player.gameMode() == GameType.ADVENTURE, "Automatic intro did not restore original game mode");
                });
            }
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        finally {
            try { Files.writeString(config, priorConfig); }
            catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
    }
    private static String read(java.nio.file.Path p) {
        try { return Files.readString(p); } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
}

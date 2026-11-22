package dev.rongmc.test;

import dev.rongmc.client.RongMcClient;
import dev.rongmc.client.editor.CameraEditorScreen;
import dev.rongmc.client.tour.OptionsSnapshot;
import dev.rongmc.tour.JsonFiles;
import dev.rongmc.tour.RongMcTour;
import dev.rongmc.tour.TourPackets;
import dev.rongmc.tour.server.TourService;
import dev.rongmc.tour.editor.CameraProjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.glfw.GLFW;

/** Uses actual key/mouse events to author, preview, persist, export and play a ReplayStudio route. */
public final class CameraEditorGameTest implements FabricClientGameTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static AbstractWidget widget(net.minecraft.client.Minecraft mc, String key) {
        check(mc.screen != null, "No screen for " + key);
        for (var child : mc.screen.children()) if (child instanceof AbstractWidget w
                && w.getMessage().getContents() instanceof TranslatableContents text && text.getKey().equals(key)) return w;
        throw new AssertionError("Widget not found: " + key);
    }
    private static double[] point(ClientGameTestContext c, String key) {
        return c.computeOnClient(mc -> {
            var w = widget(mc, key); check(w.visible && w.active, "Widget disabled/hidden: " + key);
            return new double[]{(w.getX() + w.getWidth() / 2.0) * mc.getWindow().getScreenWidth() / mc.screen.width,
                    (w.getY() + w.getHeight() / 2.0) * mc.getWindow().getScreenHeight() / mc.screen.height};
        });
    }
    private static void click(ClientGameTestContext c, String key) {
        c.waitTicks(1); var p = point(c, key); c.getInput().setCursorPos(p[0], p[1]); c.getInput().pressMouse(0);
    }
    private static void field(ClientGameTestContext c, String key, String value) {
        click(c, key);
        int length = c.computeOnClient(mc -> ((EditBox) widget(mc, key)).getValue().length());
        c.getInput().pressKey(GLFW.GLFW_KEY_END);
        for (int i = 0; i < length + 1; i++) c.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
        c.getInput().typeChars(value);
        c.runOnClient(mc -> check(((EditBox) widget(mc, key)).getValue().equals(value), "Keyboard replacement failed for " + key));
    }
    private static void open(ClientGameTestContext c) { c.getInput().pressKey(GLFW.GLFW_KEY_F8); c.waitForScreen(CameraEditorScreen.class); c.waitTicks(2); }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception e) { throw new RuntimeException(e); } }

    @Override public void runTest(ClientGameTestContext c) {
        c.getInput().resizeWindow(1280, 720);
        c.runOnClient(mc -> {
            mc.options.renderDistance().set(6); mc.options.guiScale().set(2); mc.options.framerateLimit().set(60); mc.options.enableVsync().set(false);
            mc.getLanguageManager().setSelected("zh_cn"); mc.options.languageCode = "zh_cn"; mc.reloadResourcePacks();
        });
        c.waitFor(mc -> mc.getOverlay() == null, 1200);
        try (var world = c.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            world.getClientWorld().waitForChunksRender();
            var uuid = c.computeOnClient(mc -> mc.player.getUUID());
            int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0) - 1);
            world.getServer().runOnServer(server -> {
                var level = server.overworld(); var p = server.getPlayerList().getPlayer(uuid);
                for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) level.setBlockAndUpdate(new BlockPos(x, ground, z), Blocks.SMOOTH_QUARTZ.defaultBlockState());
                for (int x : new int[]{-5, 5}) for (int z : new int[]{-5, 5}) for (int y = 1; y <= 7; y++)
                    level.setBlockAndUpdate(new BlockPos(x, ground + y, z), Blocks.QUARTZ_PILLAR.defaultBlockState());
                for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++)
                    level.setBlockAndUpdate(new BlockPos(x, ground + 8, z), Blocks.DARK_PRISMARINE.defaultBlockState());
                p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = true; p.onUpdateAbilities();
                p.teleportTo(level, 14, ground + 9, 18, Set.of(), 145, 18, false);
            });
            c.waitTicks(10); world.getClientWorld().waitForChunksRender();
            open(c); click(c, "rongmc.camera.new_project");
            field(c, "rongmc.camera.file", "camera-e2e");
            click(c, "rongmc.camera.record");
            c.runOnClient(mc -> check(RongMcClient.editor().draft().chapter().keyframes().size() == 1, "First frame was not captured"));
            click(c, "rongmc.camera.view");
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayer(uuid);
                p.teleportTo(server.overworld(), 206, ground + 70, 20, Set.of(), 190, 45, false);
            });
            c.waitTicks(6); c.getInput().pressKey(GLFW.GLFW_KEY_F7);
            c.waitFor(mc -> RongMcClient.editor().draft().chapter().keyframes().size() == 2, 400);
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayer(uuid);
                p.teleportTo(server.overworld(), -6, ground + 8, 12, Set.of(), 210, 20, false);
            });
            c.waitTicks(6); c.getInput().pressKey(GLFW.GLFW_KEY_F7);
            c.waitFor(mc -> RongMcClient.editor().draft().chapter().keyframes().size() == 3, 400);
            open(c);
            field(c, "rongmc.camera.seconds", "12.5"); field(c, "rongmc.camera.fov", "62");
            c.takeScreenshot("rongmc-camera-00-inspector-fields");
            click(c, "rongmc.camera.apply");
            c.runOnClient(mc -> check(RongMcClient.editor().draft().selected().seconds() == 12.5 && RongMcClient.editor().draft().selected().fov() == 62,
                    "Inspector edits were not applied: selected=" + RongMcClient.editor().draft().selected()
                            + "; status=" + RongMcClient.editor().status() + "; timeField=" + ((EditBox) widget(mc, "rongmc.camera.seconds")).getValue()
                            + "; fovField=" + ((EditBox) widget(mc, "rongmc.camera.fov")).getValue()));
            click(c, "rongmc.camera.save");
            var root = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("rongmc-camera");
            check(Files.exists(root.resolve("projects/camera-e2e.json")), "Project was not saved");
            OptionsSnapshot original = c.computeOnClient(OptionsSnapshot::capture);
            var options = c.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("options.txt"));
            String disk = Files.exists(options) ? read(options) : null;
            click(c, "rongmc.camera.play");
            c.waitFor(mc -> RongMcClient.editor().previewActive() && RongMcClient.editor().playing(), 400);
            double start = c.computeOnClient(mc -> RongMcClient.editor().time());
            c.waitTicks(24);
            c.runOnClient(mc -> {
                check(RongMcClient.editor().time() > start, "Preview clock did not advance");
                check(RongMcClient.editor().pose() != null, "ReplayStudio camera was not applied"); mc.options.save();
            });
            if (disk != null) check(disk.equals(read(options)), "Preview options leaked to disk");
            c.takeScreenshot("rongmc-camera-01-editor");
            // Seek to a frame 192 blocks away and >48 blocks above the chapter anchor.
            c.runOnClient(mc -> RongMcClient.editor().scrub(5));
            c.waitFor(mc -> !RongMcClient.editor().loadingPreview() && RongMcClient.editor().pose() != null
                    && Math.abs(RongMcClient.editor().pose().x() - 206) < .1 && Math.abs(mc.player.getX() - 206) < .2, 1800);
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayer(uuid); var service = RongMcTour.service(server);
                check(p.isSpectator() && Math.abs(p.getX() - 206) < .2 && service.recoveryCount() == 1 && service.completedVersion(uuid) == 0,
                        "Long preview did not stream from a protected, recoverable camera anchor");
            });
            c.takeScreenshot("rongmc-camera-05-long-preview");
            click(c, "rongmc.camera.play");
            click(c, "rongmc.camera.pause");
            double paused = c.computeOnClient(mc -> RongMcClient.editor().time()); c.waitTicks(10);
            check(Math.abs(paused - c.computeOnClient(mc -> RongMcClient.editor().time())) < .001, "Paused preview advanced");
            // Real drag of the final keyframe from 12.5 s toward 15 s; one undo restores the entire drag.
            double[] mark = c.computeOnClient(mc -> {
                var timeline = widget(mc, "rongmc.camera.timeline_label");
                return new double[]{(timeline.getX() + 8 + (timeline.getWidth() - 16) * 12.5 / 20) * mc.getWindow().getScreenWidth() / mc.screen.width,
                        (timeline.getY() + 21) * mc.getWindow().getScreenHeight() / mc.screen.height,
                        (timeline.getWidth() - 16) * 2.5 / 20 * mc.getWindow().getScreenWidth() / mc.screen.width};
            });
            c.getInput().setCursorPos(mark[0], mark[1]); c.getInput().holdMouse(0); c.waitTicks(1);
            c.getInput().moveCursor(mark[2], 0); c.waitTicks(2); c.getInput().releaseMouse(0); c.waitTicks(2);
            c.runOnClient(mc -> check(RongMcClient.editor().draft().selected().seconds() > 13, "Timeline drag did not retime keyframe"));
            click(c, "rongmc.camera.undo");
            c.runOnClient(mc -> check(RongMcClient.editor().draft().selected().seconds() == 12.5, "Timeline drag required more than one undo"));
            click(c, "rongmc.camera.redo"); click(c, "rongmc.camera.undo");
            click(c, "rongmc.camera.curve");
            c.runOnClient(mc -> check(RongMcClient.editor().draft().chapter().mode().equals("linear"), "Interpolation selector did not change"));
            click(c, "rongmc.camera.undo");
            click(c, "rongmc.camera.export");
            var exported = root.resolve("exports/camera-e2e.json"); check(Files.exists(exported), "Tour export was not created");
            String priorExport = read(exported);
            click(c, "rongmc.camera.new_chapter"); click(c, "rongmc.camera.export");
            check(priorExport.equals(read(exported)), "Invalid incomplete chapter overwrote a valid export");
            click(c, "rongmc.camera.undo"); click(c, "rongmc.camera.load");
            c.runOnClient(mc -> check(RongMcClient.editor().draft().chapter().keyframes().size() == 3, "Saved project was not restored"));
            c.getInput().resizeWindow(854, 480); c.waitTicks(3); c.takeScreenshot("rongmc-camera-02-compact");
            c.getInput().setCursorPos(70, 220); c.getInput().scroll(-8); c.waitTicks(2);
            c.takeScreenshot("rongmc-camera-03-scrolled");
            c.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); c.waitForScreen(null);
            c.runOnClient(mc -> check(!RongMcClient.editor().previewActive() && original.equals(OptionsSnapshot.capture(mc)), "Editor did not restore game options"));
            try {
                var export = JsonFiles.read(exported, CameraProjectStore.Export.class);
                check(!export.enabled() && export.tour().chapters().getFirst().mode().equals("catmull_rom"), "Export mode/disabled flag incorrect");
                world.getServer().runOnServer(server -> {
                    var p = server.getPlayerList().getPlayer(uuid);
                    String result = RongMcTour.service(server).offer(p, export.tour(), true);
                    check(RongMcTour.service(server).hasSession(uuid), "Exported route could not be offered: " + result);
                });
            } catch (java.io.IOException e) { throw new RuntimeException(e); }
            c.waitFor(mc -> RongMcClient.tour().interactive(), 3600);
            c.waitTicks(15); c.takeScreenshot("rongmc-camera-04-exported-guide");
            click(c, "rongmc.tour.skip"); c.waitFor(mc -> !RongMcClient.tour().active(), 400);
            c.getInput().resizeWindow(1280, 720); open(c); click(c, "rongmc.camera.play");
            c.waitFor(mc -> RongMcClient.editor().previewActive(), 400);
            // Leave while previewing to test the network-thread disconnect path too.
        }
        c.runOnClient(mc -> check(!RongMcClient.editor().previewActive(), "Disconnect retained editor preview"));
        // An actual non-operator request must be denied by the companion server.
        try (var denied = c.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(false)).create()) {
            denied.getClientWorld().waitForChunksRender(); c.getInput().pressKey(GLFW.GLFW_KEY_F8);
            c.waitFor(mc -> RongMcClient.editor().status().contains("仅供管理员"), 400);
            c.runOnClient(mc -> check(!(mc.screen instanceof CameraEditorScreen) && !RongMcClient.editor().previewActive(), "Non-operator opened editor"));
            // Bypass the editor UI entirely: the new path-streaming request must enforce permission itself.
            var deniedUuid = c.computeOnClient(mc -> mc.player.getUUID());
            var validRoute = denied.getServer().computeOnServer(server -> {
                var demo = TourService.demo(server.getPlayerList().getPlayer(deniedUuid));
                var route = new dev.rongmc.tour.TourDefinition(demo.version(), demo.name(), java.util.List.of(demo.chapters().getFirst()));
                route.validate(); return JsonFiles.GSON.toJson(route);
            });
            c.runOnClient(mc -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new TourPackets.PreviewRequest(java.util.UUID.randomUUID(), validRoute)));
            c.waitTicks(10);
            denied.getServer().runOnServer(server -> {
                var service = RongMcTour.service(server);
                check(!service.hasSession(deniedUuid) && service.recoveryCount() == 0
                        && !server.getPlayerList().getPlayer(deniedUuid).isSpectator(), "Non-operator started a streaming preview");
            });
        }
    }
}

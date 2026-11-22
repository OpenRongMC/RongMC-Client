package dev.rongmc.client;

import net.fabricmc.api.ClientModInitializer;
import dev.rongmc.client.tour.TourController;
import dev.rongmc.client.editor.CameraEditorController;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.resources.Identifier;
import org.lwjgl.sdl.SDLKeycode;
import dev.rongmc.tour.TourPackets;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the RongMC building-server client experience. */
public final class RongMcClient implements ClientModInitializer {
    public static final String MOD_ID = "rongmc_client";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final TourController TOUR = new TourController();
    private static final CameraEditorController EDITOR = new CameraEditorController();
    public static TourController tour() { return TOUR; }
    public static CameraEditorController editor() { return EDITOR; }

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(TourPackets.Offer.TYPE, (packet, context) -> {
            if (EDITOR.handles(packet.session())) EDITOR.offer(packet);
            else { EDITOR.closeScreen(); TOUR.offer(packet); }
        });
        ClientPlayNetworking.registerGlobalReceiver(TourPackets.EditorReply.TYPE, (packet, context) -> EDITOR.reply(packet));
        ClientPlayNetworking.registerGlobalReceiver(TourPackets.Scene.TYPE, (packet, context) -> {
            if (EDITOR.handles(packet.session())) EDITOR.scene(packet); else TOUR.scene(packet);
        });
        ClientPlayNetworking.registerGlobalReceiver(TourPackets.Stop.TYPE, (packet, context) -> {
            if (EDITOR.handles(packet.session())) EDITOR.stopped(packet); else TOUR.stop(packet);
        });
        ClientPlayNetworking.registerGlobalReceiver(TourPackets.CursorAck.TYPE, (packet, context) -> { });
        ClientTickEvents.END_CLIENT_TICK.register(TOUR::tick);
        ClientTickEvents.END_CLIENT_TICK.register(new dev.rongmc.client.setup.DeviceSetup()::showOnFirstLaunch);
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "camera"));
        var open = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rongmc.camera.open", InputConstants.Type.KEYBOARD, SDLKeycode.SDLK_F8, category));
        var record = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rongmc.camera.record", InputConstants.Type.KEYBOARD, SDLKeycode.SDLK_F7, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            EDITOR.tick(client);
            while (open.consumeClick()) EDITOR.request(TourPackets.EditorAction.OPEN);
            while (record.consumeClick()) if (client.gui.screen() == null) EDITOR.request(TourPackets.EditorAction.RECORD);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> { EDITOR.disconnect(); TOUR.dispose(); }));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { EDITOR.closeScreen(); TOUR.cancel(); });
        LOGGER.info("RongMC Client initialized: real-time welcome tour available.");
    }
}

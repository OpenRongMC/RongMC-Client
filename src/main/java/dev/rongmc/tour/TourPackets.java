package dev.rongmc.tour;

import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class TourPackets {
    private TourPackets() {}
    public static final int MAX_JSON = 24000;
    public enum Action { READY, CHAPTER, HEARTBEAT, FINISH, SKIP, CANCEL }
    public enum EditorAction { OPEN, RECORD }

    public record PreviewRequest(UUID session, String json) implements CustomPacketPayload {
        public static final Type<PreviewRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rongmc_client", "preview_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PreviewRequest> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUUID(p.session); b.writeUtf(p.json, MAX_JSON); },
                b -> new PreviewRequest(b.readUUID(), b.readUtf(MAX_JSON)));
        @Override public Type<PreviewRequest> type() { return TYPE; }
    }
    /** Sends a time on a server-approved path, never client-supplied teleport coordinates. */
    public record Cursor(UUID session, int chapter, double seconds) implements CustomPacketPayload {
        public static final Type<Cursor> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rongmc_client", "camera_cursor"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Cursor> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUUID(p.session); b.writeVarInt(p.chapter); b.writeDouble(p.seconds); },
                b -> new Cursor(b.readUUID(), b.readVarInt(), b.readDouble()));
        @Override public Type<Cursor> type() { return TYPE; }
    }
    public record CursorAck(UUID session, int chapter, double seconds) implements CustomPacketPayload {
        public static final Type<CursorAck> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rongmc_client", "camera_cursor_ack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CursorAck> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUUID(p.session); b.writeVarInt(p.chapter); b.writeDouble(p.seconds); },
                b -> new CursorAck(b.readUUID(), b.readVarInt(), b.readDouble()));
        @Override public Type<CursorAck> type() { return TYPE; }
    }

    public record EditorRequest(EditorAction action) implements CustomPacketPayload {
        public static final Type<EditorRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rongmc_client", "editor_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EditorRequest> CODEC = StreamCodec.of(
                (b, p) -> b.writeEnum(p.action), b -> new EditorRequest(b.readEnum(EditorAction.class)));
        @Override public Type<EditorRequest> type() { return TYPE; }
    }
    public record EditorReply(EditorAction action, boolean allowed, String reason) implements CustomPacketPayload {
        public static final Type<EditorReply> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rongmc_client", "editor_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EditorReply> CODEC = StreamCodec.of(
                (b, p) -> { b.writeEnum(p.action); b.writeBoolean(p.allowed); b.writeUtf(p.reason, 256); },
                b -> new EditorReply(b.readEnum(EditorAction.class), b.readBoolean(), b.readUtf(256)));
        @Override public Type<EditorReply> type() { return TYPE; }
    }

    public record Offer(UUID session, String json) implements CustomPacketPayload {
        public static final Type<Offer> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rongmc_client", "offer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Offer> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUUID(p.session); b.writeUtf(p.json, MAX_JSON); },
                b -> new Offer(b.readUUID(), b.readUtf(MAX_JSON)));
        @Override public Type<Offer> type() { return TYPE; }
    }
    public record Scene(UUID session, int chapter) implements CustomPacketPayload {
        public static final Type<Scene> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rongmc_client", "scene"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Scene> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUUID(p.session); b.writeVarInt(p.chapter); }, b -> new Scene(b.readUUID(), b.readVarInt()));
        @Override public Type<Scene> type() { return TYPE; }
    }
    public record Control(UUID session, Action action, int chapter) implements CustomPacketPayload {
        public static final Type<Control> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rongmc_client", "control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUUID(p.session); b.writeEnum(p.action); b.writeVarInt(p.chapter); },
                b -> new Control(b.readUUID(), b.readEnum(Action.class), b.readVarInt()));
        @Override public Type<Control> type() { return TYPE; }
    }
    public record Stop(UUID session, String reason) implements CustomPacketPayload {
        public static final Type<Stop> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rongmc_client", "stop"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Stop> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUUID(p.session); b.writeUtf(p.reason, 256); }, b -> new Stop(b.readUUID(), b.readUtf(256)));
        @Override public Type<Stop> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(PreviewRequest.TYPE, PreviewRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Cursor.TYPE, Cursor.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CursorAck.TYPE, CursorAck.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(EditorRequest.TYPE, EditorRequest.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EditorReply.TYPE, EditorReply.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Offer.TYPE, Offer.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Scene.TYPE, Scene.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Stop.TYPE, Stop.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Control.TYPE, Control.CODEC);
    }
}

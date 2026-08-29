package com.kuronami.oldglassphotograph.network;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.capture.PhotoCaptureController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Forge 1.20.1 {@code SimpleChannel} ベースのネットワーク層。この帯に
 * {@code CustomPacketPayload} / {@code PayloadRegistrar} は存在しない（LOADERS.md 正本）ため、
 * fabric セルの raw ByteBuf チャンネル群を SimpleChannel のメッセージ登録へ写した
 * （mod-076 forge-1.20.1 の実績方式・mod-003 と同型）。
 *
 * <ul>
 *   <li>{@code NetworkRegistry.newSimpleChannel} でビルド（バージョン照合は常に通す）</li>
 *   <li>encoder = payload の {@code write(FriendlyByteBuf)}、decoder = static {@code read(FriendlyByteBuf)}
 *       ＝fabric セルと同じ直列化本体を流用</li>
 *   <li>handler signature: {@code BiConsumer<MSG, Supplier<NetworkEvent.Context>>}</li>
 *   <li>送信: {@code CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg)} /
 *       {@code CHANNEL.sendToServer(msg)}</li>
 * </ul>
 */
public final class OgpNet {

    private static final String PROTOCOL_VERSION = "1";

    /** 全 6 型を載せる単一チャンネル（discriminator は SimpleChannel が管理する）。 */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(OldGlassPhotograph.MODID, "main"),
            () -> PROTOCOL_VERSION,
            v -> true,
            v -> true);

    private OgpNet() {
    }

    /** common setup（enqueueWork 内）から呼ぶ。 */
    public static void register() {
        int discriminator = 0;
        // client -> server（handler は common の PhotoCaptureController を直接呼ぶ）
        CHANNEL.registerMessage(discriminator++, ShutterRequestPayload.class,
                ShutterRequestPayload::write, ShutterRequestPayload::read,
                (message, ctx) -> handleOnServer(message, ctx, PhotoCaptureController::openShutter),
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(discriminator++, PhotoMapPixelsPayload.class,
                PhotoMapPixelsPayload::write, PhotoMapPixelsPayload::read,
                (message, ctx) -> handleOnServer(message, ctx, PhotoCaptureController::receivePixels),
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(discriminator++, PhotoCaptureAbortPayload.class,
                PhotoCaptureAbortPayload::write, PhotoCaptureAbortPayload::read,
                (message, ctx) -> handleOnServer(message, ctx, PhotoCaptureController::abortCapture),
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // server -> client（handler は client クラスを lambda 本体でだけ参照する＝server でロードされない）
        CHANNEL.registerMessage(discriminator++, ViewfinderOpenPayload.class,
                ViewfinderOpenPayload::write, ViewfinderOpenPayload::read,
                (message, ctx) -> handleOnClient(message, ctx,
                        () -> com.kuronami.oldglassphotograph.client.OgpClientNet.openViewfinder(message)),
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(discriminator++, ShutterOpenPayload.class,
                ShutterOpenPayload::write, ShutterOpenPayload::read,
                (message, ctx) -> handleOnClient(message, ctx,
                        () -> com.kuronami.oldglassphotograph.client.OgpClientNet.openShutter(message)),
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(discriminator++, ViewfinderClosePayload.class,
                ViewfinderClosePayload::write, ViewfinderClosePayload::read,
                (message, ctx) -> handleOnClient(message, ctx,
                        com.kuronami.oldglassphotograph.client.OgpClientNet::closeViewfinder),
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private interface ServerHandler<T> {
        void accept(ServerPlayer player, T message);
    }

    private static <T> void handleOnServer(T message, Supplier<NetworkEvent.Context> ctxSup,
                                            ServerHandler<T> handler) {
        NetworkEvent.Context ctx = ctxSup.get();
        ServerPlayer sender = ctx.getSender();
        if (sender != null) {
            ctx.enqueueWork(() -> handler.accept(sender, message));
        }
        ctx.setPacketHandled(true);
    }

    private static <T> void handleOnClient(T message, Supplier<NetworkEvent.Context> ctxSup, Runnable work) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(work);
        ctx.setPacketHandled(true);
    }

    // ------------------------------------------------------------ 送信

    public static void sendToPlayer(ServerPlayer player, ViewfinderOpenPayload message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToPlayer(ServerPlayer player, ShutterOpenPayload message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToPlayer(ServerPlayer player, ViewfinderClosePayload message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToServer(ShutterRequestPayload message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendToServer(PhotoMapPixelsPayload message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendToServer(PhotoCaptureAbortPayload message) {
        CHANNEL.sendToServer(message);
    }
}

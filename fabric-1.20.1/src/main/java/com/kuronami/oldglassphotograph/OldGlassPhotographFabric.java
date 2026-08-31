package com.kuronami.oldglassphotograph;

import com.kuronami.oldglassphotograph.capture.PhotoCaptureController;
import com.kuronami.oldglassphotograph.network.OgpNet;
import com.kuronami.oldglassphotograph.network.PhotoCaptureAbortPayload;
import com.kuronami.oldglassphotograph.network.PhotoMapPixelsPayload;
import com.kuronami.oldglassphotograph.network.ShutterRequestPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric の main entrypoint。登録と server 側の配線だけを持ち、client 専用クラスは
 * 触らない（dedicated server 安全性は構造で担保）。
 */
public final class OldGlassPhotographFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // --- 登録（OgpRegistry は static 初期化順に Registry.register する） ---
        OgpRegistry.registerCauldronInteractions();
        OgpObjects.wire(
                () -> OgpRegistry.CAMERA_BLOCK_ENTITY,
                () -> OgpRegistry.DARKROOM_TABLE_BLOCK_ENTITY,
                () -> OgpRegistry.WET_PLATE_CAMERA,
                () -> OgpRegistry.DARKROOM_TABLE,
                () -> OgpRegistry.GLASS_PLATE,
                () -> OgpRegistry.PHOTOGRAPH,
                () -> OgpRegistry.COLLODION_KIT,
                () -> OgpRegistry.DEVELOPER,
                () -> OgpRegistry.FIXER);

        // --- タブの中身を工程順で流す: 板 → 薬品 → カメラ → 暗箱。
        //     写真はタブに出さない（像を持たない写真は白紙の板でしかない。26.x と同じ判断）。
        ItemGroupEvents.modifyEntriesEvent(OgpRegistry.TAB_KEY).register(entries -> {
            entries.accept(OgpRegistry.GLASS_PLATE);
            entries.accept(OgpRegistry.COLLODION_KIT);
            entries.accept(OgpRegistry.DEVELOPER);
            entries.accept(OgpRegistry.FIXER);
            entries.accept(OgpRegistry.WET_PLATE_CAMERA_ITEM);
            entries.accept(OgpRegistry.DARKROOM_TABLE_ITEM);
        });

        // --- 送信口 ---
        OgpNet.wireServer(new OgpNet.Sink() {
            @Override
            public void sendToPlayer(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
                ServerPlayNetworking.send(player, channel, buf);
            }

            @Override
            public void sendToServer(ResourceLocation channel, FriendlyByteBuf buf) {
                // server 側では client → server 経路を使わない（握り潰す）。
            }
        });

        // --- 受信口（client -> server）。ハンドラは netty スレッドで来るので main へ投げる。
        ServerPlayNetworking.registerGlobalReceiver(OgpNet.CHANNEL_SHUTTER_REQUEST,
                (server, player, handler, buf, responseSender) -> {
                    ShutterRequestPayload message = ShutterRequestPayload.read(buf);
                    server.execute(() -> PhotoCaptureController.openShutter(player, message));
                });
        ServerPlayNetworking.registerGlobalReceiver(OgpNet.CHANNEL_PHOTO_MAP_PIXELS,
                (server, player, handler, buf, responseSender) -> {
                    PhotoMapPixelsPayload message = PhotoMapPixelsPayload.read(buf);
                    server.execute(() -> PhotoCaptureController.receivePixels(player, message));
                });
        ServerPlayNetworking.registerGlobalReceiver(OgpNet.CHANNEL_CAPTURE_ABORT,
                (server, player, handler, buf, responseSender) -> {
                    PhotoCaptureAbortPayload message = PhotoCaptureAbortPayload.read(buf);
                    server.execute(() -> PhotoCaptureController.abortCapture(player, message));
                });

        // --- server 落下時に送信フックを外す（次の world に古い sink を残さない）。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> OgpNet.wireServer(null));
    }
}

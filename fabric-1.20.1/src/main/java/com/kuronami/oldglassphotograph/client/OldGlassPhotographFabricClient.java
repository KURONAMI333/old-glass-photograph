package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.kuronami.oldglassphotograph.client.render.PhotographItemRenderer;
import com.kuronami.oldglassphotograph.client.view.PhotographViewer;
import com.kuronami.oldglassphotograph.network.OgpNet;
import com.kuronami.oldglassphotograph.network.ShutterOpenPayload;
import com.kuronami.oldglassphotograph.network.ViewfinderClosePayload;
import com.kuronami.oldglassphotograph.network.ViewfinderOpenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric の client entrypoint。NeoForge セルの {@code OgpClient} が担っていた
 * 配線を Fabric 1.20.1 の機構へ置き換えたもの。共通の処理は各 common / client 共通クラスが持つ。
 *
 * <p>NeoForge（1.21.1 セル）→ Fabric 1.20.1 の対応:
 * <ul>
 *   <li>RegisterGuiLayersEvent → {@code HudRenderCallback}（引数は GuiGraphics + 部分 tick）</li>
 *   <li>RenderLevelStageEvent.AfterLevel → {@code WorldRenderEvents.END}
 *       （この帯に LevelRenderEvents は無い。END はレベル描画の終端・HUD/GUI 合成前で同意味）</li>
 *   <li>ClientTickEvent.Post ×3 リスナ → {@code ClientTickEvents.END_CLIENT_TICK} ×1 に同順で委譲</li>
 *   <li>ViewportEvent 系 / 入力抑止 / 画面開始 / 手持ち差し替え → mixin（mixin パッケージ参照）</li>
 *   <li>RegisterClientExtensionsEvent（BEWLR）→ {@code BuiltinItemRendererRegistry}
 *       ＋ models/item/photograph.json を builtin/entity へ</li>
 * </ul>
 */
public final class OldGlassPhotographFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // --- この帯では hideGui を使わない（mod の HUD レイヤごと消えるため）。
        PhotoCaptureClient.disableVanillaHudSuppression();

        // --- 撮影: payload 受信。ハンドラは netty スレッドで来るので main へ投げる。
        ClientPlayNetworking.registerGlobalReceiver(OgpNet.CHANNEL_VIEWFINDER_OPEN,
                (client, handler, buf, responseSender) -> {
                    ViewfinderOpenPayload message = ViewfinderOpenPayload.read(buf);
                    client.execute(() -> PhotoCaptureClient.openViewfinder(message));
                });
        ClientPlayNetworking.registerGlobalReceiver(OgpNet.CHANNEL_SHUTTER_OPEN,
                (client, handler, buf, responseSender) -> {
                    ShutterOpenPayload message = ShutterOpenPayload.read(buf);
                    client.execute(() -> PhotoCaptureClient.openShutter(message));
                });
        ClientPlayNetworking.registerGlobalReceiver(OgpNet.CHANNEL_VIEWFINDER_CLOSE,
                (client, handler, buf, responseSender) -> {
                    ViewfinderClosePayload.read(buf);
                    client.execute(PhotoCaptureClient::closeViewfinder);
                });

        // --- 送信口（client -> server）。
        OgpNet.wireClient(new OgpNet.Sink() {
            @Override
            public void sendToPlayer(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
                // client 側では server -> player 経路を使わない（握り潰す）。
            }

            @Override
            public void sendToServer(ResourceLocation channel, FriendlyByteBuf buf) {
                ClientPlayNetworking.send(channel, buf);
            }
        });

        // --- 每 tick（板バー・撮影状態機械・拡大面）。26.x fabric セルと同じ同順委譲。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            OgpClientCommon.trackPlateUseProgress();
            PhotoCaptureClient.endClientTick();
            PhotographViewer.endClientTick();
        });

        // --- レベル描画終端での撮影（GUI 合成前）。
        WorldRenderEvents.END.register(context -> PhotoCaptureClient.onLevelRenderEnd());

        // --- HUD レイヤ（viewfinder / photograph_view）。
        HudRenderCallback.EVENT.register(PhotoCaptureClient::renderViewfinder);
        HudRenderCallback.EVENT.register(PhotographViewer::render);

        // --- 写真の面（opener 結線）と、画面が開いたら面を閉じる判定は mixin 側。
        PhotographViewer.init();

        // --- item model 系: 板の段階モデルは ItemProperties（vanilla compass/clock 方式）。
        PlateStageProperty.register();

        // --- 写真の面そのものは builtin/entity 経路（BuiltinItemRenderer）で描く。
        BuiltinItemRendererRegistry.INSTANCE.register(
                com.kuronami.oldglassphotograph.OgpObjects.photograph(), PhotographItemRenderer.INSTANCE);

        // --- 製図台ガードは 1.20.1 では不要（CartographyPhotographGuard 参照・申し送り）。
    }
}

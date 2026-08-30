package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.kuronami.oldglassphotograph.client.render.PhotographSpecialRenderer;
import com.kuronami.oldglassphotograph.client.view.PhotographViewer;
import com.kuronami.oldglassphotograph.network.OgpNet;
import com.kuronami.oldglassphotograph.network.ShutterOpenPayload;
import com.kuronami.oldglassphotograph.network.ViewfinderClosePayload;
import com.kuronami.oldglassphotograph.network.ViewfinderOpenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperties;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;

/**
 * Fabric client setup。dedicated server ではロードされないので client 専用コードを安全に参照できる。
 * 共通の処理は common の各クラスが持ち、ここでは Fabric 側の機構への配線だけを行う。
 */
public final class OldGlassPhotographFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // server → client の送信経路（覗き・シャッター・視点復帰）。
        OgpNet.setSendToServer(ClientPlayNetworking::send);

        // payload 受信。NeoForge の RegisterClientPayloadHandlersEvent と同じく main thread で走らせる。
        ClientPlayNetworking.registerGlobalReceiver(ViewfinderOpenPayload.TYPE, (payload, context) ->
                context.client().execute(() -> PhotoCaptureClient.openViewfinder(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ShutterOpenPayload.TYPE, (payload, context) ->
                context.client().execute(() -> PhotoCaptureClient.openShutter(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ViewfinderClosePayload.TYPE, (payload, context) ->
                context.client().execute(PhotoCaptureClient::closeViewfinder));

        // HUD レイヤ。HudElement は GuiGraphicsExtractor を受ける。
        // ファインダーの面はここに置けない（覗きの間は HUD ごと消えるため）。GuiViewfinderMixin が描く。
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "photograph_view"),
                PhotographViewer::render);

        // 每 tick。順序は NeoForge 版（板バー → 撮影 → 拡大面）と同じ。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            OgpClientCommon.trackPlateUseProgress();
            PhotoCaptureClient.endClientTick();
            PhotographViewer.endClientTick();
        });

        // 撮影点。NeoForge の RenderLevelStageEvent.AfterLevel 相当（レベル描画の終端＝HUD/GUI 合成前）。
        LevelRenderEvents.END_MAIN.register(context -> PhotoCaptureClient.onLevelRenderEnd());

        // 製図台 menu への写真よけ（client 側。ちらつき防止）。ScreenEvent.Init.Post 相当。
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) ->
                OgpClientCommon.applyMenuGuard(screen));

        // 写真をじっくり見る面（右クリック opener の結線）。
        PhotographViewer.init();

        // item model 系。client init はリソースリロードより前に走るので、
        // LateBoundIdMapper への直接 put はタイミング安全（mixin 不要）。
        SpecialModelRenderers.ID_MAPPER.put(PhotographSpecialRenderer.ID, PhotographSpecialRenderer.Unbaked.MAP_CODEC);
        SelectItemModelProperties.ID_MAPPER.put(PlateStageProperty.id(), PlateStageProperty.TYPE);
    }
}

package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.kuronami.oldglassphotograph.client.render.PhotographHandRenderer;
import com.kuronami.oldglassphotograph.client.render.PhotographSpecialRenderer;
import com.kuronami.oldglassphotograph.client.render.PlateHandRenderer;
import com.kuronami.oldglassphotograph.client.view.PhotographViewer;
import com.kuronami.oldglassphotograph.network.PhotoCaptureAbortPayload;
import com.kuronami.oldglassphotograph.network.ShutterOpenPayload;
import com.kuronami.oldglassphotograph.network.ViewfinderClosePayload;
import com.kuronami.oldglassphotograph.network.ViewfinderOpenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterSelectItemModelPropertyEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

/** client 側の入口（NeoForge 配線）。共通の処理は common の各クラスが持つ。 */
public final class OgpClient {

    private OgpClient() {
    }

    public static void init(IEventBus modBus) {
        // --- 撮影（payload 受信・HUD レイヤ・tick・レベル描画終端） ---
        modBus.addListener(OgpClient::registerClientPayloadHandlers);
        modBus.addListener(OgpClient::registerGuiLayers);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            OgpClientCommon.trackPlateUseProgress();
            PhotoCaptureClient.endClientTick();
            PhotographViewer.endClientTick();
        });
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterLevel.class,
                event -> PhotoCaptureClient.onLevelRenderEnd());
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFov.class, OgpClient::onComputeFov);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeCameraAngles.class, OgpClient::onCameraAngles);
        // 覗いている間は vanilla の攻撃／使用／ピックを止める。
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered.class, OgpClient::onInteract);

        // --- 写真をじっくり見る面 ---
        PhotographViewer.init();
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class, OgpClient::onScreenOpening);

        // --- item model 系の登録（mod bus） ---
        modBus.addListener((RegisterSelectItemModelPropertyEvent event) ->
                event.register(PlateStageProperty.id(), PlateStageProperty.TYPE));
        modBus.addListener(OgpClient::registerSpecialModelRenderers);

        // --- 一人称の手持ち差し替え。RenderHandEvent は game bus（NeoForge.EVENT_BUS）側。
        NeoForge.EVENT_BUS.addListener(PhotographHandEventHolder::onPhotographHand);
        NeoForge.EVENT_BUS.addListener(PhotographHandEventHolder::onPlateHand);

        // --- 製図台 menu への写真よけ（client 側。ちらつき防止） ---
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class,
                event -> OgpClientCommon.applyMenuGuard(event.getScreen()));
    }

    private static void registerClientPayloadHandlers(
            net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent event) {
        event.register(ViewfinderOpenPayload.TYPE, (payload, context) -> PhotoCaptureClient.openViewfinder(payload));
        event.register(ShutterOpenPayload.TYPE, (payload, context) -> PhotoCaptureClient.openShutter(payload));
        event.register(ViewfinderClosePayload.TYPE, (payload, context) -> PhotoCaptureClient.closeViewfinder());
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "viewfinder"),
                PhotoCaptureClient::renderViewfinder);
        event.registerAboveAll(
                Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "photograph_view"),
                PhotographViewer::render);
    }

    /** カメラ視点のあいだは写真用の固定 FOV を使う。プレイヤーの FOV 設定を継承させない。 */
    private static void onComputeFov(ViewportEvent.ComputeFov event) {
        float fov = PhotoCaptureClient.fovOverride();
        if (!Float.isNaN(fov)) {
            event.setFOV(fov);
        }
    }

    /**
     * 覗いている間の向き。<b>首振りの差分更新と枠の遅れは beforeCameraUpdate が担い</b>、
     * ここでは確定した角度をカメラへ渡すだけ。
     */
    private static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        PhotoCaptureClient.beforeCameraUpdate();
        if (!PhotoCaptureClient.isEngaged()) {
            return;
        }
        event.setYaw(PhotoCaptureClient.desiredYaw());
        event.setPitch(PhotoCaptureClient.desiredPitch());
        event.setRoll(0.0F);
    }

    /**
     * ファインダーに入っている間は vanilla の使用を殺す。
     *
     * <p>カメラ実体が Marker になっているあいだ、vanilla の使用ループは
     * <b>設置 Camera ではなくレンズの先</b>を pick して use を撃つ。シャッターのつもりの
     * クリックが「視界の先のブロックを right click する」ことになるので、入口で止める。
     * クリック自体は {@code PhotoCaptureClient#endClientTick} が使用キーの立ち上がりから直接拾う。
     */
    private static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (PhotoCaptureClient.shouldBlockInteractions()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /**
     * 画面が開こうとした時、写真の面を閉じる。ポーズ画面だけは開くのを止める。
     * 判定本体は {@link PhotographViewer#onScreenOpening}。
     */
    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (PhotographViewer.onScreenOpening(event.getNewScreen())) {
            event.setCanceled(true);
        }
    }

    private static void registerSpecialModelRenderers(RegisterSpecialModelRendererEvent event) {
        event.register(PhotographSpecialRenderer.ID, PhotographSpecialRenderer.Unbaked.MAP_CODEC);
    }

    /** RenderHandEvent の getter を共通 {@code trySubmit} へ渡すための殻。 */
    private static final class PhotographHandEventHolder {
        private static void onPhotographHand(RenderHandEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.player instanceof AbstractClientPlayer player)) {
                return;
            }
            if (PhotographHandRenderer.trySubmit(player, event.getPartialTick(), event.getInterpolatedPitch(),
                    event.getHand(), event.getSwingProgress(), event.getItemStack(), event.getEquipProgress(),
                    event.getPoseStack(), event.getSubmitNodeCollector(), event.getPackedLight())) {
                event.setCanceled(true);
            }
        }

        private static void onPlateHand(RenderHandEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.player instanceof AbstractClientPlayer player)) {
                return;
            }
            InteractionHand hand = event.getHand();
            if (PlateHandRenderer.trySubmit(player, event.getPartialTick(), event.getInterpolatedPitch(),
                    hand, event.getSwingProgress(), event.getItemStack(), event.getEquipProgress(),
                    event.getPoseStack(), event.getSubmitNodeCollector(), event.getPackedLight())) {
                event.setCanceled(true);
            }
        }
    }
}

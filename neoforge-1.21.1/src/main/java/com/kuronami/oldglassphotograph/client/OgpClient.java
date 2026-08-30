package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.kuronami.oldglassphotograph.client.render.PhotographHandRenderer;
import com.kuronami.oldglassphotograph.client.render.PhotographItemRenderer;
import com.kuronami.oldglassphotograph.client.render.PlateHandRenderer;
import com.kuronami.oldglassphotograph.client.view.PhotographViewer;
import com.kuronami.oldglassphotograph.network.PhotoCaptureAbortPayload;
import com.kuronami.oldglassphotograph.network.ShutterOpenPayload;
import com.kuronami.oldglassphotograph.network.ViewfinderClosePayload;
import com.kuronami.oldglassphotograph.network.ViewfinderOpenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** client 側の入口（NeoForge 配線）。共通の処理は common の各クラスが持つ。 */
public final class OgpClient {

    private OgpClient() {
    }

    public static void init(IEventBus modBus) {
        // --- 撮影（payload 受信・HUD レイヤ・tick・レベル描画終端） ---
        // 1.21.1 には S2C ハンドラを後から足す client 専用イベントが無いので、
        // payload 登録ごとここへ来る。この init は Dist.CLIENT 分岐の中でだけ呼ばれる
        // （OldGlassPhotographNeoForge 参照）ため、dedicated server でロードされない。
        modBus.addListener(OgpClient::registerPayloads);
        modBus.addListener(OgpClient::registerGuiLayers);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            OgpClientCommon.trackPlateUseProgress();
            PhotoCaptureClient.endClientTick();
            PhotographViewer.endClientTick();
        });
        // 26.x の RenderLevelStageEvent.AfterLevel ネスト型は 1.21.1 には無く、
        // Stage enum（AFTER_LEVEL）でフィルタする形。
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, event -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                PhotoCaptureClient.onLevelRenderEnd();
            }
        });
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFov.class, OgpClient::onComputeFov);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeCameraAngles.class, OgpClient::onCameraAngles);
        // 覗いている間は vanilla の攻撃／使用／ピックを止める。
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered.class, OgpClient::onInteract);

        // --- 写真をじっくり見る面 ---
        PhotographViewer.init();
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class, OgpClient::onScreenOpening);

        // --- item model 系の登録 ---
        // SelectItemModelProperty / SpecialModelRenderer 機構は 1.21.1 に無いため、
        // 板の段階モデルは ItemProperties（vanilla compass/clock 方式）、
        // 写真の面は IClientItemExtensions の BEWLR で描く。
        modBus.addListener(OgpClient::registerClientExtensions);
        PlateStageProperty.register();

        // --- 一人称の手持ち差し替え。RenderHandEvent は game bus（NeoForge.EVENT_BUS）側。
        NeoForge.EVENT_BUS.addListener(PhotographHandEventHolder::onPhotographHand);
        NeoForge.EVENT_BUS.addListener(PhotographHandEventHolder::onPlateHand);

        // --- 製図台 menu への写真よけ（client 側。ちらつき防止） ---
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class,
                event -> OgpClientCommon.applyMenuGuard(event.getScreen()));
    }

    /**
     * payload 登録。S2C（撮影の指示）は client ハンドラ付きで、ここで登録する。
     * S2C 以外（板の画素送り・中止）は common 側 {@code OgpRegistry.registerPayloads} が
     * 別の listener として同じイベントへ足す（同一イベントへの listener 追加は複数可）。
     */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(ViewfinderOpenPayload.TYPE, ViewfinderOpenPayload.CODEC,
                (payload, context) -> PhotoCaptureClient.openViewfinder(payload));
        registrar.playToClient(ShutterOpenPayload.TYPE, ShutterOpenPayload.CODEC,
                (payload, context) -> PhotoCaptureClient.openShutter(payload));
        registrar.playToClient(ViewfinderClosePayload.TYPE, ViewfinderClosePayload.CODEC,
                (payload, context) -> PhotoCaptureClient.closeViewfinder());
    }

    /** 写真アイテムの面を BEWLR へ差し替える（SpecialModelRenderer 機構の 1.21.1 相当）。 */
    private static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(PhotographItemRenderer.EXTENSIONS, com.kuronami.oldglassphotograph.OgpObjects.photograph());
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(OldGlassPhotograph.MODID, "viewfinder"),
                PhotoCaptureClient::renderViewfinder);
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(OldGlassPhotograph.MODID, "photograph_view"),
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
     * 判定本体はファインダー（{@link PhotoCaptureClient#onScreenOpening}）→
     * 写真の面（{@link PhotographViewer#onScreenOpening}）の順。
     */
    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (PhotoCaptureClient.onScreenOpening(event.getNewScreen())
                || PhotographViewer.onScreenOpening(event.getNewScreen())) {
            event.setCanceled(true);
        }
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
                    event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight())) {
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
                    event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight())) {
                event.setCanceled(true);
            }
        }
    }
}

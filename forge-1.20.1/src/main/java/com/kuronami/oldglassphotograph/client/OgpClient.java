package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.kuronami.oldglassphotograph.client.render.PhotographHandRenderer;
import com.kuronami.oldglassphotograph.client.view.PhotographViewer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * client 側の入口（Forge 配線）。共通の処理は common / client 共通クラスが持つ。
 *
 * <p>neoforge-1.21.1 セルの {@code OgpClient} と同じ役割を、1.20.1 Forge のイベント面で組んだもの
 * （この帯は NeoForge と違う旧 API 名を持つ。LOADERS.md 正本）:
 * HUD レイヤは {@code RegisterGuiOverlaysEvent.registerAboveAll}、tick は {@code TickEvent.ClientTickEvent}
 * の END フェーズ、撮影点は {@code RenderLevelStageEvent} の {@code Stage.AFTER_LEVEL}。
 */
public final class OgpClient {

    private OgpClient() {
    }

    public static void init(IEventBus modBus) {
        // --- この帯では hideGui を使わない（mod のオーバーレイごと消えるため）。
        //     ファインダーの暗幕が全面を覆うので、見た目上の役割は同じく果たされる。
        PhotoCaptureClient.disableVanillaHudSuppression();

        // --- 每 tick（板バー・撮影状態機械・拡大面）。26.x fabric セルと同じ同順委譲。
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            OgpClientCommon.trackPlateUseProgress();
            PhotoCaptureClient.endClientTick();
            PhotographViewer.endClientTick();
        });

        // --- レベル描画終端での撮影（GUI 合成前）。
        MinecraftForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                PhotoCaptureClient.onLevelRenderEnd();
            }
        });

        // --- カメラ視点の FOV と角度。
        MinecraftForge.EVENT_BUS.addListener(OgpClient::onComputeFov);
        MinecraftForge.EVENT_BUS.addListener(OgpClient::onCameraAngles);

        // --- 写真をじっくり見る面（opener 結線）。画面開始の扱いは ScreenEvent.Opening。
        PhotographViewer.init();
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Opening event) -> {
            if (PhotographViewer.onScreenOpening(event.getNewScreen())) {
                event.setCanceled(true);
            }
        });

        // --- 一人称の手持ち差し替え（写真の地図構え・板の左腕振り）。
        MinecraftForge.EVENT_BUS.addListener(OgpClient::onPhotographHand);
        MinecraftForge.EVENT_BUS.addListener(OgpClient::onPlateHand);

        // --- item model 系: 板の段階モデルは ItemProperties（vanilla compass/clock 方式）。
        PlateStageProperty.register();

        // --- mod bus 登録: HUD オーバーレイ。写真の面の BEWLR は PhotographItem#initializeClient。
        modBus.addListener(OgpClient::registerGuiOverlays);
    }

    /** HUD レイヤ（viewfinder / photograph_view）。vanilla の全オーバーレイより上。 */
    private static void registerGuiOverlays(net.minecraftforge.client.event.RegisterGuiOverlaysEvent event) {
        IGuiOverlay viewfinder = (gui, graphics, partialTick, width, height) ->
                PhotoCaptureClient.renderViewfinder(graphics, partialTick);
        IGuiOverlay photographView = (gui, graphics, partialTick, width, height) ->
                PhotographViewer.render(graphics, partialTick);
        // この帯の registerAboveAll は String id を受ける（RL 受けは後の世代）。
        event.registerAboveAll("old_glass_photograph_viewfinder", viewfinder);
        event.registerAboveAll("old_glass_photograph_photograph_view", photographView);
    }

    /** 写真アイテムの面の BEWLR 登録は {@code PhotographItem#initializeClient} が担う（この帯の方式）。 */

    /** カメラ視点のあいだは写真用の固定 FOV。プレイヤーの FOV 設定を継承させない。 */
    private static void onComputeFov(ViewportEvent.ComputeFov event) {
        float fov = PhotoCaptureClient.fovOverride();
        if (!Float.isNaN(fov)) {
            event.setFOV(fov);
        }
    }

    /**
     * 覗いている間の向き。<b>首振りの差分更新と枠の遅れは ComputeCameraAngles が担い</b>、
     * ここで確定した角度をカメラへ渡す（neoforge-1.21.1 セルと同じ配線）。
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

    /** RenderHandEvent の getter を共通 {@code trySubmit} へ渡すための殻（写真 → 板の順）。 */
    private static void onPhotographHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        // この帯では Minecraft.player は LocalPlayer（= AbstractClientPlayer の子）。instanceof 検査は不要。
        AbstractClientPlayer player = mc.player;
        if (player == null) {
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
        // この帯では Minecraft.player は LocalPlayer（= AbstractClientPlayer の子）。instanceof 検査は不要。
        AbstractClientPlayer player = mc.player;
        if (player == null) {
            return;
        }
        InteractionHand hand = event.getHand();
        if (com.kuronami.oldglassphotograph.client.render.PlateHandRenderer.trySubmit(player,
                event.getPartialTick(), event.getInterpolatedPitch(),
                hand, event.getSwingProgress(), event.getItemStack(), event.getEquipProgress(),
                event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight())) {
            event.setCanceled(true);
        }
    }
}

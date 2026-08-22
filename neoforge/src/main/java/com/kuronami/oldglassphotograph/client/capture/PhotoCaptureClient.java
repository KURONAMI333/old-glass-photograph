package com.kuronami.oldglassphotograph.client.capture;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.capture.PhotoCaptureController;
import com.kuronami.oldglassphotograph.capture.ViewfinderReading;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.network.PhotoCaptureAbortPayload;
import com.kuronami.oldglassphotograph.network.PhotoMapPixelsPayload;
import com.kuronami.oldglassphotograph.network.ShutterOpenPayload;
import com.kuronami.oldglassphotograph.network.ShutterRequestPayload;
import com.kuronami.oldglassphotograph.network.ViewfinderClosePayload;
import com.kuronami.oldglassphotograph.network.ViewfinderOpenPayload;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * client 側の撮影。型2（プレイヤーの描画カメラを設置 Camera へ一時的に移す）。
 *
 * <p><b>操作は 2 つのクリックで挟む</b>（{@code MODJAM_DECISIONS_OGP.md} §31）。
 * 1 回目でファインダーに入り、何秒でもそのまま構図と光を読む。2 回目でシャッターが開き、
 * 目標に達すると自動で閉じて視点が戻る。途中でもう一度クリックすればそこで閉じる。
 * <b>キーの押しっぱなしは撮影経路のどこにも無い。</b>
 *
 * <p><b>露光は 1 枚の撮影ではなく、窓のあいだの複数フレームの輝度平均。</b>
 * 実物の湿板写真で動体が消えるのは露光中の光を平均するからで、同じ原理をそのまま置いている。
 * 各フレームは撮った直後に 128x128 gray へ落としてから累積する（フル解像度で累積しない）。
 * 量子化は server 側の現像で 1 回だけ行う。
 *
 * <p>撮影点は RenderLevelStageEvent.AfterLevel。この時点の mainRenderTarget には
 * 手も HUD も GUI も入っていない（MODJAM_SPIKE_RESULT.md a 節）。
 */
public final class PhotoCaptureClient {

    private static final Logger LOG = LoggerFactory.getLogger("ogp");

    /** 写真の固定 FOV（垂直・度）。バニラの FOV スライダー既定値と同じ 70。 */
    public static final float PHOTO_FOV = 70.0F;

    /** カメラをブロック中心から視線方向へ押し出す距離（near plane 0.05 の内側に自分の面を入れない）。 */
    private static final double LENS_OFFSET = 0.6;

    /** takeScreenshot のコールバックが同フレームで走る保証は無いので必ず timeout を持つ。 */
    private static final int CALLBACK_TIMEOUT_TICKS = 200;

    /**
     * ファインダーに入ってからシャッターを開けられるようになるまで。
     *
     * <p>カメラ実体を移した直後は構図がまだ落ち着いていないので、その間に開いた光は捨てたい。
     * 早すぎるクリックは無視せず<b>覚えておいて</b>この tick で送るので、player からは見えない。
     */
    private static final int SHUTTER_READY_TICKS = 6;

    /** シャッター要求への返事が来ない時に、もう一度クリックできるようにするまでの tick。 */
    private static final int SHUTTER_REPLY_TIMEOUT_TICKS = 100;

    /**
     * 何があっても視点を戻す上限。<b>ファインダーで構えている間は数えない</b>
     * （何秒でも覗けるのが §31 の要件）。露光が始まってからの全経路を覆う。
     *
     * <p>カメラ実体のまま戻れなくなるのは、遊ぶ側から見て MOD が壊れたのと同じ。
     * 経路を増やすたびに個別の出口を数えるのではなく、無条件の出口を 1 本置く。
     */
    private static final int STUCK_GUARD_TICKS =
            PhotoCaptureController.MAX_EXPOSURE_TICKS + CALLBACK_TIMEOUT_TICKS + 40;

    private enum Phase { IDLE, PEEK, EXPOSING, WAITING }

    private static Phase phase = Phase.IDLE;

    // --- 待ちはすべて別フィールドで持つ（1 つを使い回すと必ず壊れる） ---
    private static int peekElapsed;
    private static int exposeElapsed;
    private static int waitLeft;
    private static int guardTicks;
    private static int shutterWait;

    /**
     * シャッターが閉じた後もキーが押されたままなら、離すまで使用キーを殺す。
     *
     * <p>2 回目のクリックで閉じた瞬間はキーが押されている。ここで解放すると
     * vanilla の使用ループ（{@code keyUse.isDown() && rightClickDelay == 0}）が
     * <b>同じ 1 回の押下でファインダーに入り直す</b>。
     */
    private static boolean awaitRelease;

    /** 前 tick の使用キーの状態。押下の立ち上がりだけをクリックとして拾う。 */
    private static boolean useDownLast;

    /**
     * ファインダーに入った時点で移動・スニークのキーが押されていた。
     *
     * <p>歩きながらカメラを右クリックすると入った直後に出てしまうので、
     * 一度離すまでは出口として数えない。
     */
    private static boolean exitKeysLatched;

    /** シャッター要求を送って返事を待っている。 */
    private static boolean shutterRequested;

    /** {@link #SHUTTER_READY_TICKS} より前に押されたクリック。落ち着いたら送る。 */
    private static boolean shutterQueued;

    private static int token;
    private static BlockPos basePos;
    private static BlockPos lensPos;
    private static float targetYaw;
    private static float targetPitch;
    private static int maxExposeTicks;
    private static int intervalTicks;

    /** 覗いている間に描く 1 行。撮れない状態ならその理由。 */
    private static @Nullable ViewfinderReading reading;

    private static @Nullable Entity marker;
    private static @Nullable Entity savedCamera;
    private static boolean hudWasHidden;

    // --- 累積 ---
    private static final int[] SUM = new int[LatentImage.SIZE];
    private static boolean captureDue;
    private static int framesDispatched;
    private static int framesCompleted;
    private static volatile byte @Nullable [] result;
    /** 露光ごとに増える。コールバックが前の露光のものかを見分ける。 */
    private static int sessionId;
    private static int resultFrames;
    private static int resultExposeTicks;

    private PhotoCaptureClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(PhotoCaptureClient::registerHandlers);
        modBus.addListener(PhotoCaptureClient::registerGuiLayers);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, PhotoCaptureClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterLevel.class, PhotoCaptureClient::onAfterLevel);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFov.class, PhotoCaptureClient::onComputeFov);
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered.class, PhotoCaptureClient::onInteract);
    }

    private static void registerHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(ViewfinderOpenPayload.TYPE, (payload, context) -> openViewfinder(payload));
        event.register(ShutterOpenPayload.TYPE, (payload, context) -> openShutter(payload));
        event.register(ViewfinderClosePayload.TYPE, (payload, context) -> closeViewfinder());
    }

    /**
     * 覗いている間の光の読みを描く 1 行。
     *
     * <p>vanilla の actionbar（{@code OVERLAY_MESSAGE} レイヤ）は HUD が隠れていると描かれないので、
     * ここに専用のレイヤを置く。{@code RegisterGuiLayersEvent} で足したレイヤは
     * vanilla のように {@code hudVisible} で包まれないため、HUD を隠したままでも描かれる。
     *
     * <p>この 1 行は写真に写り込まない。撮影は {@code RenderLevelStageEvent.AfterLevel}＝
     * GUI を合成する前の mainRenderTarget から読むため。
     */
    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "viewfinder_reading"),
                PhotoCaptureClient::renderReading);
    }

    private static void renderReading(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        ViewfinderReading current = reading;
        if (phase != Phase.PEEK || current == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Component line = current.line();
        // 位置と描き方は vanilla の actionbar（Hud.extractOverlayMessage）に合わせる。
        graphics.nextStratum();
        graphics.pose().pushMatrix();
        graphics.pose().translate(graphics.guiWidth() / 2.0F, graphics.guiHeight() - 68.0F);
        int width = font.width(line);
        graphics.textWithBackdrop(font, line, -width / 2, -4, width, 0xFFFFFFFF);
        graphics.pose().popMatrix();
    }

    /**
     * カメラ視点のあいだは写真用の固定 FOV を使う。プレイヤーの FOV 設定を継承させない。
     *
     * <p>覗いている間も同じ画角にする（覗いた構図と撮れる構図が違うとファインダーの意味が無い）。
     */
    private static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (phase != Phase.IDLE) {
            event.setFOV(PHOTO_FOV);
        }
    }

    /**
     * ファインダーに入っている間は vanilla の使用を殺す。
     *
     * <p>カメラ実体が Marker になっているあいだ、vanilla の使用ループは
     * <b>設置 Camera ではなくレンズの先</b>を pick して use を撃つ。シャッターのつもりの
     * クリックが「視界の先のブロックを right click する」ことになるので、入口で止める。
     * クリック自体は {@link #onClientTick} が使用キーの立ち上がりから直接拾う。
     */
    private static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (phase != Phase.IDLE || awaitRelease) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /** 1 回目のクリック。カメラ視点へ移り、光の読みを出す。ここではまだ何も撮らない。 */
    private static void openViewfinder(ViewfinderOpenPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (phase != Phase.IDLE) {
            return;
        }
        token = 0;
        basePos = payload.basePos();
        lensPos = payload.lensPos();
        targetYaw = payload.yaw();
        targetPitch = payload.pitch();
        reading = payload.reading();

        double dx = -Math.sin(Math.toRadians(targetYaw));
        double dz = Math.cos(Math.toRadians(targetYaw));
        Vec3 eye = Vec3.atCenterOf(lensPos).add(dx * LENS_OFFSET, 0.0, dz * LENS_OFFSET);

        Marker placed = new Marker(EntityTypes.MARKER, mc.level);
        placed.snapTo(eye.x, eye.y, eye.z, targetYaw, targetPitch);
        placed.setOldPosAndRot();
        marker = placed;

        savedCamera = mc.getCameraEntity();
        hudWasHidden = mc.gui.hud.isHidden();
        if (!hudWasHidden) {
            mc.gui.hud.toggle();
        }
        mc.setCameraEntity(placed);

        sessionId++;
        result = null;
        peekElapsed = 0;
        guardTicks = 0;
        shutterWait = 0;
        shutterRequested = false;
        shutterQueued = false;
        useDownLast = mc.options.keyUse.isDown();
        exitKeysLatched = exitKeyDown(mc);
        phase = Phase.PEEK;
    }

    /** 2 回目のクリックを server が通した。ここから光が溜まりはじめる。 */
    private static void openShutter(ShutterOpenPayload payload) {
        if (phase != Phase.PEEK) {
            // すでにファインダーから出た後に返事が届いた。session を放置すると
            // カメラが timeout まで「撮影中」のまま固まる。
            ClientPacketDistributor.sendToServer(new PhotoCaptureAbortPayload(
                    payload.token(), 0, 0, PhotoCaptureAbortPayload.REASON_LEFT));
            return;
        }
        token = payload.token();
        maxExposeTicks = Math.max(1, payload.window());
        intervalTicks = Math.max(1, payload.interval());
        sessionId++;
        java.util.Arrays.fill(SUM, 0);
        framesDispatched = 0;
        framesCompleted = 0;
        result = null;
        exposeElapsed = 0;
        guardTicks = 0;
        shutterRequested = false;
        shutterQueued = false;
        captureDue = true; // 露光の 1 枚目は窓の頭で撮る
        phase = Phase.EXPOSING;
    }

    /** server がシャッターを断った。視点を戻す（理由は actionbar で届く）。 */
    private static void closeViewfinder() {
        if (phase != Phase.PEEK) {
            return;
        }
        restore();
        phase = Phase.IDLE;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (awaitRelease && !mc.options.keyUse.isDown()) {
            awaitRelease = false;
        }
        if (phase == Phase.IDLE) {
            useDownLast = mc.options.keyUse.isDown();
            return;
        }
        if (stuckGuard()) {
            return;
        }
        boolean useDown = mc.options.keyUse.isDown();
        boolean clicked = useDown && !useDownLast;
        useDownLast = useDown;

        switch (phase) {
            // ファインダー。何秒でもここに居られる。撮るのはもう一度クリックした時だけ。
            case PEEK -> {
                peekElapsed++;
                if (exitPressed(mc)) {
                    restore();
                    phase = Phase.IDLE;
                    return;
                }
                if (clicked) {
                    shutterQueued = true;
                }
                if (shutterRequested && ++shutterWait > SHUTTER_REPLY_TIMEOUT_TICKS) {
                    // 返事が来ない。もう一度クリックできる状態へ戻す。
                    shutterRequested = false;
                    shutterWait = 0;
                }
                if (shutterQueued && !shutterRequested && peekElapsed >= SHUTTER_READY_TICKS) {
                    shutterQueued = false;
                    shutterRequested = true;
                    shutterWait = 0;
                    ClientPacketDistributor.sendToServer(new ShutterRequestPayload(basePos));
                }
            }
            // シャッターが開いている。窓が閉じるか、もう一度クリックするまで。
            case EXPOSING -> {
                exposeElapsed++;
                if (clicked || exposeElapsed >= maxExposeTicks) {
                    if (framesDispatched < PhotoCaptureController.MIN_EXPOSURE_FRAMES) {
                        abort();
                    } else {
                        finishExposure();
                    }
                } else if (exposeElapsed % intervalTicks == 0) {
                    captureDue = true;
                }
            }
            case WAITING -> {
                byte[] pixels = result;
                if (pixels != null) {
                    restore();
                    ClientPacketDistributor.sendToServer(
                            new PhotoMapPixelsPayload(token, resultExposeTicks, resultFrames, pixels));
                    LOG.debug("[ogp] sent avg of {} frames, {} ticks (token {})",
                            resultFrames, resultExposeTicks, token);
                    result = null;
                    phase = Phase.IDLE;
                } else if (--waitLeft <= 0) {
                    LOG.error("[ogp] TIMEOUT waiting for capture callbacks ({}/{}); restoring camera",
                            framesCompleted, framesDispatched);
                    restore();
                    phase = Phase.IDLE;
                }
            }
            default -> {
            }
        }
    }

    /** 移動・スニークのキーが押されているか。 */
    private static boolean exitKeyDown(Minecraft mc) {
        return mc.options.keyShift.isDown()
                || mc.options.keyUp.isDown()
                || mc.options.keyDown.isDown()
                || mc.options.keyLeft.isDown()
                || mc.options.keyRight.isDown()
                || mc.options.keyJump.isDown();
    }

    /** スニークか移動で、撮らずにファインダーから出る（{@code MODJAM_DECISIONS_OGP.md} §31）。 */
    private static boolean exitPressed(Minecraft mc) {
        if (!exitKeyDown(mc)) {
            exitKeysLatched = false;
            return false;
        }
        return !exitKeysLatched;
    }

    /**
     * 無条件の出口。どの経路で来ても、カメラ実体のまま戻れなくなることは無い。
     *
     * <p>ファインダーで構えている間は「止まっている」わけではないので数えない
     * （出口はクリック・スニーク・移動の 3 つ）。
     *
     * @return 強制的に戻したか
     */
    private static boolean stuckGuard() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.player.isAlive()) {
            LOG.warn("[ogp] viewfinder aborted: level/player gone (phase={})", phase);
            forceRestore();
            return true;
        }
        if (phase == Phase.PEEK) {
            guardTicks = 0;
            return false;
        }
        if (++guardTicks > STUCK_GUARD_TICKS) {
            LOG.error("[ogp] STUCK in {} for {} ticks; forcing camera back", phase, guardTicks);
            forceRestore();
            return true;
        }
        return false;
    }

    private static void forceRestore() {
        if (token != 0 && phase != Phase.WAITING) {
            ClientPacketDistributor.sendToServer(new PhotoCaptureAbortPayload(
                    token, exposeElapsed, framesDispatched, PhotoCaptureAbortPayload.REASON_LEFT));
        }
        restore();
        phase = Phase.IDLE;
        result = null;
        sessionId++;
    }

    /**
     * 露光を中止する。server の session を解放して、プレートには何も書かせない。
     * シャッターが開いた直後に閉じても板を失わせないための経路。
     */
    private static void abort() {
        int ticks = exposeElapsed;
        int frames = framesDispatched;
        restore();
        phase = Phase.IDLE;
        result = null;
        sessionId++;
        ClientPacketDistributor.sendToServer(new PhotoCaptureAbortPayload(
                token, ticks, frames, PhotoCaptureAbortPayload.REASON_TOO_SHORT));
        LOG.debug("[ogp] exposure closed with {} frames (below {}); plate untouched",
                frames, PhotoCaptureController.MIN_EXPOSURE_FRAMES);
    }

    /** 露光窓を閉じる。累積を平均へ落とし、コールバックの回収へ移る。 */
    private static void finishExposure() {
        resultExposeTicks = exposeElapsed;
        phase = Phase.WAITING;
        waitLeft = CALLBACK_TIMEOUT_TICKS;
        tryFinalize();
    }

    /** コールバックが全部戻っていれば平均像を作る。 */
    private static void tryFinalize() {
        if (phase != Phase.WAITING || framesCompleted < framesDispatched) {
            return;
        }
        int frames = Math.max(1, framesCompleted);
        byte[] out = new byte[LatentImage.SIZE];
        for (int i = 0; i < LatentImage.SIZE; i++) {
            out[i] = (byte) Math.clamp(SUM[i] / frames, 0, 255);
        }
        resultFrames = framesCompleted;
        result = out;
    }

    private static void restore() {
        Minecraft mc = Minecraft.getInstance();
        // 視点が戻った時点でまだ押しっぱなしなら、離すまで使用キーを殺す
        // （同じ 1 回の押下でファインダーへ入り直すのを防ぐ）。
        awaitRelease = mc.options.keyUse.isDown();
        useDownLast = awaitRelease;
        mc.setCameraEntity(savedCamera != null ? savedCamera : mc.player);
        if (!hudWasHidden && mc.gui.hud.isHidden()) {
            mc.gui.hud.toggle();
        }
        marker = null;
        savedCamera = null;
        reading = null;
    }

    private static void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        if (phase != Phase.EXPOSING || !captureDue) {
            return;
        }
        captureDue = false;
        Minecraft mc = Minecraft.getInstance();
        final int idx = framesDispatched++;
        final int session = sessionId;
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), img -> {
            // ここは同フレームでは走らない（実測: dispatch の約 1 フレーム後）。
            if (session != sessionId) {
                // 中止した露光の遅れて届いたコールバック。次の露光の累積を汚さない。
                img.close();
                return;
            }
            try {
                accumulate(img);
            } catch (Throwable t) {
                LOG.error("[ogp] capture accumulation failed on frame {}", idx, t);
            } finally {
                img.close();
            }
            framesCompleted++;
            tryFinalize();
        });
    }

    /** 生フレーム -&gt; 中央正方形クロップ -&gt; 128x128 -&gt; 8bit gray を SUM へ加算。 */
    private static void accumulate(NativeImage img) throws Exception {
        int w = img.getWidth();
        int h = img.getHeight();
        int side = Math.min(w, h);
        try (NativeImage small = new NativeImage(128, 128, false)) {
            img.resizeSubRectTo((w - side) / 2, (h - side) / 2, side, side, small);
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    int argb = small.getPixel(x, y);
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    SUM[x + y * 128] += (r * 299 + g * 587 + b * 114) / 1000;
                }
            }
        }
    }
}

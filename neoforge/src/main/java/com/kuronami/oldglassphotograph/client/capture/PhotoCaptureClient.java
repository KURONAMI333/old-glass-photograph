package com.kuronami.oldglassphotograph.client.capture;

import com.kuronami.oldglassphotograph.capture.PhotoCaptureController;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.network.PhotoCaptureAbortPayload;
import com.kuronami.oldglassphotograph.network.PhotoCaptureRequestPayload;
import com.kuronami.oldglassphotograph.network.PhotoMapPixelsPayload;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * client 側の露光。型2（プレイヤーの描画カメラを設置 Camera へ一時的に移す）。
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
     * 押し始めてから露光が始まるまで（＝ファインダーだけの時間）。
     *
     * <p>ここまでに離せば「覗いただけ」で、板も薬品も消費しない。撮る前に構図と
     * <b>いま何が動いているか</b>を見るための時間（{@code MODJAM_DECISIONS_OGP.md} §2 Fun 案1）。
     * カメラ実体を移した直後の補間が落ち着く時間（従来の settle 12 tick）も内側に含む。
     */
    private static final int PEEK_ARM_TICKS = 20;

    /** 覗くだけで離した後、視点を戻すまでの余韻。短すぎると一瞬すぎて構図が読めない。 */
    private static final int PEEK_TAIL_TICKS = 40;

    /**
     * 何があっても視点を戻す上限。露光の全経路（arm + 露光 80 + コールバック待ち 200）を覆う。
     *
     * <p>カメラ実体のまま戻れなくなるのは、遊ぶ側から見て MOD が壊れたのと同じ。
     * 経路を増やすたびに個別の出口を数えるのではなく、無条件の出口を 1 本置く。
     */
    private static final int STUCK_GUARD_TICKS = PEEK_ARM_TICKS + PEEK_TAIL_TICKS
            + PhotoCaptureController.MAX_EXPOSURE_TICKS + CALLBACK_TIMEOUT_TICKS + 40;

    private enum Phase { IDLE, PEEK, TAIL, SETTLING, EXPOSING, WAITING }

    private static Phase phase = Phase.IDLE;

    // --- 待ちはすべて別フィールドで持つ（1 つを使い回すと必ず壊れる） ---
    private static int settleLeft;
    private static int peekElapsed;
    private static int tailLeft;
    private static int exposeElapsed;
    private static int waitLeft;
    private static int guardTicks;

    /** 露光が始められる session か（server が板を検査した結果）。false なら覗くだけ。 */
    private static boolean armed;

    /**
     * 露光が閉じた後もキーが押されたままなら、離すまで使用キーを殺す。
     *
     * <p>{@code MODJAM_DECISIONS_OGP.md} §9「それ以降は押し続けても何も起きない」。
     * これが無いと、押しっぱなしのまま vanilla の使用ループが 4 tick ごとに再発火して
     * 2 回目の露光要求（＝二重露光ガードのメッセージ連打）になる。
     */
    private static boolean awaitRelease;

    private static int token;
    private static BlockPos target;
    private static float targetYaw;
    private static float targetPitch;
    private static int maxExposeTicks;
    private static int intervalTicks;

    private static Entity marker;
    private static Entity savedCamera;
    private static boolean hudWasHidden;

    // --- 累積 ---
    private static final int[] SUM = new int[LatentImage.SIZE];
    private static boolean captureDue;
    private static int framesDispatched;
    private static int framesCompleted;
    private static long captureNanos;
    private static long accumulateNanos;
    private static boolean callbackRanSinceLastFrame;
    private static long captureFrameNanoSum;
    private static int captureFrameCount;
    private static volatile byte[] result;
    /** 露光ごとに増える。コールバックが前の露光のものかを見分ける。 */
    private static int sessionId;
    private static int resultFrames;
    private static int resultExposeTicks;

    // --- フレーム時間の計測（コスト測定用） ---
    private static final int RING = 240;
    private static final long[] FRAME_NANOS = new long[RING];
    private static int ringIndex;
    private static long lastFrameNano;
    private static double baselineMeanMs;
    private static double baselineMaxMs;
    private static long exposeFrameNanoSum;
    private static long exposeFrameNanoMax;
    private static int exposeFrameCount;

    private PhotoCaptureClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(PhotoCaptureClient::registerHandlers);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, PhotoCaptureClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterLevel.class, PhotoCaptureClient::onAfterLevel);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFov.class, PhotoCaptureClient::onComputeFov);
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered.class, PhotoCaptureClient::onInteract);
    }

    private static void registerHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(PhotoCaptureRequestPayload.TYPE, (payload, context) -> begin(payload));
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
     * 露光中は使用キーの再発火を殺す。
     *
     * <p>カメラ実体が Marker になっているあいだ、vanilla の使用ループは
     * <b>設置 Camera ではなくレンズの先</b>を pick して 4 tick ごとに use を撃つ。
     * 押しっぱなしで露光を伸ばす操作が、そのまま「視界の先のブロックを right click し続ける」
     * ことになるので、露光中は入口で止める。
     */
    private static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (phase != Phase.IDLE || awaitRelease) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    private static void begin(PhotoCaptureRequestPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (phase != Phase.IDLE) {
            LOG.warn("[ogp] viewfinder already active ({}), releasing token {}", phase, payload.token());
            // server が予約した session を放置すると、カメラが 20 秒間「撮影中」のまま固まる。
            if (payload.token() != 0) {
                ClientPacketDistributor.sendToServer(new PhotoCaptureAbortPayload(
                        payload.token(), 0, 0, PhotoCaptureAbortPayload.REASON_PEEK));
            }
            return;
        }
        token = payload.token();
        armed = payload.token() != 0;
        target = payload.pos();
        targetYaw = payload.yaw();
        targetPitch = payload.pitch();
        maxExposeTicks = Math.max(1, payload.maxExposeTicks());
        intervalTicks = Math.max(1, payload.intervalTicks());

        double dx = -Math.sin(Math.toRadians(targetYaw));
        double dz = Math.cos(Math.toRadians(targetYaw));
        Vec3 eye = Vec3.atCenterOf(target).add(dx * LENS_OFFSET, 0.0, dz * LENS_OFFSET);

        marker = new Marker(EntityTypes.MARKER, mc.level);
        marker.snapTo(eye.x, eye.y, eye.z, targetYaw, targetPitch);
        marker.setOldPosAndRot();

        savedCamera = mc.getCameraEntity();
        hudWasHidden = mc.gui.hud.isHidden();
        if (!hudWasHidden) {
            mc.gui.hud.toggle();
        }
        mc.setCameraEntity(marker);

        sessionId++;
        java.util.Arrays.fill(SUM, 0);
        framesDispatched = 0;
        framesCompleted = 0;
        captureNanos = 0L;
        accumulateNanos = 0L;
        captureFrameNanoSum = 0L;
        captureFrameCount = 0;
        callbackRanSinceLastFrame = false;
        captureDue = false;
        result = null;
        exposeElapsed = 0;
        peekElapsed = 0;
        tailLeft = 0;
        guardTicks = 0;
        settleLeft = payload.settleTicks();

        exposeFrameNanoSum = 0L;
        exposeFrameNanoMax = 0L;
        exposeFrameCount = 0;
        snapshotBaseline();

        phase = Phase.PEEK;
        LOG.info("[ogp][expose] viewfinder token={} armed={} pos={} yaw={} settle={} max={} interval={}",
                token, armed, target, targetYaw, settleLeft, maxExposeTicks, intervalTicks);
    }

    private static Phase startExposure() {
        exposeElapsed = 0;
        captureDue = true; // 露光の 1 枚目は窓の頭で撮る
        return Phase.EXPOSING;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (awaitRelease && !Minecraft.getInstance().options.keyUse.isDown()) {
            awaitRelease = false;
        }
        if (phase != Phase.IDLE && stuckGuard()) {
            return;
        }
        switch (phase) {
            // ファインダー。まだ 1 枚も撮っていない。ここで離せば「覗いただけ」。
            case PEEK -> {
                peekElapsed++;
                if (released()) {
                    endPeek();
                } else if (peekElapsed >= Math.max(PEEK_ARM_TICKS, settleLeft) && armed) {
                    phase = startExposure();
                }
            }
            case TAIL -> {
                if (--tailLeft <= 0) {
                    restore();
                    phase = Phase.IDLE;
                }
            }
            case SETTLING -> {
                // 静定中に離した = そもそも 1 枚も撮れていない。中止（プレートは消費しない）。
                if (released()) {
                    abort();
                } else if (--settleLeft <= 0) {
                    phase = startExposure();
                }
            }
            case EXPOSING -> {
                exposeElapsed++;
                if (exposureShouldEnd()) {
                    // 規定枚数に満たないうちに離したら「失敗」ではなく「中止」。
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
                    LOG.info("[ogp][expose] sent avg of {} frames, {} ticks, {} bytes (token {})",
                            resultFrames, resultExposeTicks, pixels.length, token);
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

    /**
     * 覗くだけで終わった。板には一切触らせない（server の session を解放するだけ）。
     * 視点は少し残してから戻す（一瞬で戻ると構図が読めない）。
     */
    private static void endPeek() {
        if (token != 0) {
            ClientPacketDistributor.sendToServer(new PhotoCaptureAbortPayload(
                    token, 0, 0, PhotoCaptureAbortPayload.REASON_PEEK));
        }
        LOG.info("[ogp][expose] viewfinder only, no exposure (token={} ticks={})", token, peekElapsed);
        tailLeft = PEEK_TAIL_TICKS;
        phase = Phase.TAIL;
    }

    /**
     * 無条件の出口。どの経路で来ても、カメラ実体のまま戻れなくなることは無い。
     *
     * <p>押しっぱなしでファインダーを覗いている間は「止まっている」わけではないので数えない。
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
        if (phase == Phase.PEEK && !released()) {
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
                    token, exposeElapsed, framesDispatched, PhotoCaptureAbortPayload.REASON_PEEK));
        }
        restore();
        phase = Phase.IDLE;
        result = null;
        sessionId++;
    }

    /** 露光窓を閉じるか。{@code maxExposeTicks} に達したら押しっぱなしでも打ち切る。 */
    private static boolean exposureShouldEnd() {
        if (exposeElapsed >= maxExposeTicks) {
            return true;
        }
        return released();
    }

    /** 使用キーが離れているか。 */
    private static boolean released() {
        return !Minecraft.getInstance().options.keyUse.isDown();
    }

    /**
     * 露光を中止する。server の session を解放して、プレートには何も書かせない。
     * 押し間違い・気が変わったで板を失わせないための経路。
     */
    private static void abort() {
        int ticks = exposeElapsed;
        int frames = framesDispatched;
        restore();
        phase = Phase.IDLE;
        result = null;
        ClientPacketDistributor.sendToServer(new PhotoCaptureAbortPayload(
                token, ticks, frames, PhotoCaptureAbortPayload.REASON_TOO_SHORT));
        LOG.info("[ogp][expose] aborted token={} ticks={} frames={} (below {} frames)",
                token, ticks, frames, PhotoCaptureController.MIN_EXPOSURE_FRAMES);
    }

    /** 露光窓を閉じる。累積を平均へ落とし、コールバックの回収へ移る。 */
    private static void finishExposure() {
        resultExposeTicks = exposeElapsed;
        phase = Phase.WAITING;
        waitLeft = CALLBACK_TIMEOUT_TICKS;
        double meanMs = exposeFrameCount == 0 ? 0.0 : exposeFrameNanoSum / (double) exposeFrameCount / 1.0e6;
        LOG.info("[ogp][cost] exposure closed: ticks={} frames={} dispatchMsPerCapture={} "
                        + "callbackMsPerCapture={} frameMs mean={} max={} | baseline mean={} max={}",
                exposeElapsed, framesDispatched,
                fmt(framesDispatched == 0 ? 0.0 : captureNanos / 1.0e6 / framesDispatched),
                fmt(framesDispatched == 0 ? 0.0 : accumulateNanos / 1.0e6 / framesDispatched),
                fmt(meanMs), fmt(exposeFrameNanoMax / 1.0e6), fmt(baselineMeanMs), fmt(baselineMaxMs));
        tryFinalize();
    }

    /** コールバックが全部戻っていれば平均像を作る。 */
    private static void tryFinalize() {
        if (phase != Phase.WAITING || framesCompleted < framesDispatched) {
            return;
        }
        int frames = Math.max(1, framesCompleted);
        byte[] out = new byte[LatentImage.SIZE];
        long total = 0L;
        for (int i = 0; i < LatentImage.SIZE; i++) {
            int v = SUM[i] / frames;
            out[i] = (byte) Math.clamp(v, 0, 255);
            total += v;
        }
        resultFrames = framesCompleted;
        result = out;
        LOG.info("[ogp][cost] frameMs on readback frames: mean={} n={} | other frames: mean={} n={}",
                fmt(captureFrameCount == 0 ? 0.0 : captureFrameNanoSum / (double) captureFrameCount / 1.0e6),
                captureFrameCount,
                fmt(exposeFrameCount == captureFrameCount ? 0.0
                        : (exposeFrameNanoSum - captureFrameNanoSum)
                                / (double) (exposeFrameCount - captureFrameCount) / 1.0e6),
                exposeFrameCount - captureFrameCount);
        LOG.info("[ogp][expose] averaged {} frames, mean luma={}", framesCompleted,
                fmt(total / (double) LatentImage.SIZE));
    }

    private static void restore() {
        Minecraft mc = Minecraft.getInstance();
        // 視点が戻った時点でまだ押しっぱなしなら、離すまで使用キーを殺す。
        // 「80 tick で終わり、それ以降は押し続けても何も起きない」（DECISIONS §9）の実体。
        awaitRelease = mc.options.keyUse.isDown();
        mc.setCameraEntity(savedCamera != null ? savedCamera : mc.player);
        if (!hudWasHidden && mc.gui.hud.isHidden()) {
            mc.gui.hud.toggle();
        }
        marker = null;
        savedCamera = null;
        LOG.info("[ogp] restored: camera={} hudHidden={}", mc.getCameraEntity(), mc.gui.hud.isHidden());
    }

    private static void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        recordFrame();
        if (phase != Phase.EXPOSING || !captureDue) {
            return;
        }
        captureDue = false;
        Minecraft mc = Minecraft.getInstance();
        final int idx = framesDispatched++;
        final int session = sessionId;
        long t0 = System.nanoTime();
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), img -> {
            // ここは同フレームでは走らない（実測: dispatch の約 1 フレーム後）。
            // したがって dispatch の所要と callback 本体の所要は別に測る。
            long c0 = System.nanoTime();
            if (session != sessionId) {
                // 中止した露光の遅れて届いたコールバック。次の露光の累積を汚さない。
                img.close();
                return;
            }
            try {
                accumulate(img, idx);
            } catch (Throwable t) {
                LOG.error("[ogp] capture accumulation failed", t);
            } finally {
                img.close();
            }
            accumulateNanos += System.nanoTime() - c0;
            callbackRanSinceLastFrame = true;
            framesCompleted++;
            tryFinalize();
        });
        captureNanos += System.nanoTime() - t0;
    }

    /** フレーム間隔を常に記録しておく（露光していない間が baseline になる）。 */
    private static void recordFrame() {
        long now = System.nanoTime();
        if (lastFrameNano != 0L) {
            long delta = now - lastFrameNano;
            FRAME_NANOS[ringIndex] = delta;
            ringIndex = (ringIndex + 1) % RING;
            if (phase == Phase.EXPOSING || phase == Phase.WAITING) {
                exposeFrameNanoSum += delta;
                exposeFrameCount++;
                exposeFrameNanoMax = Math.max(exposeFrameNanoMax, delta);
                if (callbackRanSinceLastFrame) {
                    captureFrameNanoSum += delta;
                    captureFrameCount++;
                }
            }
            callbackRanSinceLastFrame = false;
        }
        lastFrameNano = now;
    }

    private static void snapshotBaseline() {
        long sum = 0L;
        long max = 0L;
        int n = 0;
        for (long v : FRAME_NANOS) {
            if (v > 0L) {
                sum += v;
                max = Math.max(max, v);
                n++;
            }
        }
        baselineMeanMs = n == 0 ? 0.0 : sum / (double) n / 1.0e6;
        baselineMaxMs = max / 1.0e6;
    }

    /** 生フレーム -> 中央正方形クロップ -> 128x128 -> 8bit gray を SUM へ加算。 */
    private static void accumulate(NativeImage img, int idx) throws Exception {
        int w = img.getWidth();
        int h = img.getHeight();
        int side = Math.min(w, h);
        long frameSum = 0L;
        try (NativeImage small = new NativeImage(128, 128, false)) {
            img.resizeSubRectTo((w - side) / 2, (h - side) / 2, side, side, small);
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    int argb = small.getPixel(x, y);
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int gray = (r * 299 + g * 587 + b * 114) / 1000;
                    SUM[x + y * 128] += gray;
                    frameSum += gray;
                }
            }
        }
        // 各フレームの平均輝度。全フレームが同一なら readback が使い回されている疑い。
        LOG.info("[ogp][frame] idx={} meanLuma={}", idx, fmt(frameSum / (double) LatentImage.SIZE));
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}

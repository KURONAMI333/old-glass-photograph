package com.kuronami.oldglassphotograph.client.capture;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.capture.PhotoCaptureController;
import com.kuronami.oldglassphotograph.capture.ViewfinderGeometry;
import com.kuronami.oldglassphotograph.capture.ViewfinderReading;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.network.OgpNet;
import com.kuronami.oldglassphotograph.network.PhotoCaptureAbortPayload;
import com.kuronami.oldglassphotograph.network.PhotoMapPixelsPayload;
import com.kuronami.oldglassphotograph.network.ShutterOpenPayload;
import com.kuronami.oldglassphotograph.network.ShutterRequestPayload;
import com.kuronami.oldglassphotograph.network.ViewfinderOpenPayload;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * client 側の撮影。型2（プレイヤーの描画カメラを設置 Camera へ一時的に移す）。
 *
 * <p><b>操作は 2 つのクリックで挟む</b>（§31）。1 回目でファインダーに入り、何秒でもそのまま
 * 構図と光を読む。2 回目でシャッターが開き、目標に達すると自動で閉じて視点が戻る。
 *
 * <p><b>露光は 1 枚の撮影ではなく、窓のあいだの複数フレームの輝度平均。</b>
 * 実物の湿板写真で動体が消えるのは露光中の光を平均するからで、同じ原理をそのまま置いている。
 *
 * <p>撮影点はレンダーのレベル描画の終端。この時点の mainRenderTarget には
 * 手も HUD も GUI も入っていない。{@code Screenshot#takeScreenshot(RenderTarget)} は
 * 同期版（1.20.1 jar 実測・RESOLUTION #1）で、呼び出した時点でコピー完了。
 *
 * <p><b>ローダー配線との境界</b>: このクラスはローダー型を 1 つも import しない。
 * ローダー側（Fabric: HudRenderCallback / WorldRenderEvents.END / mixin 類）が
 * payload 受信・tick・レベル描画終端・カメラ・入力抑止をここへ委譲する。
 */
public final class PhotoCaptureClient {

    private static final Logger LOG = LoggerFactory.getLogger("ogp");

    /** 写真の固定 FOV（垂直・度）。バニラの FOV スライダー既定値と同じ 70。 */
    public static final float PHOTO_FOV = 70.0F;

    /** カメラをブロック中心から視線方向へ押し出す距離。 */
    private static final double LENS_OFFSET = 0.6;

    /** takeScreenshot のコールバックが同フレームで走る保証は無いので必ず timeout を持つ。 */
    private static final int CALLBACK_TIMEOUT_TICKS = 200;

    /** ファインダーに入ってからシャッターを開けられるようになるまで。 */
    private static final int SHUTTER_READY_TICKS = 6;

    /** シャッター要求への返事が来ない時に、もう一度クリックできるようにするまでの tick。 */
    private static final int SHUTTER_REPLY_TIMEOUT_TICKS = 100;

    /** 覗いている間に振れる左右の角度。三脚の雲台の可動域にあたる。 */
    private static final float YAW_LIMIT = 70.0F;

    /** 同じく上下。 */
    private static final float PITCH_LIMIT = 45.0F;

    /** 枠がマウスの回転に一拍遅れて追う量の上限（GUI px）。 */
    private static final float FRAME_DRIFT_MAX = 5.0F;

    /** 1 度あたり何 GUI px ずらすか（実画面の px を一定にするので guiScale で割る）。 */
    private static final float FRAME_DRIFT_GAIN = 3.0F;

    /** 遅れの追従。1 に近いほど即応（＝遅れが消える）。 */
    private static final float FRAME_DRIFT_FOLLOW = 0.45F;

    /** 光の読みを出しておく長さ（tick）。構図の邪魔になるので出しっぱなしにしない。 */
    private static final int READING_HOLD_TICKS = 60;

    /** 上の後に薄れて消えるまでの長さ（tick）。 */
    private static final int READING_FADE_TICKS = 20;

    /**
     * 露光中に時計が刻む間隔（tick）。<b>1 秒固定で、露光の長さに一切依存しない。</b>
     * 拍から読めるのは「時間が進んでいる」だけ。
     */
    private static final int TICK_INTERVAL = 20;

    /** 時計の音。手元の小さな音なので通さない。 */
    private static final float TICK_VOLUME = 0.28F;

    /** 露光中だけ枠が呼吸する量（GUI px）。 */
    private static final float BREATH_AMPLITUDE = 3.0F;

    /** 呼吸の周期（tick）。割り切れない値にする。 */
    private static final float BREATH_PERIOD = 47.0F;

    /** シャッターが開いた瞬間、開口を塞いだままにする tick（キャップが横切る）。 */
    private static final int OPEN_FLASH_TICKS = 2;

    /** 露光が満ちた後、視点を戻す前に開口を塞いだままにする tick。 */
    private static final int CLOSE_HOLD_TICKS = 5;

    /** 暗幕。開口の外はここで塗り潰す。 */
    private static final int CLOTH_COLOR = 0xFF0B0908;

    /** レンズキャップ。開口を塞ぐ。 */
    private static final int CAP_COLOR = 0xFF070605;

    /** 読みの行の背景（26.x の textWithBackdrop 相当の代用色）。 */
    private static final int LINE_BACKDROP_COLOR = 0x90505050;

    /**
     * すりガラスの面・木の枠・四隅の落ち。開口の上にそのまま伸ばして貼る。
     */
    private static final ResourceLocation VIEWFINDER_TEXTURE = new ResourceLocation(
            OldGlassPhotograph.MODID, "textures/gui/viewfinder.png");

    /** viewfinder.png の実寸。blit にテクスチャ寸法を渡すのに要る。 */
    private static final int VIEWFINDER_TEX_SIZE = 512;

    /** 何があっても視点を戻す上限。<b>ファインダーで構えている間は数えない</b>。 */
    private static final int STUCK_GUARD_TICKS =
            PhotoCaptureController.MAX_EXPOSURE_TICKS + CALLBACK_TIMEOUT_TICKS + 40;

    private enum Phase { IDLE, PEEK, EXPOSING, WAITING }

    private static Phase phase = Phase.IDLE;

    /**
     * vanilla HUD を {@code options.hideGui} で隠すか。この帯では<b>両ローダーとも false にして使わない</b>
     * （hideGui が mod の HUD レイヤごと消すため。fabric の HudRenderCallback も Forge のオーバーレイも、
     * Gui.render 自体が hideGui でスキップされる）。ファインダーの暗幕が全面を覆うので、
     * 「HUD が見えない」見た目は同じく成立する。26.x / 1.21.x のレイヤ機構との差分。
     */
    private static boolean suppressVanillaHud = true;

    /** client 初期化から呼ぶ（この帯では必ず呼ぶ＝hideGui 経路を無効化する）。 */
    public static void disableVanillaHudSuppression() {
        suppressVanillaHud = false;
    }

    // --- 待ちはすべて別フィールドで持つ（1 つを使い回すと必ず壊れる） ---
    private static int peekElapsed;
    private static int exposeElapsed;
    private static int waitLeft;
    private static int guardTicks;
    private static int shutterWait;
    private static int openFlash;
    private static int closeHold;

    /** シャッターが閉じた後もキーが押されたままなら、離すまで使用キーを殺す。 */
    private static boolean awaitRelease;

    /** 前 tick の使用キーの状態。押下の立ち上がりだけをクリックとして拾う。 */
    private static boolean useDownLast;

    /** ファインダーに入った時点で移動・スニークのキーが押されていた。 */
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

    // --- 首振り。PEEK の間だけ動き、シャッターを開けた時点の値が写真になる ---

    /** 設置向きからの左右のずれ。<b>CameraMixin の PEEK 分岐だけが書く。</b> */
    private static float yawOffset;

    /** 同じく上下。 */
    private static float pitchOffset;

    /** シャッターを開けた時点の {@link #yawOffset}。露光中と現像はこれを使う。 */
    private static float shotYawOffset;

    /** シャッターを開けた時点の {@link #pitchOffset}。 */
    private static float shotPitchOffset;

    /** 前フレームの player の向き。差分だけを首振りに足す。 */
    private static float lastPlayerYaw;
    private static float lastPlayerPitch;

    /** 枠の遅れ（GUI px）。 */
    private static float frameDriftX;
    private static float frameDriftY;

    /** 覗いている間に描く 1 行。撮れない状態ならその理由。 */
    private static @Nullable ViewfinderReading reading;

    private static @Nullable Entity marker;
    private static @Nullable Entity savedCamera;
    private static boolean hudWasHidden;
    private static @Nullable CameraType savedCameraType;
    private static float enterPlayerYaw;
    private static float enterPlayerPitch;

    // --- 累積 ---
    private static final int[] SUM = new int[LatentImage.SIZE];
    private static boolean captureDue;
    private static int framesDispatched;
    private static int framesCompleted;
    private static volatile byte @Nullable [] result;
    private static int resultFrames;
    private static int resultExposeTicks;

    private PhotoCaptureClient() {
    }

    /** ファインダーの何かしらの段に入っているか。写真を見る面との重なり防止に使う読み取り口。 */
    public static boolean isEngaged() {
        return phase != Phase.IDLE;
    }

    /** 覗き・露光のあいだ、vanilla の攻撃／使用／ピックを握り潰しているか。 */
    public static boolean shouldBlockInteractions() {
        return phase != Phase.IDLE || awaitRelease;
    }

    /** カメラ実体として置いている Marker（覗いていない間は null）。mixin 側のカメラ配線が読む。 */
    public static @Nullable Entity cameraMarker() {
        return marker;
    }

    // ------------------------------------------------------------ payload 受信（server からの指示）

    /** 1 回目のクリック。カメラ視点へ移り、光の読みを出す。ここではまだ何も撮らない。 */
    public static void openViewfinder(ViewfinderOpenPayload payload) {
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

        Marker placed = new Marker(EntityType.MARKER, mc.level);
        placed.setPos(eye.x, eye.y, eye.z);
        placed.setYRot(targetYaw);
        placed.setXRot(targetPitch);
        placed.setOldPosAndRot();
        marker = placed;

        savedCamera = mc.getCameraEntity();
        hudWasHidden = mc.options.hideGui;
        if (!hudWasHidden && suppressVanillaHud) {
            mc.options.hideGui = true;
        }
        // 三人称のままだと camera が marker の 4 ブロック後ろへ引かれる。
        savedCameraType = mc.options.getCameraType();
        if (savedCameraType != CameraType.FIRST_PERSON) {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
        mc.setCameraEntity(placed);

        enterPlayerYaw = mc.player.getYRot();
        enterPlayerPitch = mc.player.getXRot();
        mc.player.setXRot(0.0F);
        mc.player.xRotO = 0.0F;
        lastPlayerYaw = mc.player.getYRot();
        lastPlayerPitch = 0.0F;
        yawOffset = 0.0F;
        pitchOffset = 0.0F;
        shotYawOffset = 0.0F;
        shotPitchOffset = 0.0F;
        frameDriftX = 0.0F;
        frameDriftY = 0.0F;

        result = null;
        peekElapsed = 0;
        guardTicks = 0;
        shutterWait = 0;
        openFlash = 0;
        closeHold = 0;
        shutterRequested = false;
        shutterQueued = false;
        useDownLast = mc.options.keyUse.isDown();
        exitKeysLatched = exitKeyDown(mc);
        phase = Phase.PEEK;

        // 暗幕を被って、すりガラスを覗く。
        play(SoundEvents.WOOL_PLACE, 0.55F, 0.75F);
        play(SoundEvents.SPYGLASS_USE, 0.55F, 0.9F);
    }

    /** 2 回目のクリックを server が通した。ここから光が溜まりはじめる。 */
    public static void openShutter(ShutterOpenPayload payload) {
        if (phase != Phase.PEEK) {
            // すでにファインダーから出た後に返事が届いた。
            OgpNet.sendToServer(new PhotoCaptureAbortPayload(
                    payload.token(), 0, 0, PhotoCaptureAbortPayload.REASON_LEFT));
            return;
        }
        token = payload.token();
        maxExposeTicks = Math.max(1, payload.window());
        intervalTicks = Math.max(1, payload.interval());
        shotYawOffset = yawOffset;
        shotPitchOffset = pitchOffset;
        java.util.Arrays.fill(SUM, 0);
        framesDispatched = 0;
        framesCompleted = 0;
        result = null;
        exposeElapsed = 0;
        guardTicks = 0;
        openFlash = OPEN_FLASH_TICKS;
        closeHold = 0;
        shutterRequested = false;
        shutterQueued = false;
        captureDue = true; // 露光の 1 枚目は窓の頭で撮る
        phase = Phase.EXPOSING;

        play(SoundEvents.WOODEN_BUTTON_CLICK_ON, 0.85F, 0.5F);
    }

    /** server がシャッターを断った。視点を戻す（理由は actionbar で届く）。 */
    public static void closeViewfinder() {
        if (phase != Phase.PEEK) {
            return;
        }
        play(SoundEvents.SPYGLASS_STOP_USING, 0.5F, 0.9F);
        restore();
        phase = Phase.IDLE;
    }

    // ------------------------------------------------------------ 毎 tick / 每フレームの橋渡し

    /** 每 tick。ローダーの client tick 終端から呼ぶ。 */
    public static void endClientTick() {
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
        if (openFlash > 0) {
            openFlash--;
        }
        boolean useDown = mc.options.keyUse.isDown();
        boolean clicked = useDown && !useDownLast;
        useDownLast = useDown;

        switch (phase) {
            case PEEK -> {
                peekElapsed++;
                if (exitPressed(mc)) {
                    play(SoundEvents.SPYGLASS_STOP_USING, 0.5F, 0.9F);
                    restore();
                    phase = Phase.IDLE;
                    return;
                }
                if (clicked) {
                    shutterQueued = true;
                    play(SoundEvents.LEVER_CLICK, 0.45F, 0.7F);
                }
                if (shutterRequested && ++shutterWait > SHUTTER_REPLY_TIMEOUT_TICKS) {
                    shutterRequested = false;
                    shutterWait = 0;
                }
                if (shutterQueued && !shutterRequested && peekElapsed >= SHUTTER_READY_TICKS) {
                    shutterQueued = false;
                    shutterRequested = true;
                    shutterWait = 0;
                    OgpNet.sendToServer(new ShutterRequestPayload(basePos));
                }
            }
            case EXPOSING -> {
                exposeElapsed++;
                if (exposeElapsed % TICK_INTERVAL == 0) {
                    play(SoundEvents.STONE_BUTTON_CLICK_ON, TICK_VOLUME,
                            exposeElapsed / TICK_INTERVAL % 2 == 0 ? 1.90F : 1.72F);
                }
                boolean filled = exposeElapsed >= maxExposeTicks;
                if (clicked || filled) {
                    if (framesDispatched < PhotoCaptureController.MIN_EXPOSURE_FRAMES) {
                        play(SoundEvents.WOODEN_BUTTON_CLICK_OFF, 0.6F, 0.7F);
                        abort();
                    } else {
                        finishExposure(filled);
                    }
                } else if (exposeElapsed % intervalTicks == 0) {
                    captureDue = true;
                }
            }
            case WAITING -> {
                if (closeHold > 0) {
                    closeHold--;
                }
                byte[] pixels = result;
                if (pixels != null && closeHold <= 0) {
                    restore();
                    OgpNet.sendToServer(
                            new PhotoMapPixelsPayload(token, resultExposeTicks, resultFrames, pixels));
                    LOG.debug("[ogp] sent avg of {} frames, {} ticks (token {})",
                            resultFrames, resultExposeTicks, token);
                    result = null;
                    phase = Phase.IDLE;
                } else if (pixels == null && --waitLeft <= 0) {
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
     * 每フレーム、カメラが player の回転を読むより前に呼ぶ。首振りの差分更新と枠の遅れを進める。
     * Camera mixin（{@code Camera#setup} HEAD）が呼ぶ。
     */
    public static void beforeCameraUpdate() {
        if (phase == Phase.IDLE) {
            return;
        }
        if (phase == Phase.PEEK) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                float yaw = player.getYRot();
                float pitch = player.getXRot();
                float dYaw = Mth.wrapDegrees(yaw - lastPlayerYaw);
                float dPitch = pitch - lastPlayerPitch;
                lastPlayerYaw = yaw;
                lastPlayerPitch = pitch;

                float nextYaw = Mth.clamp(yawOffset + dYaw, -YAW_LIMIT, YAW_LIMIT);
                float nextPitch = Mth.clamp(pitchOffset + dPitch, -PITCH_LIMIT, PITCH_LIMIT);
                advanceFrameDrift(nextYaw - yawOffset, nextPitch - pitchOffset);
                yawOffset = nextYaw;
                pitchOffset = nextPitch;
            }
        } else {
            advanceFrameDrift(0.0F, 0.0F);
        }
    }

    /** 覗き・露光中にカメラが向くべき yaw。{@link #beforeCameraUpdate} を毎フレーム先に呼ぶこと。 */
    public static float desiredYaw() {
        return phase == Phase.EXPOSING || phase == Phase.WAITING
                ? targetYaw + shotYawOffset
                : targetYaw + yawOffset;
    }

    /** 同じく pitch。 */
    public static float desiredPitch() {
        return phase == Phase.EXPOSING || phase == Phase.WAITING
                ? targetPitch + shotPitchOffset
                : targetPitch + pitchOffset;
    }

    /** 覗き・露光中は写真用の固定 FOV。覗いていない間は NaN（ローダー側は何もしない）。 */
    public static float fovOverride() {
        return phase == Phase.IDLE ? Float.NaN : PHOTO_FOV;
    }

    // ------------------------------------------------------------ 描画

    /**
     * ファインダーの面。暗幕・すりガラスの枠・レンズキャップ・光の読みを描く。
     *
     * <p>Fabric 1.20.1 では {@code HudRenderCallback}（引数は GuiGraphics と部分 tick）
     * として登録される。この callback は vanilla の {@code hudVisible} で包まれないため、
     * HUD を隠したままでも描かれる。
     *
     * <p>ここで描いたものは写真に写り込まない。撮影はレベル描画の終端
     * （{@code WorldRenderEvents.END}）でコピー済みだから。
     */
    public static void renderViewfinder(GuiGraphics graphics, float tickDelta) {
        if (phase == Phase.IDLE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ViewfinderGeometry.Square open = aperture(mc);
        if (open == null) {
            return;
        }
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();

        // 1. 暗幕。開口の外は塗り潰す。ここは<b>絶対に動かさない</b>（撮れる範囲そのものなので）。
        graphics.fill(0, 0, w, open.y(), CLOTH_COLOR);
        graphics.fill(0, open.bottom(), w, h, CLOTH_COLOR);
        graphics.fill(0, open.y(), open.x(), open.bottom(), CLOTH_COLOR);
        graphics.fill(open.right(), open.y(), w, open.bottom(), CLOTH_COLOR);

        // 2. すりガラスの面・枠・四隅の落ち。テクスチャ全域を矩形へ引き伸ばす blit。
        int dx = Math.round(frameDriftX);
        int dy = Math.round(frameDriftY);
        int pad = ViewfinderGeometry.framePad(open.side(), (int) Math.ceil(FRAME_DRIFT_MAX));
        // blit は blend を張らないので、すりガラスの半透明が効かず面が塗り潰される。
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        graphics.blit(VIEWFINDER_TEXTURE,
                open.x() - pad + dx, open.y() - pad + dy,
                open.right() + pad + dx - (open.x() - pad + dx),
                open.bottom() + pad + dy - (open.y() - pad + dy),
                0.0F, 0.0F, VIEWFINDER_TEX_SIZE, VIEWFINDER_TEX_SIZE, VIEWFINDER_TEX_SIZE, VIEWFINDER_TEX_SIZE);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();

        // 3. レンズキャップ。開いた直後と、露光が満ちた直後に開口を塞ぐ。
        if (openFlash > 0 || phase == Phase.WAITING) {
            graphics.fill(open.x(), open.y(), open.right(), open.bottom(), CAP_COLOR);
        }

        // 4. 光の読み。開口の下辺の内側（枠のすぐ上）に置く。
        //    この帯に drawStringWithBackdrop は無いので、背景の fill を自分で敷く。
        ViewfinderReading current = reading;
        if (phase != Phase.PEEK || current == null) {
            return;
        }
        // 覗いてから数秒で消す（2026-08-31 kura「写真取るのに邪魔だぜ」）。
        // 読みは覗いた時に 1 回決まって以後変わらないので、経過 tick だけで足りる。
        int alpha = 255;
        if (peekElapsed >= READING_HOLD_TICKS) {
            int fade = peekElapsed - READING_HOLD_TICKS;
            if (fade >= READING_FADE_TICKS) {
                return;
            }
            alpha = 255 * (READING_FADE_TICKS - fade) / READING_FADE_TICKS;
        }
        Font font = Minecraft.getInstance().font;
        // 開口の幅で折り返す。1 行で描くと長い読みが画面の外へ出る。
        int maxWidth = Math.max(160, Math.min(open.side(), w) - 24);
        List<FormattedCharSequence> lines = font.split(current.line(), maxWidth);
        if (lines.isEmpty()) {
            lines = List.of(current.line().getVisualOrderText());
        }
        int step = font.lineHeight + 2;
        // 最後の行が、折り返しが無かった時と同じ高さに来るように上へ積む。
        int lastY = open.bottom() - Math.max(24, open.side() / 12) - 4;
        int top = lastY - step * (lines.size() - 1);
        int backdrop = (LINE_BACKDROP_COLOR & 0x00FFFFFF)
                | ((((LINE_BACKDROP_COLOR >>> 24) * alpha / 255) & 0xFF) << 24);
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence part = lines.get(i);
            int width = font.width(part);
            int y = top + step * i;
            graphics.fill(w / 2 - width / 2 - 2, y - 3, w / 2 + width / 2 + 2, y + 5, backdrop);
            graphics.drawString(font, part, w / 2 - width / 2, y, (alpha << 24) | 0x00FFFFFF);
        }
    }

    /**
     * ファインダーの開口（GUI px）。写真になる切り出しと同じ矩形。
     *
     * <p>寸法は screenshot が読むのと同じ {@code getMainRenderTarget} から採る。
     */
    private static ViewfinderGeometry.@Nullable Square aperture(Minecraft mc) {
        RenderTarget target = mc.getMainRenderTarget();
        if (target.width <= 0 || target.height <= 0) {
            return null;
        }
        return ViewfinderGeometry.aperture(target.width, target.height, mc.getWindow().getGuiScale());
    }

    /** 枠の遅れを 1 フレーム進める。回転が止まれば 0 へ戻る。 */
    private static void advanceFrameDrift(float dYaw, float dPitch) {
        double scale = Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
        float breathX = 0.0F;
        float breathY = 0.0F;
        if (phase == Phase.EXPOSING) {
            float t = exposeElapsed * Mth.TWO_PI / BREATH_PERIOD;
            breathX = Mth.sin(t) * BREATH_AMPLITUDE;
            breathY = Mth.cos(t * 0.5F) * (BREATH_AMPLITUDE * 0.5F);
        }
        float targetX = Mth.clamp((float) (-dYaw * FRAME_DRIFT_GAIN / scale) + breathX,
                -FRAME_DRIFT_MAX, FRAME_DRIFT_MAX);
        float targetY = Mth.clamp((float) (-dPitch * FRAME_DRIFT_GAIN / scale) + breathY,
                -FRAME_DRIFT_MAX, FRAME_DRIFT_MAX);
        frameDriftX += (targetX - frameDriftX) * FRAME_DRIFT_FOLLOW;
        frameDriftY += (targetY - frameDriftY) * FRAME_DRIFT_FOLLOW;
    }

    // ------------------------------------------------------------ 出口まわり

    /**
     * 覗きの間に画面が開こうとした時の扱い。ローダー側の画面開始 hook から呼ぶ。
     *
     * <p>Esc も E も「カメラから離れる」に倒す。工程はメニューを使わないので、覗いたまま
     * 画面が開くと暗幕の開口の中に vanilla の画面が覗く形になり、出口も分からなくなる。
     *
     * <p>ウィンドウが非アクティブな時のポーズ画面（vanilla の自動ポーズ）は握り潰さない。
     * 握り潰すと席を外している間もワールドが動き続ける。
     *
     * @return 画面の表示そのものを止めるか
     */
    public static boolean onScreenOpening(@Nullable Screen newScreen) {
        if (phase == Phase.IDLE) {
            return false;
        }
        boolean swallow = newScreen != null && Minecraft.getInstance().isWindowActive();
        // 露光が満ちて像の返りを待っている間は抜けない（抜けると撮った像を捨てることになる）。
        if (phase == Phase.WAITING) {
            return swallow;
        }
        play(SoundEvents.SPYGLASS_STOP_USING, 0.5F, 0.9F);
        forceRestore();
        return swallow;
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

    /** スニークか移動で、撮らずにファインダーから出る（§31）。 */
    private static boolean exitPressed(Minecraft mc) {
        if (!exitKeyDown(mc)) {
            exitKeysLatched = false;
            return false;
        }
        return !exitKeysLatched;
    }

    /** 無条件の出口。どの経路で来ても、カメラ実体のまま戻れなくなることは無い。 */
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
            OgpNet.sendToServer(new PhotoCaptureAbortPayload(
                    token, exposeElapsed, framesDispatched, PhotoCaptureAbortPayload.REASON_LEFT));
        }
        restore();
        phase = Phase.IDLE;
        result = null;
    }

    /** 露光を中止する。server の session を解放して、プレートには何も書かせない。 */
    private static void abort() {
        int ticks = exposeElapsed;
        int frames = framesDispatched;
        restore();
        phase = Phase.IDLE;
        result = null;
        OgpNet.sendToServer(new PhotoCaptureAbortPayload(
                token, ticks, frames, PhotoCaptureAbortPayload.REASON_TOO_SHORT));
        LOG.debug("[ogp] exposure closed with {} frames (below {}); plate untouched",
                frames, PhotoCaptureController.MIN_EXPOSURE_FRAMES);
    }

    /** 露光窓を閉じる。累積を平均へ落とし、コールバックの回収へ移る。 */
    private static void finishExposure(boolean filled) {
        resultExposeTicks = exposeElapsed;
        phase = Phase.WAITING;
        waitLeft = CALLBACK_TIMEOUT_TICKS;
        closeHold = CLOSE_HOLD_TICKS;
        // キャップが戻る。満ちて閉じた時だけ、板が座る音を重ねて「撮れた」を別物にする。
        play(SoundEvents.WOODEN_BUTTON_CLICK_OFF, 0.85F, 0.5F);
        if (filled) {
            play(SoundEvents.GLASS_PLACE, 0.5F, 1.15F);
        }
        tryFinalize();
    }

    /** コールバックが全部戻っていれば平均像を作る。Java 17 に Math.clamp は無いので Mth.clamp。 */
    private static void tryFinalize() {
        if (phase != Phase.WAITING || framesCompleted < framesDispatched) {
            return;
        }
        int frames = Math.max(1, framesCompleted);
        byte[] out = new byte[LatentImage.SIZE];
        for (int i = 0; i < LatentImage.SIZE; i++) {
            out[i] = (byte) Mth.clamp(SUM[i] / frames, 0, 255);
        }
        resultFrames = framesCompleted;
        result = out;
    }

    private static void restore() {
        Minecraft mc = Minecraft.getInstance();
        awaitRelease = mc.options.keyUse.isDown();
        useDownLast = awaitRelease;
        mc.setCameraEntity(savedCamera != null ? savedCamera : mc.player);
        if (!hudWasHidden && mc.options.hideGui) {
            mc.options.hideGui = false;
        }
        if (savedCameraType != null) {
            mc.options.setCameraType(savedCameraType);
            savedCameraType = null;
        }
        // 覗くために借りていた向きを player へ返す。
        if (mc.player != null) {
            mc.player.setYRot(enterPlayerYaw);
            mc.player.yRotO = enterPlayerYaw;
            mc.player.setXRot(enterPlayerPitch);
            mc.player.xRotO = enterPlayerPitch;
        }
        openFlash = 0;
        closeHold = 0;
        frameDriftX = 0.0F;
        frameDriftY = 0.0F;
        marker = null;
        savedCamera = null;
        reading = null;
    }

    /** カメラの位置で鳴らす（client 側だけ）。 */
    private static void play(SoundEvent sound, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || lensPos == null) {
            return;
        }
        mc.level.playLocalSound(lensPos.getX(), lensPos.getY(), lensPos.getZ(),
                sound, SoundSource.BLOCKS, volume, pitch, false);
    }

    /** レベル描画の終端。露光の 1 フレームを mainRenderTarget から落とす。 */
    public static void onLevelRenderEnd() {
        if (phase != Phase.EXPOSING || !captureDue) {
            return;
        }
        captureDue = false;
        Minecraft mc = Minecraft.getInstance();
        final int idx = framesDispatched++;
        // 1.20.1 の takeScreenshot は同期版（戻り値 NativeImage。RESOLUTION #1 の確定値）。
        try (NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            accumulate(img);
            framesCompleted++;
            tryFinalize();
        } catch (Throwable t) {
            LOG.error("[ogp] capture failed on frame {}", idx, t);
        }
    }

    /** 生フレーム -&gt; 中央正方形クロップ -&gt; {@link LatentImage#DIM} 角 -&gt; 8bit gray を SUM へ加算。 */
    private static void accumulate(NativeImage img) throws Exception {
        ViewfinderGeometry.Square c = ViewfinderGeometry.crop(img.getWidth(), img.getHeight());
        try (NativeImage small = new NativeImage(LatentImage.DIM, LatentImage.DIM, false)) {
            img.resizeSubRectTo(c.x(), c.y(), c.side(), c.side(), small);
            for (int y = 0; y < LatentImage.DIM; y++) {
                for (int x = 0; x < LatentImage.DIM; x++) {
                    int argb = small.getPixelRGBA(x, y);
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    SUM[x + y * LatentImage.DIM] += (r * 299 + g * 587 + b * 114) / 1000;
                }
            }
        }
    }
}

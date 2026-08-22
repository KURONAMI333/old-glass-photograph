package com.kuronami.oldglassphotograph.client.capture;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.capture.PhotoCaptureController;
import com.kuronami.oldglassphotograph.capture.ViewfinderGeometry;
import com.kuronami.oldglassphotograph.capture.ViewfinderReading;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.network.PhotoCaptureAbortPayload;
import com.kuronami.oldglassphotograph.network.PhotoMapPixelsPayload;
import com.kuronami.oldglassphotograph.network.ShutterOpenPayload;
import com.kuronami.oldglassphotograph.network.ShutterRequestPayload;
import com.kuronami.oldglassphotograph.network.ViewfinderClosePayload;
import com.kuronami.oldglassphotograph.network.ViewfinderOpenPayload;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
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
 * <p><b>覗いている間は自由に構図を動かせる</b>（§32-3 A 案）。マウスの回転をそのまま
 * カメラの向きに足し、シャッターを切った時の向きが写真になる。<b>露光が始まったら固定する</b>——
 * 三脚に載ったカメラは露光中に動かないし、動かせると 40 枚の時間平均が全部ぶれて
 * 動体消失（§1）が壊れる。固定は「露光中は向きを書く経路が 1 つも無い」ことで保証する
 * （{@link #onCameraAngles} の PEEK 分岐だけが {@link #yawOffset} を書く）。
 *
 * <p><b>露光は 1 枚の撮影ではなく、窓のあいだの複数フレームの輝度平均。</b>
 * 実物の湿板写真で動体が消えるのは露光中の光を平均するからで、同じ原理をそのまま置いている。
 * 各フレームは撮った直後に 128x128 gray へ落としてから累積する（フル解像度で累積しない）。
 * 量子化は server 側の現像で 1 回だけ行う。
 *
 * <p>撮影点は RenderLevelStageEvent.AfterLevel。この時点の mainRenderTarget には
 * 手も HUD も GUI も入っていない（MODJAM_SPIKE_RESULT.md a 節）。
 * {@code Screenshot#takeScreenshot} は<b>その場でコピー命令を積む</b>
 * （{@code createCommandEncoder().copyTextureToBuffer}）ので、後から GUI を描いても写真に入らない。
 * ファインダーの枠・暗幕・キャップの塗り潰しはすべてこの性質に乗っている。
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

    /** 覗いている間に振れる左右の角度。三脚の雲台の可動域にあたる。 */
    private static final float YAW_LIMIT = 70.0F;

    /** 同じく上下。あおりの効く範囲を超えて真上・真下へは向けない。 */
    private static final float PITCH_LIMIT = 45.0F;

    /**
     * 枠がマウスの回転に一拍遅れて追う量の上限（GUI px）。
     *
     * <p>世界と枠が別々に動くと、枠が player の手前にある物として見える。
     * <b>開口の矩形そのものは動かさない</b>（構図の一致の唯一の出所なので）。動くのは枠の絵だけ。
     */
    private static final float FRAME_DRIFT_MAX = 5.0F;

    /** 1 度あたり何 GUI px ずらすか（実画面の px を一定にするので guiScale で割る）。 */
    private static final float FRAME_DRIFT_GAIN = 3.0F;

    /** 遅れの追従。1 に近いほど即応（＝遅れが消える）。 */
    private static final float FRAME_DRIFT_FOLLOW = 0.45F;

    /**
     * 露光中に時計が刻む間隔（tick）。<b>1 秒固定で、露光の長さに一切依存しない。</b>
     *
     * <p>露光は真昼で 4 秒、暗い所では最大 12 秒かかる。その間は向きが固定され世界も止まって見えるので、
     * 何も出さないと<b>フリーズしたと読まれる</b>（2026-08-22 kura 実機指摘）。
     * 湿板の写真家はキャップを外してから懐中時計か口の中で秒を数えていたので、
     * 拍を刻むこと自体が実物の工程にあたる。
     *
     * <p><b>拍の周期を固定にするのが要点。</b>残り時間に応じて速くしたり、拍の総数で
     * 満ちる時刻を割り出せたりすると、それは数値・進捗の割合を出したのと同じになる
     * （{@code MODJAM_DECISIONS_OGP.md} §15）。この拍から読めるのは「時間が進んでいる」だけ。
     */
    private static final int TICK_INTERVAL = 20;

    /** 時計の音。手元の小さな音なので通さない。 */
    private static final float TICK_VOLUME = 0.28F;

    /**
     * 露光中だけ枠が呼吸する量（GUI px）。
     *
     * <p>音を切っている player にも「止まっていない」を届ける。三脚に載せて暗幕を被った
     * カメラが完全に静止することは無いので、ごく浅い揺れを置く。
     * <b>{@link #FRAME_DRIFT_MAX} の予算の中で動かす</b>（枠の不透明部が開口へ食い込まない
     * 不変条件は {@code generate_viewfinder.py} の {@code check()} がこの値で検算している）。
     * 露光中は回転が止まっていて遅れの項が 0 なので、予算はまるごとここに使える。
     *
     * <p>貼る位置は整数の GUI px へ丸められる（{@code graphics.blit} が int）。
     * 振れが小さいと通る位置が 3〜4 箇所しか無く、滑らかに漂うのではなく段で飛んで見える。
     * <b>ここは「揺れの大きさ」より「段の細かさ」を決める値</b>として扱う。
     */
    private static final float BREATH_AMPLITUDE = 3.0F;

    /** 呼吸の周期（tick）。時計の拍と同じにすると機械仕掛けに見えるので、割り切れない値にする。 */
    private static final float BREATH_PERIOD = 47.0F;

    /** シャッターが開いた瞬間、開口を塞いだままにする tick（キャップが横切る）。 */
    private static final int OPEN_FLASH_TICKS = 2;

    /** 露光が満ちた後、視点を戻す前に開口を塞いだままにする tick。 */
    private static final int CLOSE_HOLD_TICKS = 5;

    /** 暗幕。開口の外はここで塗り潰す。 */
    private static final int CLOTH_COLOR = 0xFF0B0908;

    /** レンズキャップ。開口を塞ぐ。 */
    private static final int CAP_COLOR = 0xFF070605;

    /**
     * すりガラスの面・木の枠・四隅の落ち。開口の上にそのまま伸ばして貼る。
     *
     * <p>面の艶消し（暗く・低彩度・粒状）もこの 1 枚に焼いてある。別レイヤで
     * {@code fill} すると draw call が 1 つ増えるだけで、得るものが無い。
     */
    private static final Identifier VIEWFINDER_TEXTURE = Identifier.fromNamespaceAndPath(
            OldGlassPhotograph.MODID, "textures/gui/viewfinder.png");

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
    private static int openFlash;
    private static int closeHold;

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

    // --- 首振り。PEEK の間だけ動き、シャッターを開けた時点の値が写真になる ---

    /** 設置向きからの左右のずれ。<b>{@link #onCameraAngles} の PEEK 分岐だけが書く。</b> */
    private static float yawOffset;

    /** 同じく上下。 */
    private static float pitchOffset;

    /** シャッターを開けた時点の {@link #yawOffset}。露光中と現像はこれを使う。 */
    private static float shotYawOffset;

    /** シャッターを開けた時点の {@link #pitchOffset}。 */
    private static float shotPitchOffset;

    /** 前フレームの player の向き。差分だけを首振りに足す（絶対値を使うと端で詰まる）。 */
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
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeCameraAngles.class, PhotoCaptureClient::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered.class, PhotoCaptureClient::onInteract);
    }

    /**
     * ファインダーの何かしらの段に入っているか（覗いている・露光中・結果待ち）。
     *
     * <p>写真をじっくり見る面が、同じ画面に 2 枚重ならないようにするためだけの読み取り口
     * （{@code PhotographViewer}）。<b>ここから phase を書き換える経路は無い。</b>
     */
    public static boolean isEngaged() {
        return phase != Phase.IDLE;
    }

    private static void registerHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(ViewfinderOpenPayload.TYPE, (payload, context) -> openViewfinder(payload));
        event.register(ShutterOpenPayload.TYPE, (payload, context) -> openShutter(payload));
        event.register(ViewfinderClosePayload.TYPE, (payload, context) -> closeViewfinder());
    }

    /**
     * ファインダーの面。暗幕・すりガラスの枠・レンズキャップ・光の読みを描く。
     *
     * <p>vanilla の actionbar（{@code OVERLAY_MESSAGE} レイヤ）は HUD が隠れていると描かれないので、
     * ここに専用のレイヤを置く。{@code RegisterGuiLayersEvent} で足したレイヤは
     * vanilla のように {@code hudVisible} で包まれないため、HUD を隠したままでも描かれる。
     *
     * <p>ここで描いたものは写真に写り込まない。撮影は {@code RenderLevelStageEvent.AfterLevel} で
     * GPU のコピー命令を積んでおり、GUI の合成はその後だから。
     */
    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "viewfinder"),
                PhotoCaptureClient::renderViewfinder);
    }

    private static void renderViewfinder(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
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

        graphics.nextStratum();

        // 1. 暗幕。開口の外は塗り潰す。ここは<b>絶対に動かさない</b>（撮れる範囲そのものなので）。
        graphics.fill(0, 0, w, open.y(), CLOTH_COLOR);
        graphics.fill(0, open.bottom(), w, h, CLOTH_COLOR);
        graphics.fill(0, open.y(), open.x(), open.bottom(), CLOTH_COLOR);
        graphics.fill(open.right(), open.y(), w, open.bottom(), CLOTH_COLOR);

        // 2. すりガラスの面・枠・四隅の落ち。マウスの回転に一拍遅れて追う。
        //    張り出しは開口の一辺に比例させる（固定 px だと大きい画面で枠が糸のように細くなる）。
        int dx = Math.round(frameDriftX);
        int dy = Math.round(frameDriftY);
        int pad = ViewfinderGeometry.framePad(open.side(), (int) Math.ceil(FRAME_DRIFT_MAX));
        graphics.blit(VIEWFINDER_TEXTURE,
                open.x() - pad + dx, open.y() - pad + dy,
                open.right() + pad + dx, open.bottom() + pad + dy,
                0.0F, 1.0F, 0.0F, 1.0F);

        // 3. レンズキャップ。開いた直後と、露光が満ちた直後に開口を塞ぐ。
        if (openFlash > 0 || phase == Phase.WAITING) {
            graphics.fill(open.x(), open.y(), open.right(), open.bottom(), CAP_COLOR);
        }

        // 4. 光の読み。開口の下辺の内側（枠のすぐ上）に置く。
        ViewfinderReading current = reading;
        if (phase != Phase.PEEK || current == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Component line = current.line();
        int width = font.width(line);
        graphics.pose().pushMatrix();
        graphics.pose().translate(w / 2.0F, open.bottom() - Math.max(24, open.side() / 12.0F));
        graphics.textWithBackdrop(font, line, -width / 2, -4, width, 0xFFFFFFFF);
        graphics.pose().popMatrix();
    }

    /**
     * ファインダーの開口（GUI px）。写真になる切り出しと同じ矩形。
     *
     * <p>寸法は screenshot が読むのと同じ {@code mainRenderTarget} から採る。window から採ると
     * render target がずれた時に、絵と写真が黙って食い違う。
     */
    private static ViewfinderGeometry.@Nullable Square aperture(Minecraft mc) {
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        if (target.width <= 0 || target.height <= 0) {
            return null;
        }
        return ViewfinderGeometry.aperture(target.width, target.height, mc.getWindow().getGuiScale());
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
     * 覗いている間の向き。<b>ここが首振りの唯一の書き込み口。</b>
     *
     * <p>PEEK の間だけ player のマウス回転の差分を {@link #yawOffset} へ足す。
     * 露光が始まると分岐に入らないので、<b>向きが変わる経路が存在しない</b>
     * （フラグで止めているのではなく、書く場所が無い）。
     */
    private static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
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
                // 実際に動いた分だけ枠を遅らせる（可動域の端では枠も動かない）。
                advanceFrameDrift(nextYaw - yawOffset, nextPitch - pitchOffset);
                yawOffset = nextYaw;
                pitchOffset = nextPitch;
            }
            event.setYaw(targetYaw + yawOffset);
            event.setPitch(targetPitch + pitchOffset);
        } else {
            advanceFrameDrift(0.0F, 0.0F);
            event.setYaw(targetYaw + shotYawOffset);
            event.setPitch(targetPitch + shotPitchOffset);
        }
        event.setRoll(0.0F);
    }

    /**
     * 枠の遅れを 1 フレーム進める。回転が止まれば 0 へ戻る。
     *
     * <p>露光中は回転が止まっているので、代わりに {@link #BREATH_AMPLITUDE} の呼吸を目標に置く。
     * <b>合計は {@link #FRAME_DRIFT_MAX} を超えない</b>（超えると枠が開口へ食い込む）。
     */
    private static void advanceFrameDrift(float dYaw, float dPitch) {
        double scale = Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
        float breathX = 0.0F;
        float breathY = 0.0F;
        if (phase == Phase.EXPOSING) {
            float t = exposeElapsed * Mth.TWO_PI / BREATH_PERIOD;
            breathX = Mth.sin(t) * BREATH_AMPLITUDE;
            // 上下は半周ずらして半分の振れにする。同位相だと斜めの往復になって機械に見える。
            breathY = Mth.cos(t * 0.5F) * (BREATH_AMPLITUDE * 0.5F);
        }
        float targetX = Mth.clamp((float) (-dYaw * FRAME_DRIFT_GAIN / scale) + breathX,
                -FRAME_DRIFT_MAX, FRAME_DRIFT_MAX);
        float targetY = Mth.clamp((float) (-dPitch * FRAME_DRIFT_GAIN / scale) + breathY,
                -FRAME_DRIFT_MAX, FRAME_DRIFT_MAX);
        frameDriftX += (targetX - frameDriftX) * FRAME_DRIFT_FOLLOW;
        frameDriftY += (targetY - frameDriftY) * FRAME_DRIFT_FOLLOW;
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
        // 三人称のままだと camera が marker の 4 ブロック後ろへ引かれ、絵も写真も設計と別物になる。
        savedCameraType = mc.options.getCameraType();
        if (savedCameraType != CameraType.FIRST_PERSON) {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
        mc.setCameraEntity(placed);

        // 首振りは player の回転の差分から作る。真下を向いた状態で入ると上にしか振れないので、
        // 入口で pitch を水平に均しておく（カメラ視点なので player には見えない）。
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

        sessionId++;
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
        // ここから先、向きは動かない。写真になるのはこの瞬間の構図。
        shotYawOffset = yawOffset;
        shotPitchOffset = pitchOffset;
        sessionId++;
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

        // キャップが外れる。低く短い一打。
        play(SoundEvents.WOODEN_BUTTON_CLICK_ON, 0.85F, 0.5F);
    }

    /** server がシャッターを断った。視点を戻す（理由は actionbar で届く）。 */
    private static void closeViewfinder() {
        if (phase != Phase.PEEK) {
            return;
        }
        play(SoundEvents.SPYGLASS_STOP_USING, 0.5F, 0.9F);
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
        if (openFlash > 0) {
            openFlash--;
        }
        boolean useDown = mc.options.keyUse.isDown();
        boolean clicked = useDown && !useDownLast;
        useDownLast = useDown;

        switch (phase) {
            // ファインダー。何秒でもここに居られる。撮るのはもう一度クリックした時だけ。
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
                    // Exposure と同じで、判定より先に鳴らす。往復を待つと押した感触が遅れる。
                    play(SoundEvents.LEVER_CLICK, 0.45F, 0.7F);
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
                // 時計が秒を刻む。周期は固定なので、拍からは「進んでいる」以上のことは読めない。
                if (exposeElapsed % TICK_INTERVAL == 0) {
                    play(SoundEvents.STONE_BUTTON_CLICK_ON, TICK_VOLUME,
                            // 表拍と裏拍でわずかに高さを変える。単調な連打でなく時計に聞こえる。
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
                    ClientPacketDistributor.sendToServer(
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

    /**
     * 露光窓を閉じる。累積を平均へ落とし、コールバックの回収へ移る。
     *
     * <p>視点はすぐには戻さない。{@link #CLOSE_HOLD_TICKS} のあいだ開口をキャップで塞ぎ、
     * <b>撮り終わったことを見た目でも見せる</b>（§32-2。視点が戻るだけでは
     * 「勝手に撮られた」に見える）。
     *
     * @param filled 目標に届いて自動で閉じた（player が途中で閉じたのではない）
     */
    private static void finishExposure(boolean filled) {
        resultExposeTicks = exposeElapsed;
        phase = Phase.WAITING;
        waitLeft = CALLBACK_TIMEOUT_TICKS;
        closeHold = CLOSE_HOLD_TICKS;
        // キャップが戻る。満ちて閉じた時だけ、板が座る音を重ねて「撮れた」を別物にする。
        // 額縁の音は使わない。撮影も右クリックの操作なので、
        // 「額縁に写真をはめた」と読まれる（2026-08-23 kura 実機指摘）。
        // 硝子の板が座る音を、木のキャップの音の上へ薄く重ねる。
        play(SoundEvents.WOODEN_BUTTON_CLICK_OFF, 0.85F, 0.5F);
        if (filled) {
            play(SoundEvents.GLASS_PLACE, 0.5F, 1.15F);
        }
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
        if (savedCameraType != null) {
            mc.options.setCameraType(savedCameraType);
            savedCameraType = null;
        }
        // 覗くために借りていた向きを player へ返す（首を振った分だけ体が回ったままにしない）。
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

    /** カメラの位置で鳴らす（client 側だけ。周りの player には届かない）。 */
    private static void play(SoundEvent sound, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || lensPos == null) {
            return;
        }
        mc.level.playLocalSound(lensPos, sound, SoundSource.BLOCKS, volume, pitch, false);
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
            // ただしコピー命令は dispatch 時点で積まれているので、中身は AfterLevel の絵。
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

    /**
     * 生フレーム -&gt; 中央正方形クロップ -&gt; 128x128 -&gt; 8bit gray を SUM へ加算。
     *
     * <p>切り出しは {@link ViewfinderGeometry#crop} が決める。ファインダーの開口も同じ関数から
     * 出るので、<b>覗いた構図と撮れる構図が食い違う経路が無い</b>。
     */
    private static void accumulate(NativeImage img) throws Exception {
        ViewfinderGeometry.Square c = ViewfinderGeometry.crop(img.getWidth(), img.getHeight());
        try (NativeImage small = new NativeImage(128, 128, false)) {
            img.resizeSubRectTo(c.x(), c.y(), c.side(), c.side(), small);
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

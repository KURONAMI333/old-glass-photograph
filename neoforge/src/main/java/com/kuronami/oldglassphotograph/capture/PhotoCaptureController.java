package com.kuronami.oldglassphotograph.capture;

import com.kuronami.oldglassphotograph.block.WetPlateCameraBlockEntity;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.kuronami.oldglassphotograph.network.PhotoCaptureAbortPayload;
import com.kuronami.oldglassphotograph.network.PhotoCaptureRequestPayload;
import com.kuronami.oldglassphotograph.network.PhotoMapPixelsPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * server 側の露光 session。token を 1 個だけ発行し、返ってきた平均像を検証してから
 * 装填 Plate の潜像へ書く。
 *
 * <p>露光の長さは client が返す（押しっぱなしの長さはキー状態でしか取れない。カメラ実体を
 * 差し替えている間、vanilla の使用ループは設置 Camera を pick しないため server では測れない）。
 * server は上限だけを持ち、返ってきた値を {@code 1..MAX_EXPOSURE_TICKS} に丸める。
 */
public final class PhotoCaptureController {

    private static final Logger LOG = LoggerFactory.getLogger("ogp");

    /**
     * 屋外の真昼（光量 15）で露光が成立する tick。kura 受理済み = 4 秒。
     *
     * <p>暗い場所ではここより長くなる（{@link ExposureModel#requiredTicks}）。
     * 日中の標準の体験が 4 秒である点は変えていない。
     */
    public static final int NOMINAL_EXPOSURE_TICKS = 80;

    /**
     * シャッターを開けていられる上限 tick（12 秒）。露光窓はここか、
     * {@link ExposureModel#requiredTicks} の早い方で閉じる。
     *
     * <p>ここまで押しても目標に届かない明るさ（実測で光量 7 以下）は露光不足になる。
     * client の自己申告を丸める天井も兼ねる（{@code MODJAM_IMPL_LOG_3.md} §7-8）。
     */
    public static final int MAX_EXPOSURE_TICKS = 240;

    /** 既定の撮影間隔。80 tick / 2 = 40 枚（{@code MODJAM_DECISIONS_OGP.md} §8 の N=40）。 */
    public static final int DEFAULT_INTERVAL_TICKS = 2;

    /**
     * 露光として成立する最小の枚数。これに満たないうちに離したら<b>中止</b>で、
     * プレートを消費しない（{@code MODJAM_DECISIONS_OGP.md} §8）。
     * 押し間違い・気が変わったは失敗ではない。
     */
    public static final int MIN_EXPOSURE_FRAMES = 5;

    private static final AtomicInteger NEXT_TOKEN = new AtomicInteger(1);

    /**
     * 認可済みの露光 session。露光は 1 token につき 1 回だけ。
     *
     * @param light 露光を始めた時点の撮影地点の明るさ。<b>撮る時に決まる</b>ので、
     *              現像が夜になっても・カメラが壊されても写真の階調は変わらない
     */
    public record Session(BlockPos pos, int light) {
    }

    private static final Map<Integer, Session> PENDING = new ConcurrentHashMap<>();

    /** カメラを移してから露光を始めるまでの待ち tick（構図の lerp を落ち着かせる）。 */
    private static volatile int settleTicks = 12;

    private static volatile int intervalTicks = DEFAULT_INTERVAL_TICKS;

    private PhotoCaptureController() {
    }

    public static int settleTicks() {
        return settleTicks;
    }

    public static void setSettleTicks(int ticks) {
        settleTicks = Math.clamp(ticks, 0, 200);
    }

    public static int intervalTicks() {
        return intervalTicks;
    }

    public static void setIntervalTicks(int ticks) {
        intervalTicks = Math.clamp(ticks, 1, 200);
    }

    /**
     * カメラを右クリックした時の入口。<b>常にファインダー（カメラ視点）へ入る。</b>
     *
     * <p>露光が始まるのは「装填された板が撮れる状態」で、なおかつ player が
     * {@code PEEK_ARM_TICKS} を超えて押し続けた時だけ。短く押せば構図と動いているものを見るだけで、
     * 板も薬品も消費しない（{@code MODJAM_DECISIONS_OGP.md} §2 Fun 案1）。
     *
     * @return 露光が armed で始まったか（false でもファインダーには入っている）
     */
    public static boolean requestCapture(ServerPlayer player, WetPlateCameraBlockEntity camera,
                                         BlockPos pos, Direction facing) {
        if (camera.isAwaitingCapture()) {
            player.sendSystemMessage(Component.literal("Exposure already in progress."), true);
            return false;
        }
        // 露光を arm できない理由。null なら arm できる。
        // どの理由でも「覗くだけ」は通す（板を消費しないので危険が無い）。
        String blocked = null;
        ItemStack loaded = camera.getPlate();
        if (loaded.isEmpty()) {
            blocked = "No plate loaded. Viewfinder only.";
        } else if (GlassPlateItem.isExposed(loaded)) {
            // 二重露光の禁止。潜像を持った板にもう一度撮ると、出来ていた写真が黙って消える
            // （MODJAM_DECISIONS_OGP.md §8）。プレートは消費せず理由だけ出す。
            blocked = "This plate already holds a latent image. Take it out and develop it.";
        } else if (GlassPlateItem.resolveDryOut(loaded, player.level().getGameTime())) {
            camera.setChanged();
            blocked = "The plate dried out inside the camera. Coat it again.";
        } else if (!GlassPlateItem.isReadyToLoad(loaded)) {
            blocked = "This plate is not sensitized. Coat it with a Collodion Kit first.";
        }

        int light = ExposureModel.sampleLight(player.level(), pos, facing);
        // この明るさで目標に届くまでの tick。上限を超える暗さでも露光自体は許す
        // （上限まで押して届かなければ露光不足。板は写真になる）。
        int required = ExposureModel.requiredTicks(light);
        int window = Math.min(required, MAX_EXPOSURE_TICKS);
        int token = 0;
        if (blocked == null) {
            token = NEXT_TOKEN.getAndIncrement();
            PENDING.put(token, new Session(pos, light));
            camera.beginCapture(token, player.getUUID());
        }
        PacketDistributor.sendToPlayer(player, new PhotoCaptureRequestPayload(
                token, pos, facing.toYRot(), 0.0F, settleTicks,
                window, intervalTicks));
        player.sendSystemMessage(Component.literal(
                blocked != null ? blocked : viewfinderHint(light, required)), true);
        LOG.info("[ogp] viewfinder pos={} facing={} light={} required={} window={} armed={} token={} "
                        + "settle={} interval={}",
                pos, facing, light, required, window, blocked == null, token,
                settleTicks, intervalTicks);
        return token != 0;
    }

    /**
     * 覗いている間に出す 1 行。<b>この明るさで何秒かかるか</b>を先に見せる。
     * HUD を作らずに「暗い所ほど長い」を player へ渡す唯一の経路。
     */
    private static String viewfinderHint(int light, int required) {
        if (required > MAX_EXPOSURE_TICKS) {
            return "Viewfinder. Light " + light + " is too dark for this camera - bring light.";
        }
        return "Viewfinder. Light " + light + " - hold about "
                + String.format(java.util.Locale.ROOT, "%.1f", required / 20.0) + "s.";
    }

    /** client から戻ってきた平均像 16,384 byte を受ける。 */
    public static void receivePixels(ServerPlayer player, PhotoMapPixelsPayload payload) {
        Session session = PENDING.remove(payload.token());
        if (session == null) {
            LOG.warn("[ogp] unknown or already-used capture token {} from {}",
                    payload.token(), player.getGameProfile().name());
            return;
        }
        if (payload.gray().length != LatentImage.SIZE) {
            LOG.warn("[ogp] bad pixel length {} from {}", payload.gray().length,
                    player.getGameProfile().name());
            return;
        }
        BlockPos pos = session.pos();
        if (!(player.level().getBlockEntity(pos) instanceof WetPlateCameraBlockEntity camera)) {
            LOG.warn("[ogp] camera gone at {} before pixels arrived", pos);
            return;
        }
        if (!camera.matchesToken(payload.token(), player.getUUID())) {
            LOG.warn("[ogp] token/owner mismatch at {}", pos);
            return;
        }
        camera.clearCapture();

        ItemStack plate = camera.getPlate();
        if (plate.isEmpty()) {
            LOG.warn("[ogp] plate gone at {} before pixels arrived", pos);
            return;
        }
        int exposure = Math.clamp(payload.exposureTicks(), 1, MAX_EXPOSURE_TICKS);
        // 明るさは露光を始めた時点の値を使う（現像時の時刻やカメラの有無に左右させない）。
        LatentImage latent = new LatentImage(payload.gray(), exposure, session.light());
        plate.set(OgpDataComponents.LATENT_IMAGE.get(), latent);
        // 期限（wetUntil）はそのまま持ち越す。露光しても板は乾き続ける。
        PlateProcess before = GlassPlateItem.process(plate);
        long wetUntil = before == null ? 0L : before.wetUntil();
        plate.set(OgpDataComponents.PLATE_PROCESS.get(), new PlateProcess(
                PlateProcess.Stage.EXPOSED, wetUntil,
                (int) Math.max(0, (wetUntil - player.level().getGameTime() + 19) / 20)));
        camera.setChanged();

        ExposureModel.Result result = ExposureModel.evaluate(payload.gray(), exposure, session.light());
        LOG.info("[ogp][measure-1] latent written: pos={} bytes={} crc32={} exposureTicks={} required={} "
                        + "dose={} frames={} light={} gain={} band={} meanLuma={} clipped={}% crushed={}%",
                pos, latent.pixels().length, latent.checksum(), exposure, result.requiredTicks(),
                fmt(result.dose()), payload.frames(),
                session.light(), fmt(result.gain()), result.band(), fmt(result.meanLuma()),
                fmt(result.clippedPct()), fmt(result.crushedPct()));
        player.sendSystemMessage(Component.literal(result.message()), true);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /**
     * 露光が成立しなかった（最短露光に満たないうちに離した）。session を捨てるだけで、
     * <b>プレートには一切触らない</b>。
     */
    public static void abortCapture(ServerPlayer player, PhotoCaptureAbortPayload payload) {
        Session session = PENDING.remove(payload.token());
        if (session == null) {
            return;
        }
        if (player.level().getBlockEntity(session.pos()) instanceof WetPlateCameraBlockEntity camera
                && camera.matchesToken(payload.token(), player.getUUID())) {
            camera.clearCapture();
        }
        LOG.info("[ogp] exposure released token={} ticks={} frames={} reason={}",
                payload.token(), payload.ticks(), payload.frames(), payload.reason());
        // 覗いただけ（露光が始まる前に離した）なら何も言わない。失敗ではないので。
        if (payload.reason() == PhotoCaptureAbortPayload.REASON_TOO_SHORT) {
            player.sendSystemMessage(Component.literal(
                    "Shutter released too early. No exposure was made."), true);
        }
    }
}

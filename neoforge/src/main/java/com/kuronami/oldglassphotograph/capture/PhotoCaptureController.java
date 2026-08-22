package com.kuronami.oldglassphotograph.capture;

import com.kuronami.oldglassphotograph.block.WetPlateCameraBlock;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlockEntity;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.kuronami.oldglassphotograph.network.PhotoCaptureAbortPayload;
import com.kuronami.oldglassphotograph.network.PhotoMapPixelsPayload;
import com.kuronami.oldglassphotograph.network.ShutterOpenPayload;
import com.kuronami.oldglassphotograph.network.ShutterRequestPayload;
import com.kuronami.oldglassphotograph.network.ViewfinderClosePayload;
import com.kuronami.oldglassphotograph.network.ViewfinderOpenPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * server 側の露光。撮影は<b>2 つの離散的なクリック</b>で挟まれる
 * （{@code MODJAM_DECISIONS_OGP.md} §31。実物の湿板もレンズキャップを外して戻す操作）。
 *
 * <ol>
 *   <li>1 回目の右クリック -&gt; {@link #openViewfinder} = カメラを覗く。何秒でもそのまま。
 *       ここでは token を発行しない</li>
 *   <li>2 回目のクリック -&gt; {@link #openShutter} = 露光を始める。<b>判定は全部ここ。</b>
 *       覗いている間に板が乾いたり抜かれたりしうるので、覗いた時点の検査では足りない</li>
 *   <li>目標に達すると client が窓を閉じ、平均像を {@link #receivePixels} へ返す</li>
 * </ol>
 *
 * <p>実際に何 tick 露光したかは client が返す。server は上限だけを持ち、
 * 返ってきた値を {@code 1..MAX_EXPOSURE_TICKS} に丸める。
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
     * <p>ここまで開けても目標に届かない明るさ（実測で光量 7 以下）は露光不足になる。
     * client の自己申告を丸める天井も兼ねる（{@code MODJAM_IMPL_LOG_3.md} §7-8）。
     */
    public static final int MAX_EXPOSURE_TICKS = 240;

    /** 撮影間隔。80 tick / 2 = 40 枚（{@code MODJAM_DECISIONS_OGP.md} §8 の N=40）。 */
    public static final int INTERVAL_TICKS = 2;

    /**
     * 露光として成立する最小の枚数。シャッターが開いた直後に閉じた場合は<b>中止</b>で、
     * プレートを消費しない（{@code MODJAM_DECISIONS_OGP.md} §8）。
     *
     * <p>0 枚で確定させると平均が全ゼロ＝真っ黒の写真になり、板だけが減る。
     * 早く閉じること自体は意図した選択（§31）だが、像が 1 枚も乗っていない状態は写真にしない。
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

    private PhotoCaptureController() {
    }

    /**
     * カメラを右クリックした時の入口。<b>常にファインダーへ入るだけ</b>で、板も薬品も消費しない。
     *
     * <p>撮れない状態（板が無い・すでに露光済み・乾いた）でも覗ける。構図と、いま何が動いているかを
     * 撮る前に見られること自体が要件（{@code MODJAM_DECISIONS_OGP.md} §2 Fun 案1）。
     * 撮れない理由は覗いている間の 1 行に出る。
     *
     * @param entityPos カメラ BlockEntity の実位置（下半分）
     * @param lensPos   撮影原点（上半分＝レンズの位置）。光量サンプリングと client のカメラ位置に使う
     */
    public static void openViewfinder(ServerPlayer player, WetPlateCameraBlockEntity camera,
                                      BlockPos entityPos, BlockPos lensPos, Direction facing) {
        ViewfinderReading blocked = readPlate(player, camera);
        ViewfinderReading reading = blocked != null
                ? blocked
                : ExposureModel.reading(ExposureModel.sampleLight(player.level(), lensPos, facing));
        PacketDistributor.sendToPlayer(player, new ViewfinderOpenPayload(
                entityPos, lensPos, facing.toYRot(), 0.0F, reading));
    }

    /**
     * 覗いている player がもう一度クリックした。<b>ここが唯一の arm 地点。</b>
     *
     * <p>覗いている間に板が乾く・抜かれる・別の player が撮ることがあるので、
     * 検査は覗いた時点ではなくここで行う。通らなければファインダーから出して理由を言う
     * （覗いている間は HUD が隠れていて actionbar が見えない）。
     */
    public static void openShutter(ServerPlayer player, ShutterRequestPayload payload) {
        BlockPos basePos = payload.cameraPos();
        BlockState state = player.level().getBlockState(basePos);
        if (!(state.getBlock() instanceof WetPlateCameraBlock)
                || !(player.level().getBlockEntity(basePos) instanceof WetPlateCameraBlockEntity camera)) {
            close(player, "The camera is gone.");
            return;
        }
        if (!player.isWithinBlockInteractionRange(basePos, 2.0)) {
            close(player, "You are too far from the camera.");
            return;
        }
        // 覗いている間に横や前へ回り込まれたらシャッターを切らせない
        // （B-1 の立ち位置の条件は入口だけでなくシャッターにも掛かる）。
        if (!WetPlateCameraBlock.isBehind(state.getValue(WetPlateCameraBlock.FACING), basePos, player)) {
            close(player, "You have to stand close behind the camera to take the shot.");
            return;
        }
        if (camera.isAwaitingCapture()) {
            close(player, "Exposure already in progress.");
            return;
        }
        ViewfinderReading blocked = readPlate(player, camera);
        if (blocked != null) {
            close(player, blocked.reason());
            return;
        }

        Direction facing = state.getValue(WetPlateCameraBlock.FACING);
        int light = ExposureModel.sampleLight(player.level(), basePos.above(), facing);
        // この明るさで目標に届くまでの tick。上限を超える暗さでも露光自体は許す
        // （上限まで開けて届かなければ露光不足。板は写真になる）。
        int window = Math.min(ExposureModel.requiredTicks(light), MAX_EXPOSURE_TICKS);

        // 乾燥期限を跨ぐ露光は arm しない（MODJAM_DECISIONS_OGP.md §27 B4）。
        // 「すでに乾いているか」だけを見ていると、成功を告げた直後に潜像が消える。
        PlateProcess process = GlassPlateItem.process(camera.getPlate());
        if (process != null && process.isWet()
                && process.wetUntil() - player.level().getGameTime() < window) {
            close(player, ViewfinderReading.WOULD_DRY.reason());
            return;
        }

        int token = NEXT_TOKEN.getAndIncrement();
        PENDING.put(token, new Session(basePos, light));
        camera.beginCapture(token, player.getUUID());
        PacketDistributor.sendToPlayer(player, new ShutterOpenPayload(token, window, INTERVAL_TICKS));
    }

    /**
     * 装填 Plate が撮れない理由。撮れるなら null。
     *
     * <p>乾燥の清算（{@code resolveDryOut}）はここで済ませる。カメラの中では
     * {@code inventoryTick} が回らないので、触った時に清算しないと古い状態のまま残る。
     */
    private static @Nullable ViewfinderReading readPlate(ServerPlayer player, WetPlateCameraBlockEntity camera) {
        ItemStack loaded = camera.getPlate();
        if (loaded.isEmpty()) {
            return ViewfinderReading.NO_PLATE;
        }
        if (GlassPlateItem.isExposed(loaded)) {
            // 二重露光の禁止。潜像を持った板にもう一度撮ると、出来ていた写真が黙って消える
            // （MODJAM_DECISIONS_OGP.md §8）。
            return ViewfinderReading.ALREADY_EXPOSED;
        }
        if (GlassPlateItem.resolveDryOut(loaded, player.level().getGameTime())) {
            camera.setChanged();
            return ViewfinderReading.DRIED;
        }
        if (!GlassPlateItem.isReadyToLoad(loaded)) {
            return ViewfinderReading.NOT_SENSITIZED;
        }
        return null;
    }

    /** ファインダーから出してから理由を言う。覗いている間は actionbar が見えないため。 */
    private static void close(ServerPlayer player, String reason) {
        PacketDistributor.sendToPlayer(player, ViewfinderClosePayload.INSTANCE);
        player.sendSystemMessage(Component.literal(reason), true);
    }

    /** client から戻ってきた平均像 16,384 byte を受ける。 */
    public static void receivePixels(ServerPlayer player, PhotoMapPixelsPayload payload) {
        Session session = PENDING.remove(payload.token());
        if (session == null) {
            LOG.warn("[ogp] unknown or already-used capture token {} from {}",
                    payload.token(), player.getGameProfile().name());
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
        LOG.info("[ogp] exposed at {}: light={} ticks={} required={} frames={} band={}",
                pos, session.light(), exposure, result.requiredTicks(), payload.frames(), result.band());
        player.sendSystemMessage(Component.literal(result.message()), true);
    }

    /**
     * 露光が写真にならずに終わった。session を捨てるだけで、<b>プレートには一切触らない</b>。
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
        LOG.debug("[ogp] exposure ended without a photo: token={} ticks={} frames={} reason={}",
                payload.token(), payload.ticks(), payload.frames(), payload.reason());
        // 撮らずに出ただけなら何も言わない。失敗ではないので。
        if (payload.reason() == PhotoCaptureAbortPayload.REASON_TOO_SHORT) {
            player.sendSystemMessage(Component.literal(
                    "The shutter closed before any light reached the plate. Nothing was exposed."), true);
        }
    }
}

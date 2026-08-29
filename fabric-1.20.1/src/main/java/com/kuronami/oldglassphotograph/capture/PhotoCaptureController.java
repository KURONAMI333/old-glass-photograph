package com.kuronami.oldglassphotograph.capture;

import com.kuronami.oldglassphotograph.block.WetPlateCameraBlock;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlockEntity;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.component.OgpNbt;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.kuronami.oldglassphotograph.network.OgpNet;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * server 側の露光。撮影は<b>2 つの離散的なクリック</b>で挟まれる（§31）。
 *
 * <ol>
 *   <li>1 回目の右クリック -&gt; {@link #openViewfinder} = カメラを覗く。ここでは token を発行しない</li>
 *   <li>2 回目のクリック -&gt; {@link #openShutter} = 露光を始める。<b>判定は全部ここ。</b></li>
 *   <li>目標に達すると client が窓を閉じ、平均像を {@link #receivePixels} へ返す</li>
 * </ol>
 */
public final class PhotoCaptureController {

    private static final Logger LOG = LoggerFactory.getLogger("ogp");

    /** 屋外の真昼（光量 15）で露光が成立する tick。実機検証済み = 4 秒。 */
    public static final int NOMINAL_EXPOSURE_TICKS = 80;

    /** シャッターを開けていられる上限 tick（12 秒）。 */
    public static final int MAX_EXPOSURE_TICKS = 240;

    /** 撮影間隔。80 tick / 2 = 40 枚（§8 の N=40）。 */
    public static final int INTERVAL_TICKS = 2;

    /** 露光として成立する最小の枚数。 */
    public static final int MIN_EXPOSURE_FRAMES = 5;

    /**
     * シャッター要求を受理する距離の上限（水平ではなく 3D 距離・ブロック）。
     *
     * <p>1.20.1 には {@code blockInteractionRange()}（1.20.5+ の属性）が無いため
     * 定数で置く。サバイバル既定の視認リーチ約 4.5 + 覗きのマージン 2.0 ＝
     * neoforge-1.21.1 セルの {@code blockInteractionRange() + 2.0} と同じ値になるよう 6.5。
     */
    private static final double SHUTTER_MAX_RANGE = 6.5;

    private static final AtomicInteger NEXT_TOKEN = new AtomicInteger(1);

    /** 認可済みの露光 session。露光は 1 token につき 1 回だけ。 */
    public record Session(BlockPos pos, int light) {
    }

    private static final Map<Integer, Session> PENDING = new ConcurrentHashMap<>();

    private PhotoCaptureController() {
    }

    /** カメラを右クリックした時の入口。<b>常にファインダーへ入るだけで</b>、板も薬品も消費しない。 */
    public static void openViewfinder(ServerPlayer player, WetPlateCameraBlockEntity camera,
                                      BlockPos entityPos, BlockPos lensPos, Direction facing) {
        ViewfinderReading blocked = readPlate(player, camera);
        ViewfinderReading reading = blocked != null
                ? blocked
                : ExposureModel.reading(ExposureModel.sampleLight(player.level(), lensPos, facing));
        OgpNet.sendToPlayer(player, new ViewfinderOpenPayload(
                entityPos, lensPos, facing.toYRot(), 0.0F, reading));
    }

    /** 覗いている player がもう一度クリックした。<b>ここが唯一の arm 地点。</b> */
    public static void openShutter(ServerPlayer player, ShutterRequestPayload payload) {
        BlockPos basePos = payload.cameraPos();
        BlockState state = player.level().getBlockState(basePos);
        if (!(state.getBlock() instanceof WetPlateCameraBlock)
                || !(player.level().getBlockEntity(basePos) instanceof WetPlateCameraBlockEntity camera)) {
            close(player, Component.translatable("message.old_glass_photograph.camera.gone"));
            return;
        }
        Vec3 center = Vec3.atCenterOf(basePos);
        if (player.distanceToSqr(center.x, center.y, center.z) > SHUTTER_MAX_RANGE * SHUTTER_MAX_RANGE) {
            close(player, Component.translatable("message.old_glass_photograph.camera.too_far"));
            return;
        }
        // 覗いている間に横や前へ回り込まれたらシャッターを切らせない。
        if (!WetPlateCameraBlock.isBehind(state.getValue(WetPlateCameraBlock.FACING), basePos, player)) {
            close(player, Component.translatable("message.old_glass_photograph.camera.stand_behind_shot"));
            return;
        }
        if (camera.isAwaitingCapture()) {
            close(player, Component.translatable("message.old_glass_photograph.camera.exposure_in_progress"));
            return;
        }
        ViewfinderReading blocked = readPlate(player, camera);
        if (blocked != null) {
            close(player, blocked.reason());
            return;
        }

        Direction facing = state.getValue(WetPlateCameraBlock.FACING);
        int light = ExposureModel.sampleLight(player.level(), basePos.above(), facing);
        int window = Math.min(ExposureModel.requiredTicks(light), MAX_EXPOSURE_TICKS);

        // 乾燥期限を跨ぐ露光は arm しない（§27 B4）。
        PlateProcess process = GlassPlateItem.process(camera.getPlate());
        if (process != null && process.isWet()
                && process.wetUntil() - player.level().getGameTime() < window) {
            close(player, ViewfinderReading.WOULD_DRY.reason());
            return;
        }

        int token = NEXT_TOKEN.getAndIncrement();
        PENDING.put(token, new Session(basePos, light));
        camera.beginCapture(token, player.getUUID());
        OgpNet.sendToPlayer(player, new ShutterOpenPayload(token, window, INTERVAL_TICKS));
        // 撮っている本人は client 側で先に鳴らしている。ここで鳴らすのは周りのぶん。
        player.level().playSound(player, basePos.above(),
                SoundEvents.WOODEN_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.7F, 0.5F);
    }

    /** 装込 Plate が撮れない理由。撮れるなら null。乾燥の清算もここで済ませる。 */
    private static @Nullable ViewfinderReading readPlate(ServerPlayer player, WetPlateCameraBlockEntity camera) {
        ItemStack loaded = camera.getPlate();
        if (loaded.isEmpty()) {
            return ViewfinderReading.NO_PLATE;
        }
        if (GlassPlateItem.isExposed(loaded)) {
            // 二重露光の禁止。
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
    private static void close(ServerPlayer player, Component reason) {
        OgpNet.sendToPlayer(player, ViewfinderClosePayload.INSTANCE);
        player.sendSystemMessage(reason, true);
    }

    /** client から戻った平均像を受け取る。像は潜像として板の NBT へ書く。 */
    public static void receivePixels(ServerPlayer player, PhotoMapPixelsPayload payload) {
        Session session = PENDING.remove(payload.token());
        if (session == null) {
            LOG.warn("[ogp] unknown or already-used capture token {}", payload.token());
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
        if (payload.gray().length != LatentImage.SIZE) {
            LOG.error("[ogp] malformed pixel payload at {}: {} bytes", pos, payload.gray().length);
            return;
        }
        camera.clearCapture();

        ItemStack plate = camera.getPlate();
        if (plate.isEmpty()) {
            LOG.warn("[ogp] plate gone at {} before pixels arrived", pos);
            return;
        }
        int exposure = Mth.clamp(payload.exposureTicks(), 1, MAX_EXPOSURE_TICKS);
        LatentImage latent = new LatentImage(payload.gray(), exposure, session.light());
        OgpNbt.setLatent(plate, latent);
        // 期限（wetUntil）はそのまま持ち越す。露光しても板は乾き続ける。
        PlateProcess before = GlassPlateItem.process(plate);
        long wetUntil = before == null ? 0L : before.wetUntil();
        OgpNbt.setProcess(plate, new PlateProcess(
                PlateProcess.Stage.EXPOSED, wetUntil,
                (int) Math.max(0, (wetUntil - player.level().getGameTime() + 19) / 20)));
        camera.setChanged();

        player.level().playSound(player, pos.above(),
                SoundEvents.WOODEN_BUTTON_CLICK_OFF, SoundSource.BLOCKS, 0.7F, 0.5F);

        ExposureModel.Result result = ExposureModel.evaluate(payload.gray(), exposure, session.light());
        LOG.info("[ogp] exposed at {}: light={} ticks={} required={} frames={} band={}",
                pos, session.light(), exposure, result.requiredTicks(), payload.frames(), result.band());
        player.sendSystemMessage(result.message(), true);
    }

    /** 露光が写真にならずに終わった。session を捨てるだけで、<b>プレートには一切触らない</b>。 */
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
        if (payload.reason() == PhotoCaptureAbortPayload.REASON_TOO_SHORT) {
            player.sendSystemMessage(
                    Component.translatable("message.old_glass_photograph.camera.nothing_exposed"), true);
        }
    }
}

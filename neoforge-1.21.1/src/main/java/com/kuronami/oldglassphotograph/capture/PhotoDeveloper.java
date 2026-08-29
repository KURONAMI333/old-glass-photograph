package com.kuronami.oldglassphotograph.capture;

import com.kuronami.oldglassphotograph.OgpAdvancements;
import com.kuronami.oldglassphotograph.OgpObjects;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.component.OgpComponents;
import com.kuronami.oldglassphotograph.component.PhotoCredit;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * 潜像 -> 完成写真。露光量のゲインを掛け、湿板の物理痕跡を乗せてから map パレットへ量子化して
 * locked filled map を作る。
 *
 * <p>量子化は <b>1 回だけ</b>ここで行う（{@code MODJAM_DESIGN_FIXES.md} A-3）。
 */
public final class PhotoDeveloper {

    private static final Logger LOG = LoggerFactory.getLogger("ogp");

    private static final int WIDTH = 128;
    private static final int HEIGHT = 128;

    /**
     * 痕跡の強さは dose（露光の充足度。1.0 で成立）が下がるほど濃くなる連続量
     * （{@code MODJAM_DECISIONS_OGP.md} §19）。
     *
     * <p>dose &gt;= 1.0 でも痕跡はゼロにしない。手で流したコロジオンは完璧な露光でも
     * 縁と現像にムラを残すため（同 §12）、下限として {@link #TRACE_FLOOR} を必ず乗せる。
     * dose が 0 へ近づくほど {@link #TRACE_FLOOR} から 1.0 まで線形に濃くなり、
     * 1.0 の強さは {@code _research/MODJAM_VISUAL_CANDIDATES/SHEET_C_photograph.png} の
     * C4/C5（周辺減光+流し縁+現像筋 [+ワニス縁]）を上端の見本として超えない。
     */
    private static final double TRACE_FLOOR = 0.15;

    /**
     * 痕跡の上限。かぶりを足しても<b>ここを超えない</b>ので、写真は必ず得られる
     * （{@code MODJAM_DECISIONS_OGP.md} §30。蓋を開けて作業しても板を失う失敗は増やさない）。
     */
    private static final double TRACE_CEILING = 1.0;

    /**
     * かぶりが最大に効く tick 数。Darkroom Table の 2 工程（塗布 80 + 現像 80）を
     * 蓋を開けたまま最後まで回した量。
     */
    private static final int FOG_FULL_TICKS = 160;

    /** かぶりが痕跡へ足せる最大量。適正露光（痕跡 {@link #TRACE_FLOOR}）でも真っ白にはならない幅に取る。 */
    private static final double FOG_TRACE_MAX = 0.45;

    /** 周辺減光（Petzval 型レンズの周辺光量落ち）。SHEET_C の C1 と同じ強さを上端に採る。 */
    private static final double VIGNETTE_STRENGTH_MAX = 0.55;
    private static final double VIGNETTE_POWER = 2.6;

    /** 流し縁（コロジオンが四隅まで回らず素ガラスが覗く）。SHEET_C の C2 と同じ深さを上端に採る。 */
    private static final int POUR_EDGE_DEPTH_MAX = 14;
    /** 素ガラスの明るさ（SHEET_C の bare=(206,206,198) を luminance 換算）。 */
    private static final int BARE_GLASS_GRAY = 205;

    /** 現像筋（薬液を流しかけた縦方向の濃度むら）。SHEET_C の C3 と同じ振幅を上端に採る。 */
    private static final double FLOW_STREAK_AMOUNT_MAX = 10.0;

    /** ワニス縁（外周に残る暗い塗り縁）。SHEET_C の C5 相当。dose=1.0 でも細く薄く残す。 */
    private static final int VARNISH_WIDTH_MIN = 2;
    private static final int VARNISH_WIDTH_MAX = 5;
    private static final double VARNISH_ALPHA_MAX = 0.85;
    private static final int VARNISH_GRAY = 51;

    private PhotoDeveloper() {
    }

    public static boolean develop(ServerPlayer player, ItemStack plate) {
        LatentImage latent = plate.get(OgpComponents.latentImage());
        if (latent == null) {
            return false;
        }
        if (!latent.hasPixels()) {
            // 潜像の pixel が欠けている。§5(c) で pixel は client へも同期しているので、
            // ここに来るのは保存データが壊れた時だけ。
            LOG.error("[ogp] latent has {} bytes (expected {}) - pixels were lost",
                    latent.pixels().length, LatentImage.SIZE);
            player.sendSystemMessage(Component.translatable("message.old_glass_photograph.plate.blank"), true);
            return false;
        }
        int exposure = Math.clamp(latent.exposureTicks() <= 0
                ? PhotoCaptureController.NOMINAL_EXPOSURE_TICKS : latent.exposureTicks(),
                1, PhotoCaptureController.MAX_EXPOSURE_TICKS);
        // 明暗は「溜めた光の量」で決まる（ExposureModel）。潜像は露光窓のフレーム平均なので
        // 撮影地点の明るさを既に持っており、掛けるのは露光時間ぶんのゲインだけ。
        // 潜像は線形のまま保存してあるので、ゲインを掛けるのはここ 1 回だけ。
        ExposureModel.Result result = ExposureModel.evaluate(latent.pixels(), exposure, latent.light());
        byte[] exposed = ExposureModel.apply(latent.pixels(), exposure);

        ServerLevel level = (ServerLevel) player.level();
        // 痕跡の乱数種は player x 時刻 x 露光量から作る（1 枚ごとに違う縁・筋になる。決定的なので再現もできる）。
        long traceSeed = player.getUUID().getMostSignificantBits()
                ^ level.getGameTime()
                ^ ((long) exposure << 20);
        int fogTicks = plate.getOrDefault(OgpComponents.plateFog(), 0);
        double traceIntensity = traceIntensity(result.dose(), fogTicks);
        byte[] traced = applyPlateTraces(exposed, traceIntensity, traceSeed);

        LOG.debug("[ogp][expose] develop: exposureTicks={} required={} dose={} light={} gain={} band={} "
                        + "meanLuma={} clipped={}% crushed={}% fogTicks={} traceIntensity={}",
                exposure, result.requiredTicks(), result.dose(), latent.light(), result.gain(),
                result.band(), result.meanLuma(), result.clippedPct(), result.crushedPct(),
                fogTicks, traceIntensity);

        byte[] packed = PhotoMapPalette.quantizeAll(traced);
        MapItemSavedData fresh = MapItemSavedData.createFresh(
                player.getX(), player.getZ(), (byte) 0, false, false, level.dimension());
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                fresh.setColor(x, y, packed[x + y * 128]);
            }
        }
        // 色を書いた後に locked() を呼ぶ（先に呼ぶと空の複製になる）
        MapItemSavedData locked = fresh.locked();
        MapId id = level.getFreeMapId();
        level.setMapData(id, locked);

        ItemStack photo = new ItemStack(OgpObjects.photograph());
        photo.set(DataComponents.MAP_ID, id);
        // 撮影者と日付。ここが唯一の書き込み口で、以後この写真では変わらない。
        photo.set(OgpComponents.photoCredit(),
                new PhotoCredit(player.getGameProfile().getName(), PhotoCredit.dayOf(level.getGameTime()),
                        PhotoCredit.captureTimestamp()));

        plate.shrink(1);
        if (!player.addItem(photo)) {
            player.drop(photo, false);
        }
        awardMilestones(player, result, fogTicks);
        LOG.info("[ogp] developed photograph mapId={} steps={}", id, PhotoMapPalette.stepCount());
        return true;
    }

    /**
     * 仕上がった1枚から遊びの節目を拾う。<b>写真が生まれる場所はここ1つ</b>なので、
     * 露光の充足度・撮影地点の明るさ・かぶりの tick が全部この scope に揃っている。
     *
     * <p>露光不足は「やってしまったこと」の記録であって、狙わせるものではない
     * （{@code MODJAM_DECISIONS_OGP.md} §1「取り返しのつかない失敗を作らない」）。
     * したがって進捗の側も hidden にしてあり、文面も出来事の記述にしてある。
     */
    private static void awardMilestones(ServerPlayer player, ExposureModel.Result result, int fogTicks) {
        if (result.band() == ExposureModel.Band.UNDER) {
            OgpAdvancements.award(player, OgpAdvancements.UNDEREXPOSED);
        } else if (ExposureModel.reading(result.light()) == ViewfinderReading.DIM) {
            // 暗い光で目標まで待ち切った1枚。撮れる下限（TOO_DARK）ではなく、
            // 待てば撮れる帯で最も暗いところ。
            OgpAdvancements.award(player, OgpAdvancements.LONG_EXPOSURE);
        }
        if (fogTicks > 0) {
            OgpAdvancements.award(player, OgpAdvancements.LIGHT_GOT_IN);
        }
    }

    /**
     * dose（1.0 で露光成立）とかぶり量から痕跡の強さ 0..1 を作る。{@link #TRACE_FLOOR} が下限で、
     * dose が 0 に近づくほど 1.0 まで線形に上がる。
     *
     * <p>Darkroom Table の蓋を開けたまま工程を回すと光が入り、その tick 数だけ痕跡が濃くなる
     * （{@code MODJAM_DECISIONS_OGP.md} §30）。<b>失敗が増えるのではなく写真がかぶる</b>。
     * かぶりの寄与は {@link #FOG_TRACE_MAX} 止まりで、合計も {@link #TRACE_CEILING} で頭打ちにする。
     *
     * @param fogTicks 蓋を開けたまま工程が進んだ tick 数
     */
    static double traceIntensity(double dose, int fogTicks) {
        double severity = Math.clamp(1.0 - dose, 0.0, 1.0);
        double base = TRACE_FLOOR + (1.0 - TRACE_FLOOR) * severity;
        double fog = FOG_TRACE_MAX * Math.clamp(fogTicks / (double) FOG_FULL_TICKS, 0.0, 1.0);
        return Math.min(base + fog, TRACE_CEILING);
    }

    /**
     * 湿板コロジオン法の物理痕跡を輝度配列へ乗せる。周辺減光 -&gt; 流し縁 -&gt; 現像筋 -&gt; ワニス縁の順に
     * 適用し、強さは {@code intensity} 1本で全成分に効く（{@code MODJAM_DECISIONS_OGP.md} §19）。
     *
     * <p>量子化前（gray 0..255、128x128）にだけ効く。露光の判定・ゲインには一切触れない。
     */
    static byte[] applyPlateTraces(byte[] gray, double intensity, long seed) {
        double[] px = new double[gray.length];
        for (int i = 0; i < gray.length; i++) {
            px[i] = gray[i] & 0xFF;
        }

        Random edgeRng = new Random(seed);
        double[][] edgeWobble = smoothNoiseGrid(edgeRng, 9, 9, WIDTH, HEIGHT);
        Random streakRng = new Random(seed ^ 0x9E3779B97F4A7C15L);
        double[] streakCols = smoothNoise1D(streakRng, WIDTH / 8 + 2, WIDTH);

        double cx = (WIDTH - 1) / 2.0;
        double cy = (HEIGHT - 1) / 2.0;
        double vignetteStrength = VIGNETTE_STRENGTH_MAX * intensity;
        double pourDepth = POUR_EDGE_DEPTH_MAX * intensity;
        double flowAmount = FLOW_STREAK_AMOUNT_MAX * intensity;

        for (int y = 0; y < HEIGHT; y++) {
            // 現像液は上から流すので、下ほどムラが濃く残る（SHEET_C の flow_streak と同じランプ）。
            double ramp = 0.35 + 0.65 * (y / (double) (HEIGHT - 1));
            for (int x = 0; x < WIDTH; x++) {
                int idx = x + y * WIDTH;
                double v = px[idx];

                // 1. 周辺減光（レンズの四隅落ち）
                double rx = (x - cx) / cx;
                double ry = (y - cy) / cy;
                double r = Math.sqrt(rx * rx + ry * ry) / Math.sqrt(2);
                double vm = 1.0 - vignetteStrength * Math.pow(Math.clamp(r, 0.0, 1.0), VIGNETTE_POWER);
                v *= vm;

                // 2. 流し縁（コロジオンが四隅まで回らず素ガラスが覗く）
                if (pourDepth > 0.01) {
                    double wob = edgeWobble[y][x];
                    int d = Math.min(Math.min(x, WIDTH - 1 - x), Math.min(y, HEIGHT - 1 - y));
                    double thr = pourDepth * (0.55 + 0.9 * wob);
                    double t = Math.clamp(1.0 - d / Math.max(thr, 1e-6), 0.0, 1.0);
                    t = Math.pow(t, 1.4);
                    v = v * (1 - t) + BARE_GLASS_GRAY * t;
                }

                // 3. 現像筋（薬液を流しかけた縦方向の濃度むら）
                double g = (streakCols[x] - 0.5) * 2 * flowAmount;
                v += g * ramp;

                px[idx] = v;
            }
        }

        // 4. ワニス縁（外周の暗い塗り縁。幅・濃さとも intensity で伸び縮みし、dose=1.0 でも細く残る）
        int vw = (int) Math.round(VARNISH_WIDTH_MIN + (VARNISH_WIDTH_MAX - VARNISH_WIDTH_MIN) * intensity);
        double vAlphaMax = VARNISH_ALPHA_MAX * intensity;
        if (vw > 0) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int edgeDist = Math.min(Math.min(x, WIDTH - 1 - x), Math.min(y, HEIGHT - 1 - y));
                    if (edgeDist < vw) {
                        double a = vAlphaMax * (1.0 - edgeDist / (double) vw);
                        int idx = x + y * WIDTH;
                        px[idx] = px[idx] * (1 - a) + VARNISH_GRAY * a;
                    }
                }
            }
        }

        byte[] out = new byte[gray.length];
        for (int i = 0; i < gray.length; i++) {
            out[i] = (byte) Math.clamp(Math.round(px[i]), 0L, 255L);
        }
        return out;
    }

    /** grid x grid の乱数を outW x outH へバイリニアで引き伸ばす（縁のふらつき・雲状ノイズ用）。 */
    private static double[][] smoothNoiseGrid(Random rng, int gridH, int gridW, int outW, int outH) {
        double[][] grid = new double[gridH][gridW];
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                grid[gy][gx] = rng.nextDouble();
            }
        }
        double[][] out = new double[outH][outW];
        for (int y = 0; y < outH; y++) {
            double gy = y * (gridH - 1) / (double) Math.max(outH - 1, 1);
            int y0 = (int) Math.floor(gy);
            int y1 = Math.min(y0 + 1, gridH - 1);
            double fy = gy - y0;
            for (int x = 0; x < outW; x++) {
                double gx = x * (gridW - 1) / (double) Math.max(outW - 1, 1);
                int x0 = (int) Math.floor(gx);
                int x1 = Math.min(x0 + 1, gridW - 1);
                double fx = gx - x0;
                double top = grid[y0][x0] * (1 - fx) + grid[y0][x1] * fx;
                double bot = grid[y1][x0] * (1 - fx) + grid[y1][x1] * fx;
                out[y][x] = top * (1 - fy) + bot * fy;
            }
        }
        return out;
    }

    /** grid 個の乱数を outW へ線形補間で引き伸ばす（現像筋の列ごとの濃さ用）。 */
    private static double[] smoothNoise1D(Random rng, int gridW, int outW) {
        double[] grid = new double[gridW];
        for (int gx = 0; gx < gridW; gx++) {
            grid[gx] = rng.nextDouble();
        }
        double[] out = new double[outW];
        for (int x = 0; x < outW; x++) {
            double gx = x * (gridW - 1) / (double) Math.max(outW - 1, 1);
            int x0 = (int) Math.floor(gx);
            int x1 = Math.min(x0 + 1, gridW - 1);
            double fx = gx - x0;
            out[x] = grid[x0] * (1 - fx) + grid[x1] * fx;
        }
        return out;
    }
}

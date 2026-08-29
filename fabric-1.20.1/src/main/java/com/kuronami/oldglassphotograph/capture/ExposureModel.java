package com.kuronami.oldglassphotograph.capture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelReader;

/**
 * 露光量のモデル。<b>光を溜めて、目標に達したら終わる。</b>
 *
 * <pre>
 *   dose(t, L) = B(L) x t                     … 明るいほど速く溜まる
 *   目標        = B(15) x 80 tick             … 屋外の真昼で 4 秒
 *   必要 tick   = 80 / w(L)   ただし w(L) = B(L)/B(15)
 *   完成写真     = 潜像 x (実際の露光 tick / 80)
 * </pre>
 *
 * <p>潜像は露光窓のフレーム平均なので、それ自体が {@code B(L)} を持っている。したがって
 * 上のゲインを掛けると <b>完成写真の明るさは光量によらず一定</b>になる。
 *
 * <p>採った明るさは {@code getMaxLocalRawBrightness}（0..15）。
 */
public final class ExposureModel {

    /** 光量が分からない（古い潜像）。真昼として扱う。 */
    public static final int UNKNOWN_LIGHT = -1;

    /**
     * 露光量が 2 倍になる光量の段数。
     *
     * <p>保存した潜像 9 枚（光量 15..4）の平均輝度から実測した描画応答
     * {@code w(L) = B(L)/B(15)} に {@code 2^((L-15)/k)} を当てて決めた
     * （{@code MODJAM_IMPL_LOG_5.md} §2）。
     */
    private static final double LEVELS_PER_STOP = 4.5;

    /** 飛んだと見なす輝度（記録用。判定には使わない）。 */
    private static final int CLIP_LEVEL = 246;

    /** 沈んだと見なす輝度。map パレットの最下段は 13。 */
    private static final int CRUSH_LEVEL = 20;

    private ExposureModel() {
    }

    /**
     * 撮影地点の明るさ。<b>カメラブロックの中ではなくレンズの前の 1 マス</b>を採る。
     */
    public static int sampleLight(LevelReader level, BlockPos cameraPos, Direction facing) {
        return level.getMaxLocalRawBrightness(cameraPos.relative(facing));
    }

    /**
     * この明るさで露光を成立させるのに必要な tick。屋外の真昼（光量 15）でちょうど 80 = 4 秒。
     */
    public static int requiredTicks(int light) {
        int l = light == UNKNOWN_LIGHT ? 15 : Mth.clamp(light, 0, 15);
        double ticks = PhotoCaptureController.NOMINAL_EXPOSURE_TICKS
                * Math.pow(2.0, (15 - l) / LEVELS_PER_STOP);
        return (int) Math.min(Math.round(ticks), 100000L);
    }

    /**
     * この明るさをファインダーに出す 1 行へ落とす。<b>数値は出さない</b>（§15）。
     */
    public static ViewfinderReading reading(int light) {
        int required = requiredTicks(light);
        if (required > PhotoCaptureController.MAX_EXPOSURE_TICKS) {
            return ViewfinderReading.TOO_DARK;
        }
        if (required <= PhotoCaptureController.NOMINAL_EXPOSURE_TICKS * 7 / 5) {
            return ViewfinderReading.BRIGHT;
        }
        if (required <= PhotoCaptureController.NOMINAL_EXPOSURE_TICKS * 11 / 5) {
            return ViewfinderReading.SOFT;
        }
        return ViewfinderReading.DIM;
    }

    /** 実際に露光した tick から掛けるゲイン。 */
    public static double gain(int exposureTicks) {
        return Mth.clamp(exposureTicks, 0, PhotoCaptureController.MAX_EXPOSURE_TICKS)
                / (double) PhotoCaptureController.NOMINAL_EXPOSURE_TICKS;
    }

    /** 潜像（平均輝度）にゲインを掛けて完成写真の輝度にする。量子化はこの後 1 回だけ。 */
    public static byte[] apply(byte[] mean, int exposureTicks) {
        double gain = gain(exposureTicks);
        byte[] out = new byte[mean.length];
        for (int i = 0; i < mean.length; i++) {
            int v = (int) Math.round((mean[i] & 0xFF) * gain);
            out[i] = (byte) Mth.clamp(v, 0, 255);
        }
        return out;
    }

    /** 露光の 2 経路。過多は無い（{@code MODJAM_DECISIONS_OGP.md} §14）。 */
    public enum Band {
        UNDER, NORMAL
    }

    /** 露光の結果。判定は<b>溜まった光の量</b>で決める。 */
    public record Result(Band band, int light, int exposureTicks, int requiredTicks, double gain,
                         double meanLuma, double clippedPct, double crushedPct) {

        /** 露光がここまで進んだ割合（1.0 で成立）。 */
        public double dose() {
            return exposureTicks / (double) requiredTicks;
        }

        /** 露光が終わった時に actionbar へ出す 1 行。 */
        public Component message() {
            if (band == Band.NORMAL) {
                return Component.translatable("message.old_glass_photograph.exposure.normal");
            }
            if (requiredTicks > PhotoCaptureController.MAX_EXPOSURE_TICKS) {
                return Component.translatable("message.old_glass_photograph.exposure.under_dark");
            }
            return Component.translatable("message.old_glass_photograph.exposure.under_short");
        }
    }

    /** ゲインを掛けた後の画像を測り、溜めた光の量で経路を決める。 */
    public static Result evaluate(byte[] mean, int exposureTicks, int light) {
        byte[] exposed = apply(mean, exposureTicks);
        long total = 0L;
        int clipped = 0;
        int crushed = 0;
        for (byte b : exposed) {
            int v = b & 0xFF;
            total += v;
            if (v >= CLIP_LEVEL) {
                clipped++;
            } else if (v <= CRUSH_LEVEL) {
                crushed++;
            }
        }
        int required = requiredTicks(light);
        Band band = exposureTicks >= required ? Band.NORMAL : Band.UNDER;
        return new Result(band, light, exposureTicks, required, gain(exposureTicks),
                total / (double) exposed.length,
                clipped * 100.0 / exposed.length, crushed * 100.0 / exposed.length);
    }
}

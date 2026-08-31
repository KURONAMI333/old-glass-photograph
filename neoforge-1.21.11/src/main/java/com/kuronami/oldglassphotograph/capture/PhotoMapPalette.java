package com.kuronami.oldglassphotograph.capture;

import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 8bit gray -> vanilla map の packed color byte への量子化。
 *
 * <p>パレットは <b>起動時に実際の {@link MapColor} を全走査して</b>組み立てる。
 * 定数表を焼き込まないので、バニラ側がパレットを変えても追随する。
 *
 * <p>採用条件は「厳密に無彩色（R=G=B）」であること。colorId 0 = {@code MapColor.NONE} は
 * 透明なので除外する。
 */
public final class PhotoMapPalette {

    private static final Logger LOG = LoggerFactory.getLogger("ogp");

    /** 無彩色と認める最大チャンネル差。0 = R=G=B のみ。 */
    private static final int NEUTRAL_SPREAD = 0;

    /** 輝度 0..255 -> packed map byte。 */
    private static final byte[] LUT = new byte[256];

    /** 実際に採用した階調（輝度の昇順・重複除去済み）。 */
    private static final int[] STEP_LUMINANCE;

    /** {@link #LUT} の gray 版。map 色 id ではなく、丸めた先の輝度そのものを返す。 */
    private static final byte[] GRAY_LUT = new byte[256];

    private static final byte[] STEP_PACKED;

    static {
        record Entry(int packed, int lum, int r, int g, int b, int spread) {
        }
        List<Entry> neutral = new ArrayList<>();
        // colorId 0 (NONE) は透明。1..63 を全走査する。
        for (int id = 1; id < 64; id++) {
            for (int brightness = 0; brightness < 4; brightness++) {
                int packed = (id << 2) | brightness;
                int argb = MapColor.getColorFromPackedId(packed);
                if ((argb >>> 24) == 0) {
                    continue; // 未定義 id は NONE にフォールバックして透明になる
                }
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int spread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                if (spread <= NEUTRAL_SPREAD) {
                    neutral.add(new Entry(packed, luminance(r, g, b), r, g, b, spread));
                }
            }
        }
        neutral.sort((a, b) -> Integer.compare(a.lum(), b.lum()));

        // 同一輝度は 1 段に潰す（画としての段数はここで決まる）
        List<Entry> steps = new ArrayList<>();
        for (Entry e : neutral) {
            if (steps.isEmpty() || steps.getLast().lum() != e.lum()) {
                steps.add(e);
            }
        }

        STEP_LUMINANCE = steps.stream().mapToInt(Entry::lum).toArray();
        STEP_PACKED = new byte[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            STEP_PACKED[i] = (byte) steps.get(i).packed();
        }

        for (int v = 0; v < 256; v++) {
            int best = 0;
            int bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < STEP_LUMINANCE.length; i++) {
                int d = Math.abs(STEP_LUMINANCE[i] - v);
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            LUT[v] = STEP_PACKED[best];
            GRAY_LUT[v] = (byte) STEP_LUMINANCE[best];
        }
    }

    private PhotoMapPalette() {
    }

    public static int luminance(int r, int g, int b) {
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    /** 使える白黒の段数（重複輝度を潰した後）。 */
    public static int stepCount() {
        return STEP_LUMINANCE.length;
    }

    public static int[] stepLuminance() {
        return STEP_LUMINANCE.clone();
    }

    public static byte quantize(int gray) {
        return LUT[Math.clamp(gray, 0, 255)];
    }

    /** gray 16,384 byte -> packed map color 16,384 byte。 */
    /**
     * {@link #quantizeAll} と同じ階段に丸めて、<b>輝度のまま</b>返す。
     *
     * <p>写真の保存を地図データから自前のコンポーネントへ移したので（{@code PhotoImage}）、
     * 出力は map 色 id である必要が無くなった。階調の見た目は {@link #quantizeAll} と同一。
     */
    public static byte[] quantizeAllToGray(byte[] gray) {
        byte[] out = new byte[gray.length];
        for (int i = 0; i < gray.length; i++) {
            out[i] = GRAY_LUT[gray[i] & 0xFF];
        }
        return out;
    }

    public static byte[] quantizeAll(byte[] gray) {
        byte[] out = new byte[gray.length];
        for (int i = 0; i < gray.length; i++) {
            out[i] = quantize(gray[i] & 0xFF);
        }
        return out;
    }

    /**
     * 未確認事項 (2) の実測ダンプ。全 64 colorId x 4 brightness を走査して
     * 無彩色として使える値を数え、閾値別の内訳をログへ出す。
     */
    public static void dumpPaletteSurvey() {
        int[] thresholds = {0, 2, 4, 8, 16, 32};
        int total = 0;
        StringBuilder neutralDetail = new StringBuilder();
        int[] countAt = new int[thresholds.length];
        int[] distinctAt = new int[thresholds.length];

        for (int t = 0; t < thresholds.length; t++) {
            List<Integer> lums = new ArrayList<>();
            for (int id = 1; id < 64; id++) {
                for (int brightness = 0; brightness < 4; brightness++) {
                    int packed = (id << 2) | brightness;
                    int argb = MapColor.getColorFromPackedId(packed);
                    if ((argb >>> 24) == 0) {
                        continue;
                    }
                    if (t == 0) {
                        total++;
                    }
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int spread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                    if (spread <= thresholds[t]) {
                        countAt[t]++;
                        int lum = luminance(r, g, b);
                        if (!lums.contains(lum)) {
                            lums.add(lum);
                        }
                        if (thresholds[t] == 0) {
                            neutralDetail.append(String.format("%n  packed=%3d rgb=(%3d,%3d,%3d) lum=%3d", packed, r, g, b, lum));
                        }
                    }
                }
            }
            distinctAt[t] = lums.size();
        }

        LOG.info("[ogp][palette] 不透明エントリ総数 (colorId 1..63 x brightness 4) = {}", total);
        for (int t = 0; t < thresholds.length; t++) {
            LOG.info("[ogp][palette] spread<={} : entries={} distinctLuminance={}",
                    thresholds[t], countAt[t], distinctAt[t]);
        }
        LOG.info("[ogp][palette] 採用した段数 (spread=0, 輝度重複を除去) = {} / 輝度 = {}",
                stepCount(), java.util.Arrays.toString(STEP_LUMINANCE));
        LOG.info("[ogp][palette] spread=0 の全エントリ:{}", neutralDetail);
    }
}

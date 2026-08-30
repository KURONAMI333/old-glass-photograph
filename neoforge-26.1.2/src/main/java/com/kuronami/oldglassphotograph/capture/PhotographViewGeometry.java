package com.kuronami.oldglassphotograph.capture;

/**
 * 写真をじっくり見る面の寸法。<b>Minecraft の型を 1 つも使わない純関数</b>で、
 * 「{@link #PHOTO_PX} 角の 1 画素が画面の何 px になるか」と「ケースの枠が画面に収まるか」を
 * ここ 1 箇所で決める（{@code MODJAM_DECISIONS_OGP.md} §32-5）。
 *
 * <h2>なぜ GUI px でなく render target の px で測るか</h2>
 *
 * 写真は {@link #PHOTO_PX} px なので、整数倍で置くと寸法はその倍数にしか跳べない。GUI 座標で測ると
 * この跳びが粗すぎて画面が余る。1920x1080 の既定（guiScale 4 = 480x270 GUI px）では
 * 2 倍が 256 GUI px で高さ 270 に枠ごと収まらず、<b>1 倍 = 高さの 47%</b> まで落ちる。
 *
 * <p>実画面の px で測れば同じ画面で 6 倍が取れる（写真 768 px = 高さの 71%）。
 * 1 ドットはきっちり 6 実 px で、<b>整数倍であることは GUI px で測った時より厳密になる</b>
 * （GUI px の 1 倍は実画面では 4 px だが、GUI 座標の丸めがその上に乗る）。
 *
 * <p>描く側は pose に {@code guiScaledWidth / renderTargetWidth} を掛けて
 * 「1 単位 = 実画面の 1 px」にしてから、ここが返す整数座標をそのまま使う。
 * 比を GUI scale の名目値でなく実測の商から作るので、画面幅が GUI scale で割り切れない時
 * （854 / 4）でも像の端が実 px の境界からずれない。
 *
 * <h2>整数倍と整数座標の両方が要る</h2>
 *
 * 倍率だけ整数にしても左上が半端な位置に来ると、最近傍で拾われるテクセルの幅が場所によって
 * {@code scale} px と {@code scale±1} px に割れて、写真のドットが不揃いに見える。
 * {@link #layout} は外枠の左上も整数除算で出す。
 *
 * <h2>枠は「収まるかどうか」の計算の内側に入れる</h2>
 *
 * 写真の大きさを先に決めてから枠を外へ描き足すと、解像度によって枠が画面外へ出る。
 * ここでは写真 {@link #PHOTO_PX} + 枠 {@link #FRAME_UNITS} x2 = {@link #TOTAL_UNITS} 単位を 1 つの矩形として扱い、
 * その矩形が画面に収まる最大の整数倍を選ぶ。撮影者と日付の行の高さも同じ計算に入れる。
 *
 * <p>検算は {@code main} が実寸で印字する（{@code java PhotographViewGeometry.java}）。
 */
public final class PhotographViewGeometry {

    /**
     * 写真の 1 辺（画素）。<b>解像度の正本はここ</b>で、{@code LatentImage.DIM} がこれを引く。
     *
     * <p>128 だったのは保存にバニラの地図データを使っていたから（地図は 128x128 固定）。
     * 保存を自前のコンポーネントへ移して上限が外れたので 256 にした（2026-08-31）。
     * 画面に占める大きさは変わらない（整数倍が 6 から 3 へ落ちるだけ）で、密度が 4 倍になる。
     */
    public static final int PHOTO_PX = 256;

    /** 木の枠の幅（写真 1 画素を 1 単位とする）。外から 面取り 1 / 地 2 / 内側の落ち 1。 */
    public static final int WOOD_UNITS = 4;

    /** 木と硝子の間の黒い決り（しゃくり）。実物のケースで硝子の縁に掛かる段。 */
    public static final int REBATE_UNITS = 1;

    /** 片側の枠の合計。 */
    public static final int FRAME_UNITS = WOOD_UNITS + REBATE_UNITS;

    /** 外枠 1 辺の単位数。 */
    public static final int TOTAL_UNITS = PHOTO_PX + FRAME_UNITS * 2;

    /**
     * 外枠が画面に占めてよい割合。
     *
     * <p>1.0 にすると倍率が上がった瞬間に枠が画面の端へ貼り付く（暗幕が見えなくなり、
     * ケースを暗幕の上に置いて見ている絵にならない）。四辺に 4% ずつ残す。
     */
    public static final double VIEW_FRAC = 0.92;

    /**
     * 撮影者・日付・実世界の日時を出す時に、外枠の下へ空ける高さ（<b>GUI px</b>）。
     *
     * <p>文字は暗幕の上に GUI の等倍で描く（実 px 単位へ落とすと文字が読めない大きさになる）ので、
     * ここだけ単位が GUI px。内訳は 枠との間 6 + 10 px の行 3 本 + 下の余白 2。行の高さは
     * {@code PhotographViewer.LINE_HEIGHT}（実際の描画間隔）に合わせてあり、古い写真
     * （3 行目の実世界日時を持たない）でも同じ高さを取り置く（写真ごとに枠の倍率が
     * 変わると並べて見た時に不揃いになるため）。
     */
    public static final int CREDIT_BLOCK_GUI = 36;

    private PhotographViewGeometry() {
    }

    /**
     * 画面に置くケースの位置と大きさ。<b>座標も寸法も render target の px</b>。
     *
     * @param scale    写真 1 画素の実 px。必ず 1 以上の整数
     * @param x        外枠の左端
     * @param y        外枠の上端
     * @param outer    外枠の 1 辺
     */
    public record Layout(int scale, int x, int y, int outer) {

        public int right() {
            return x + outer;
        }

        public int bottom() {
            return y + outer;
        }

        /** 木の枠の内側 = 黒い決りの外側。 */
        public int rebateX() {
            return x + WOOD_UNITS * scale;
        }

        public int rebateY() {
            return y + WOOD_UNITS * scale;
        }

        public int rebateSize() {
            return outer - WOOD_UNITS * scale * 2;
        }

        /** 写真そのものの左端。 */
        public int photoX() {
            return x + FRAME_UNITS * scale;
        }

        public int photoY() {
            return y + FRAME_UNITS * scale;
        }

        /** 写真の 1 辺。<b>常に {@link #PHOTO_PX} の整数倍</b>。 */
        public int photoSize() {
            return PHOTO_PX * scale;
        }

        /** 面取り（明部・陰部）の厚み。 */
        public int bevel() {
            return scale;
        }
    }

    /**
     * ケースの位置と写真の倍率を出す。
     *
     * @param targetWidth  render target の幅（実 px）
     * @param targetHeight render target の高さ（実 px）
     * @param guiScale     GUI の拡大率。撮影者の行に要る高さを実 px へ直すのに使う
     * @param hasCredit    撮影者と日付を出すか。出すならその高さを先に取り置く
     */
    public static Layout layout(int targetWidth, int targetHeight, double guiScale, boolean hasCredit) {
        int creditPx = hasCredit ? (int) Math.ceil(CREDIT_BLOCK_GUI * Math.max(1.0, guiScale)) : 0;
        // 縦は撮影者の行のぶんを先に引く。引かずに置いてから下へ書くと画面外へ出る。
        int usableHeight = Math.max(1, targetHeight - creditPx);
        int side = Math.min(targetWidth, usableHeight);
        int scale = Math.max(1, (int) (side * VIEW_FRAC) / TOTAL_UNITS);
        int outer = TOTAL_UNITS * scale;
        // 整数除算。左上を半端な位置に置くとドットの幅が不揃いになる。
        int x = (targetWidth - outer) / 2;
        // ケースと文字を 1 つの塊として縦に中央へ置く。
        int y = (targetHeight - (outer + creditPx)) / 2;
        return new Layout(scale, x, y, outer);
    }

    /** 検算。{@code java PhotographViewGeometry.java} で実寸を印字する。 */
    public static void main(String[] args) {
        int[][] cases = {
                {854, 480, 4}, {854, 480, 2}, {854, 480, 1},
                {1280, 720, 3}, {1366, 768, 3},
                {1920, 1080, 4}, {1920, 1080, 3}, {1920, 1080, 2},
                {2560, 1440, 4}, {3440, 1440, 4}, {3840, 2160, 6},
        };
        boolean ok = true;
        for (int[] c : cases) {
            for (boolean credit : new boolean[]{true, false}) {
                Layout l = layout(c[0], c[1], c[2], credit);
                int creditPx = credit ? (int) Math.ceil(CREDIT_BLOCK_GUI * (double) c[2]) : 0;
                boolean inside = l.x() >= 0 && l.y() >= 0
                        && l.right() <= c[0] && l.bottom() + creditPx <= c[1];
                boolean integral = l.photoSize() == PHOTO_PX * l.scale() && l.scale() >= 1;
                if (!inside || !integral) {
                    ok = false;
                }
                if (credit) {
                    System.out.printf(
                            "%4dx%-4d gs%d  scale=%2d outer=%4d photo=%4d at (%4d,%4d) "
                                    + "photo/height=%.1f%% case/short=%.1f%% inside=%s%n",
                            c[0], c[1], c[2], l.scale(), l.outer(), l.photoSize(), l.x(), l.y(),
                            100.0 * l.photoSize() / c[1],
                            100.0 * l.outer() / Math.min(c[0], c[1]), inside);
                } else if (!inside || !integral) {
                    System.out.printf("  (no-credit) NG %dx%d gs%d%n", c[0], c[1], c[2]);
                }
            }
        }
        System.out.println(ok ? "OK" : "NG: frame left the screen or scale was not integral");
    }
}

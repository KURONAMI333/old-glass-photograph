package com.kuronami.oldglassphotograph.capture;

/**
 * ファインダーの開口と、写真になる切り出しの幾何。<b>Minecraft の型を 1 つも使わない純関数</b>で、
 * 「覗いた構図」と「撮れる構図」が同じ矩形であることをここ 1 箇所で決める。
 *
 * <p>写真は 128x128 の正方形なので、撮影は毎フレームの render target から
 * <b>中央の正方形</b>（一辺 = min(幅, 高さ) × {@link #APERTURE_FRAC}）を切って作る。
 * ファインダーはその同じ正方形を開口として描き、外側を暗幕で塗り潰す。
 * <b>両方がこのクラスから出る</b>ので、描いた枠と撮れる範囲が食い違う経路が無い。
 *
 * <p>切り出しは render target のピクセル、開口は GUI のピクセルで測る。
 * GUI 座標は render target を {@code guiScale} で割ったもので原点が同じなので、
 * {@link #aperture} は {@link #crop} を {@code guiScale} で割っただけになる。
 * 丸めで最大 1 GUI px ずれる（実害は無いが、一致は「同一」でなく「1px 以内」である）。
 */
public final class ViewfinderGeometry {

    /**
     * 正方形の矩形。
     *
     * @param x    左端
     * @param y    上端
     * @param side 一辺
     */
    public record Square(int x, int y, int side) {

        public int right() {
            return x + side;
        }

        public int bottom() {
            return y + side;
        }
    }

    /**
     * 開口の一辺が画面の短辺に占める割合。
     *
     * <p>1.0 だと開口が短辺いっぱいになり、<b>その辺には枠を置く余地が無くなる</b>
     * （16:9 では木の枠が左右にしか出ず、上下は画面外へ切れていた）。
     * 短辺の {@code (1 - APERTURE_FRAC) / 2} = 10% を四辺の余白として空け、
     * そこへ {@link #FRAME_PAD_FRAC} 分の枠と、その外側の暗幕を置く。
     *
     * <p>代償として写真の画角が狭くなる（垂直 70&deg; の中央 80% ＝ 約 59&deg;）。
     * 覗いた構図と撮れる構図は {@link #crop} と {@link #aperture} が同じ値から出るので、
     * 縮めても一致は保たれる。
     */
    public static final double APERTURE_FRAC = 0.80;

    /**
     * ファインダーの枠を開口の外へ張り出させる量（開口の一辺に対する比）。
     *
     * <p>枠の絵（{@code viewfinder.png}）は「開口 + 四辺にこの量」の矩形へ貼る。
     * テクスチャ側の不透明な枠は外周の {@code BAND + REBATE_W} = 半幅の 12.4% を占めるので、
     * 不透明部の内縁は開口の外側 {@code (0.5 + F)(1 - 0.124) - 0.5} = <b>一辺の約 3.0%</b> に来る。
     * 枠は回転に一拍遅れて最大 5 GUI px 動くので、開口の一辺が 171 GUI px
     * （854x480 を guiScale 4 で見た最小の実用値）でもその余裕が 5.1 px 残る。
     *
     * <p>張り出し 10.5% は余白 10% よりわずかに大きいが、枠の外側 2 割弱は透明なので
     * 実際に見える不透明部は余白に収まる。値の検算は {@code scripts/generate_viewfinder.py}
     * の {@code check()} が実寸で印字する（<b>両者の定数は同じ値でなければならない</b>）。
     */
    public static final double FRAME_PAD_FRAC = 0.105;

    private ViewfinderGeometry() {
    }

    /**
     * 写真になる切り出し（render target のピクセル）。
     *
     * @param width  render target の幅
     * @param height render target の高さ
     */
    public static Square crop(int width, int height) {
        int side = Math.max(1, (int) Math.round(Math.min(width, height) * APERTURE_FRAC));
        return new Square((width - side) / 2, (height - side) / 2, side);
    }

    /**
     * 枠の絵を開口の外へ張り出させる量（GUI のピクセル）。
     *
     * @param apertureSide {@link #aperture} の一辺
     * @param minPad       これ以下にはしない（枠の遅れの最大量。窓が極端に小さい時の下限）
     */
    public static int framePad(int apertureSide, int minPad) {
        return Math.max(minPad, (int) Math.round(apertureSide * FRAME_PAD_FRAC));
    }

    /**
     * ファインダーの開口（GUI のピクセル）。{@link #crop} と同じ矩形を GUI 座標へ写したもの。
     *
     * @param width    render target の幅
     * @param height   render target の高さ
     * @param guiScale {@code Window#getGuiScale}
     */
    public static Square aperture(int width, int height, double guiScale) {
        Square c = crop(width, height);
        double s = guiScale <= 0.0 ? 1.0 : guiScale;
        return new Square(
                (int) Math.round(c.x() / s),
                (int) Math.round(c.y() / s),
                (int) Math.round(c.side() / s));
    }
}

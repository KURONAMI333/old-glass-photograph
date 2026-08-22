package com.kuronami.oldglassphotograph.capture;

/**
 * ファインダーの開口と、写真になる切り出しの幾何。<b>Minecraft の型を 1 つも使わない純関数</b>で、
 * 「覗いた構図」と「撮れる構図」が同じ矩形であることをここ 1 箇所で決める。
 *
 * <p>写真は 128x128 の正方形なので、撮影は毎フレームの render target から
 * <b>中央の正方形</b>（一辺 = min(幅, 高さ)）を切って作る。ファインダーはその同じ正方形を
 * 開口として描き、外側を暗幕で塗り潰す。<b>両方がこのクラスから出る</b>ので、
 * 描いた枠と撮れる範囲が食い違う経路が無い。
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

    private ViewfinderGeometry() {
    }

    /**
     * 写真になる切り出し（render target のピクセル）。
     *
     * @param width  render target の幅
     * @param height render target の高さ
     */
    public static Square crop(int width, int height) {
        int side = Math.min(width, height);
        return new Square((width - side) / 2, (height - side) / 2, side);
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

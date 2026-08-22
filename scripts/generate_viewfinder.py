#!/usr/bin/env python3
"""ファインダーに重ねる 1 枚（すりガラスの面・縁・四隅の落ち）を焼く。

湿板カメラの背面はマホガニーの枠にすりガラスが落とし込まれていて、木と硝子の間に
黒い決り（しゃくり）の段が 1 本入る。撮影者は暗幕を被ってその面を覗く。
このスクリプトはその面だけを描く（暗幕＝開口の外は Java 側が単色で塗る）。

色は実物写真から採った実測値:
`_research/camera_refs/photos/smg_mahogany_wetplate.jpg`
（Science Museum Group、湿板期のスライディングボックス型。すりガラス背面が写っている）

    枠の明部 (171,101,39) / 中間 (136,81,42) (129,56,3) / 陰 (88,39,0)
    黒い決り (41,41,41) / 真鍮金具 (134,118,59)
    すりガラスの面 (120,103,82)  ← x700-910 y850-1110 の低彩度画素 6842 点の平均。
                                  同区画の輝度 sd は 23（平均 106 の 21.7%）

暗幕の下なので、そのままでは明るすぎる。上の値を一律 0.74 倍して使う
（暗幕の中には直射が入らない）。

--- テクスチャと開口の関係（Java 側と揃える）---

このテクスチャは「開口 + 四辺の張り出し」に貼られる（`ViewfinderGeometry.framePad`）。
したがって開口はテクスチャの内側 `AP = 0.5 / (0.5 + FRAME_PAD_FRAC)` までで、
枠はその外側に載る。不変条件は 2 つある:

1. **枠の不透明部が開口へ食い込まないこと。** 枠は回転に一拍遅れて動く
   （`FRAME_DRIFT_MAX` = 5 GUI px）ので、その分の余裕を残す。
2. **すりガラスの面が開口の内側を余さず覆うこと。** 面を `AP` ちょうどで切ると、
   遅れて動いた分だけ開口の縁に素の描画が出る（枠の際に細い筋として見える）。
   面は開口の外へはみ出させ、不透明部の内縁まで塗る。はみ出した分は暗幕か枠の下に隠れる。
   実物でも硝子板は決りの下まで続いているので、絵としても正しい向きの逸脱ではない。

main() が実寸で検算して印字する。

出力: neoforge/src/main/resources/assets/old_glass_photograph/textures/gui/viewfinder.png
"""

from __future__ import annotations

import pathlib
import random

from PIL import Image

SIZE = 512

# --- 実測値（上の写真からのサンプル）と、暗幕の下へ落とす係数 ---
SHADE = 0.74


def dim(rgb: tuple[int, int, int], k: float = SHADE) -> tuple[int, int, int]:
    return tuple(max(0, min(255, int(round(c * k)))) for c in rgb)  # type: ignore[return-value]


WOOD_LIT = dim((171, 101, 39))
WOOD_MID = dim((136, 81, 42))
WOOD_DEEP = dim((129, 56, 3))
WOOD_SHADOW = dim((88, 39, 0))
REBATE = dim((41, 41, 41), 0.42)

# --- すりガラスの面 ---
# 実測の面色 (120,103,82) を暗幕の下へ落とすと (89,76,61)。ただしこれをそのまま
# 灰幕にすると画面全体が茶色く転ぶ。写真は白黒なので、色味を自分の輝度へ半分だけ
# 寄せて（＝暖色を半分だけ抜いて）使う。実物からの意図的な逸脱。
_GLASS = dim((120, 103, 82))
_GLASS_LUM = int(round((_GLASS[0] * 299 + _GLASS[1] * 587 + _GLASS[2] * 114) / 1000))
_HAZE_BASE = tuple((c + _GLASS_LUM) / 2 for c in _GLASS)

# 面のフレア。すりガラスは像を透かすだけでなく散らすので、暗幕の中にも
# 面全体から一様な散乱光が返ってくる。博物館の写真は「像が結んでいない状態」の
# 面色なのでこの分が乗っておらず、実測値そのままでは暗すぎる。
#
# **この値と HAZE_ALPHA の 2 つで「暗い層 + 灰色の層」を 1 枚に畳んでいる。**
# 合成は out = (1 - a) * 世界 + a * 灰 のアフィン写像で、傾き (1 - a) が暗い層、
# 切片 a * 灰 が灰色の層にあたる。彩度の落ち方は切片/傾きの比で決まり、
# 明るさの変化は傾きと切片の差で決まるので、2 つを別々に置けば
# **明るさを動かさずに彩度だけを落とせる**（alpha だけを上げると暗くなる）。
HAZE_FLARE = 1.3
HAZE_COLOR = tuple(min(255, int(round(c * HAZE_FLARE))) for c in _HAZE_BASE)

# 艶消しの面が像を散らす量。
#
# 実測（開口の平坦部・実プレイのスクショ 4 枚から灰幕を逆算して当て直したもの）:
#   彩度 -15.6% 〜 -31.6%（a=0.20 比）／ 明るさ -5.3% 〜 +19.3%
# 中庸の灰 128 は 119 -> 121 でほぼ動かず、明部が下がり暗部が持ち上がる圧縮になる。
HAZE_ALPHA = 0.35

# 面のむら。実測の輝度 sd は平均の 21.7% だが、その相当部分は
# 「像が結んでいない硝子の向こうに木地が透けている」分（博物館写真の事情）で、
# 使用中のすりガラスの持ちむらではない。半分だけ採る。
#
# **ここは HAZE_ALPHA に乗算で効く**（a_haze = HAZE_ALPHA * (1 + ...)）ので、
# 目に見える振れは HAZE_ALPHA * この値で決まる。振れの絶対量を
# alpha 0.20 の頃と同じ 0.022 / 0.012 に保つ値を置く。
HAZE_MOTTLE = 0.063  # 低周波（研磨むら）。0.35 * 0.063 = 0.022
HAZE_GRAIN = 0.034  # 高周波（粒状）。0.35 * 0.034 = 0.012
MOTTLE_CELL = 8  # 低周波の 1 マス（テクセル）
GRAIN_CELL = 2  # 高周波の 1 マス（テクセル）

# 四隅の落ち。ピリオドのレンズは像円が四隅まで届かず、角が沈む。
FALLOFF_COLOR = (16, 13, 11)
# 上限 alpha。**面の灰幕の上に載る**ので、単独の値ではなく player が見る
# 「隅と中央の落差」で見る。落ちは灰幕を通した後の像を一定率で減光する役なので、
# **この値を動かさないことが落差を保つ手**になる（灰幕をどう変えても落差は変わらない）。
# 中庸の灰 128 に対する隅/中央は 0.325 で 0.712（実機検証済み）。
FALLOFF_MAX = 0.325
# 合成後 alpha の頭打ち。むらの振れで四隅が受理済みの濃さを超えないようにする。
# 余裕は 0.03。合成後の素の値は FALLOFF_MAX + HAZE_ALPHA(1 - FALLOFF_MAX)。
ALPHA_CAP = FALLOFF_MAX + HAZE_ALPHA * (1.0 - FALLOFF_MAX) + 0.03
FALLOFF_START = 0.80  # ここから落ち始める（開口の半幅を 1 とした超楕円距離）
FALLOFF_END = 1.335  # 角。n=2.4 の超楕円で nx=ny=1 のとき 2^(1/2.4)=1.335
FALLOFF_N = 2.4  # 2 に近いほど「辺は素直・角だけ沈む」＝像円の落ち方になる

# 枠の寸法（テクスチャの半幅に対する比）。開口の外側だけを使う。
BAND = 0.100  # マホガニーの枠
REBATE_W = 0.024  # 黒い決り
# 角金具は置かない。上の写真の背面枠を拡大すると、四隅は素の留め継ぎで、
# 金物は縁に付いた黒い掛け金 1 つだけ。真鍮は前面のレンズ鏡胴にしか無い。

# Java 側と揃える 1 つの値。`ViewfinderGeometry.FRAME_PAD_FRAC` と同じでなければならない。
FRAME_PAD_FRAC = 0.105
# 開口がテクスチャの半幅に占める割合。
AP = 0.5 / (0.5 + FRAME_PAD_FRAC)


def smoothstep(t: float) -> float:
    t = max(0.0, min(1.0, t))
    return t * t * (3.0 - 2.0 * t)


def over(
    top: tuple[tuple[int, int, int], float], bottom: tuple[tuple[int, int, int], float]
) -> tuple[tuple[int, int, int], float]:
    """top を bottom の上に載せる（source-over）。"""
    ct, at = top
    cb, ab = bottom
    a = at + ab * (1.0 - at)
    if a <= 0.0:
        return (0, 0, 0), 0.0
    c = tuple((ct[i] * at + cb[i] * ab * (1.0 - at)) / a for i in range(3))
    return tuple(int(round(v)) for v in c), a  # type: ignore[return-value]


def noise_field(rng: random.Random, cell: int) -> list[list[float]]:
    n = (SIZE + cell - 1) // cell
    return [[rng.uniform(-1.0, 1.0) for _ in range(n)] for _ in range(n)]


def build() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()
    # 半幅はテクセルの端で測る（SIZE/2）。テクセルの中心で測る (SIZE-1)/2 にすると
    # 半テクセル分だけ内側にずれ、貼った先で開口の縁に 0.5 px の素の描画が残る。
    c = SIZE / 2.0
    rng = random.Random(20260822)
    mottle = noise_field(rng, MOTTLE_CELL)
    grain = noise_field(rng, GRAIN_CELL)

    wood_in = 1.0 - BAND
    rebate_in = wood_in - REBATE_W

    for y in range(SIZE):
        ny = abs(y + 0.5 - c) / c
        for x in range(SIZE):
            nx = abs(x + 0.5 - c) / c
            m = max(nx, ny)  # 正方形の等高線（枠はこれで切る）

            if m >= rebate_in:
                if m >= wood_in:
                    # マホガニーの枠。光は左上から。段の底（内寄り）を暗く落として厚みを出す。
                    on_dark_side = (x > c and nx >= ny) or (y > c and ny > nx)
                    step = (m - wood_in) / BAND  # 0=内寄り 1=外周
                    if step < 0.22:
                        tone = WOOD_SHADOW
                    elif on_dark_side:
                        tone = WOOD_DEEP if step < 0.7 else WOOD_MID
                    else:
                        tone = WOOD_MID if step < 0.55 else WOOD_LIT
                    # 木目。1 段だけ振る（階調を増やさない）。
                    if rng.random() < 0.10:
                        tone = WOOD_DEEP if tone is WOOD_MID else tone
                    px[x, y] = (*tone, 255)
                else:
                    px[x, y] = (*REBATE, 255)
                continue

            # --- 開口の中と、その外の「のりしろ」。座標を開口の半幅で測り直す ---
            # ここは枠の内側（m < rebate_in）が全部通る。ax/ay が 1 を超える帯は
            # 開口の外だが、そこも塗らないと枠が遅れて動いた時に素の描画が出る。
            # はみ出した分は暗幕の上に乗るだけで、四隅の落ちが飽和しているので暗いまま。
            ax = nx / AP
            ay = ny / AP

            # すりガラスの面。研磨むらと粒状で alpha を振る。
            n_lo = mottle[y // MOTTLE_CELL][x // MOTTLE_CELL]
            n_hi = grain[y // GRAIN_CELL][x // GRAIN_CELL]
            a_haze = HAZE_ALPHA * (1.0 + n_lo * HAZE_MOTTLE + n_hi * HAZE_GRAIN)
            a_haze = max(0.0, min(1.0, a_haze))
            layer = (HAZE_COLOR, a_haze)

            # 四隅の落ちをその上に載せる。
            d = (ax**FALLOFF_N + ay**FALLOFF_N) ** (1.0 / FALLOFF_N)
            if d > FALLOFF_START:
                t = smoothstep((d - FALLOFF_START) / (FALLOFF_END - FALLOFF_START))
                a_fall = max(0.0, min(1.0, FALLOFF_MAX * t * (1.0 + n_lo * 0.12)))
                layer = over((FALLOFF_COLOR, a_fall), layer)

            tone, alpha = layer
            a8 = max(0, min(255, int(round(min(alpha, ALPHA_CAP) * 255))))
            if a8 > 0:
                px[x, y] = (*tone, a8)

    return img


def check(aperture_side: float, label: str, drift_max: float = 5.0) -> None:
    """実寸（GUI px）で不変条件を検算して印字する。"""
    pad = aperture_side * FRAME_PAD_FRAC
    blit = aperture_side + 2 * pad
    half = blit / 2.0
    opaque_in = (1.0 - BAND - REBATE_W) * half  # 中心から不透明部の内縁まで
    wood_in = (1.0 - BAND) * half
    clearance = opaque_in - aperture_side / 2.0
    # 面は不透明部の内縁まで塗ってあるので、開口の外へのはみ出しは clearance と同じ。
    overlap = clearance
    print(
        f"  [{label}] 開口={aperture_side:.0f}  張り出し={pad:.1f}"
        f"  木枠の見え幅={wood_in - opaque_in + (half - wood_in):.1f}"
        f"  不透明部の内縁の余裕={clearance:.1f} (>= drift {drift_max}: "
        f"{'OK' if clearance >= drift_max else 'NG'})"
        f"  面のはみ出し={overlap:.1f} (>= drift {drift_max}: "
        f"{'OK' if overlap >= drift_max else 'NG'})"
    )


def main() -> None:
    out = (
        pathlib.Path(__file__).resolve().parents[1]
        / "neoforge/src/main/resources/assets/old_glass_photograph/textures/gui/viewfinder.png"
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    img = build()
    img.save(out)
    colors = img.convert("RGBA").getcolors(maxcolors=1 << 20)
    opaque = [c for c in colors if c[1][3] == 255]
    print(f"wrote {out} ({img.size[0]}x{img.size[1]})")
    print(f"  opaque tones: {len(opaque)}  total entries: {len(colors)}")
    print(
        f"  band={BAND} rebate={REBATE_W} pad_frac={FRAME_PAD_FRAC} aperture_in_tex={AP:.4f}"
    )
    print(
        f"  haze={HAZE_COLOR} alpha={HAZE_ALPHA} mottle={HAZE_MOTTLE} grain={HAZE_GRAIN}"
    )
    # 短辺 x APERTURE_FRAC / guiScale が開口の一辺（GUI px）。
    check(1080 * 0.80 / 3, "1920x1080 s3")
    check(1440 * 0.80 / 2, "2560x1440 s2")
    check(1080 * 0.80 / 4, "1080x1920 s4 (portrait)")
    check(854 * 0.80 / 4, "854x480 s4 (最小窓)")


if __name__ == "__main__":
    main()

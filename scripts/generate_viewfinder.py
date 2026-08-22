#!/usr/bin/env python3
"""ファインダーに重ねる 1 枚（すりガラスの縁と四隅の落ち）を焼く。

湿板カメラの背面はマホガニーの枠にすりガラスが落とし込まれていて、木と硝子の間に
黒い決り（しゃくり）の段が 1 本入る。撮影者は暗幕を被ってその面を覗く。
このスクリプトはその面だけを描く（暗幕＝開口の外は Java 側が単色で塗る）。

色は実物写真から採った実測値:
`_research/camera_refs/photos/smg_mahogany_wetplate.jpg`
（Science Museum Group、湿板期のスライディングボックス型。すりガラス背面が写っている）

    枠の明部 (171,101,39) / 中間 (136,81,42) (129,56,3) / 陰 (88,39,0)
    黒い決り (41,41,41) / 真鍮金具 (134,118,59)

暗幕の下なので、そのままでは明るすぎる。上の値を一律 0.74 倍して使う
（暗幕の中には直射が入らない）。

出力: neoforge/src/main/resources/assets/old_glass_photograph/textures/gui/viewfinder.png
"""

from __future__ import annotations

import math
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
BRASS = dim((134, 118, 59))
BRASS_LIT = dim((134, 118, 59), 1.05)

# 四隅の落ち。ピリオドのレンズは像円が四隅まで届かず、角が沈む。
FALLOFF_COLOR = (16, 13, 11)
FALLOFF_MAX = 0.46  # 上限 alpha（浅く保つ。深くするとシェーダ環境で輪郭が出る）
FALLOFF_START = 0.80  # ここから落ち始める（超楕円距離）
FALLOFF_END = 1.335  # 角。n=2.4 の超楕円で nx=ny=1 のとき 2^(1/2.4)=1.335
FALLOFF_N = 2.4  # 2 に近いほど「辺は素直・角だけ沈む」＝像円の落ち方になる

# 枠の寸法（半幅に対する比）。開口の内側を食う量そのものなので大きくしない。
BAND = 0.030  # マホガニーの枠
REBATE_W = 0.013  # 黒い決り
BRASS_LEN = 0.16  # 角金具の長さ（辺方向）


def smoothstep(t: float) -> float:
    t = max(0.0, min(1.0, t))
    return t * t * (3.0 - 2.0 * t)


def build() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()
    c = (SIZE - 1) / 2.0
    rng = random.Random(20260822)

    wood_in = 1.0 - BAND
    rebate_in = wood_in - REBATE_W

    for y in range(SIZE):
        ny = abs(y - c) / c
        for x in range(SIZE):
            nx = abs(x - c) / c
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
                    # 角の真鍮金具
                    if nx > 1.0 - BRASS_LEN and ny > 1.0 - BRASS_LEN:
                        tone = BRASS_LIT if step > 0.55 else BRASS
                    px[x, y] = (*tone, 255)
                else:
                    px[x, y] = (*REBATE, 255)
                continue

            # 開口の中。四隅だけが落ちる。
            d = (nx**FALLOFF_N + ny**FALLOFF_N) ** (1.0 / FALLOFF_N)
            if d <= FALLOFF_START:
                continue
            t = smoothstep((d - FALLOFF_START) / (FALLOFF_END - FALLOFF_START))
            a = FALLOFF_MAX * t
            # すりガラスのむら。alpha を ±6% だけ揺らす。
            a *= 1.0 + (rng.random() - 0.5) * 0.12
            alpha = max(0, min(255, int(round(a * 255))))
            if alpha > 0:
                px[x, y] = (*FALLOFF_COLOR, alpha)

    return img


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
    print(f"  band={BAND} rebate={REBATE_W} falloff_max={FALLOFF_MAX}")


if __name__ == "__main__":
    main()

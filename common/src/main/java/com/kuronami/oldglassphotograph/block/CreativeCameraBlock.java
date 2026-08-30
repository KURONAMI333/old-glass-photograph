package com.kuronami.oldglassphotograph.block;

/**
 * 撮影用のカメラ。<b>出荷物には入らない</b>（{@code dev/creative-camera} ブランチにしか無い）。
 *
 * <p>通常の {@link WetPlateCameraBlock} をそのまま継ぐので、モデルも当たり判定も覗きの立ち位置も
 * 同じものが効く。違うのは、この型であることを見て {@code PhotoCaptureController} が
 * 「板が無ければその場で用意する」「露光を一瞬で終える」「撮った時点で写真にする」の3つを
 * 切り替える点だけ。<b>通常のカメラの挙動には一切触っていない</b>ので、同じワールドに両方置ける。
 */
public class CreativeCameraBlock extends WetPlateCameraBlock {

    public CreativeCameraBlock(Properties properties) {
        super(properties);
    }
}

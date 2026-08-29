package com.kuronami.oldglassphotograph.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * client -> server。ファインダーを覗いている player がもう一度クリックした
 * ＝<b>シャッターを開けたい</b>（{@code MODJAM_DECISIONS_OGP.md} §31）。
 *
 * <p>判定は全部 server が持つ。装填状態も乾燥期限も覗いている間に変わりうるので、
 * <b>覗いた時点ではなくここで</b>検査してから token を発行する。
 *
 * <p>1.20.1 には {@code CustomPacketPayload}/{@code StreamCodec} が無いため、
 * 26.x の payload record を write/read の raw ByteBuf 直列化へ写したもの
 * （フィールド順・型は StreamCodec 本体と同じ。mod-076 fabric-1.20.1 方式）。
 *
 * @param cameraPos カメラ BlockEntity の位置（下半分）。server は距離とブロックの実在を検査する
 */
public record ShutterRequestPayload(BlockPos cameraPos) {

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(cameraPos);
    }

    public static ShutterRequestPayload read(FriendlyByteBuf buf) {
        return new ShutterRequestPayload(buf.readBlockPos());
    }
}

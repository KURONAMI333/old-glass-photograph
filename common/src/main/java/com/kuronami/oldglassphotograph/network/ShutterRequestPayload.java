package com.kuronami.oldglassphotograph.network;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * client -> server。ファインダーを覗いている player がもう一度クリックした
 * ＝<b>シャッターを開けたい</b>（{@code MODJAM_DECISIONS_OGP.md} §31）。
 *
 * <p>判定は全部 server が持つ。装填状態も乾燥期限も覗いている間に変わりうるので、
 * <b>覗いた時点ではなくここで</b>検査してから token を発行する。
 *
 * @param cameraPos カメラ BlockEntity の位置（下半分）。server は距離とブロックの実在を検査する
 */
public record ShutterRequestPayload(BlockPos cameraPos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShutterRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "shutter_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShutterRequestPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> BlockPos.STREAM_CODEC.encode(buf, p.cameraPos()),
                    buf -> new ShutterRequestPayload(BlockPos.STREAM_CODEC.decode(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

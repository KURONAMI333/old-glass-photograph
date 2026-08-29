package com.kuronami.oldglassphotograph.network;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.capture.ViewfinderReading;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * server -> client。カメラを覗く（ファインダーに入る）。<b>ここではまだ露光しない。</b>
 *
 * <p>覗きは何秒でも続く。露光が始まるのは player がもう一度クリックして
 * {@link ShutterRequestPayload} を投げ、server が {@link ShutterOpenPayload} を返した時
 * （{@code MODJAM_DECISIONS_OGP.md} §31）。
 *
 * @param basePos カメラ BlockEntity の位置（下半分）。シャッター要求で server へ返す
 * @param lensPos 撮影原点（上半分＝レンズ）。client のカメラ実体をここへ置く
 * @param reading 覗いている間に描く光の読み。撮れない状態ならその理由が入る
 */
public record ViewfinderOpenPayload(BlockPos basePos, BlockPos lensPos, float yaw, float pitch,
                                    ViewfinderReading reading)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ViewfinderOpenPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "viewfinder_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ViewfinderOpenPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        BlockPos.STREAM_CODEC.encode(buf, p.basePos());
                        BlockPos.STREAM_CODEC.encode(buf, p.lensPos());
                        buf.writeFloat(p.yaw());
                        buf.writeFloat(p.pitch());
                        ByteBufCodecs.VAR_INT.encode(buf, p.reading().ordinal());
                    },
                    buf -> new ViewfinderOpenPayload(
                            BlockPos.STREAM_CODEC.decode(buf),
                            BlockPos.STREAM_CODEC.decode(buf),
                            buf.readFloat(),
                            buf.readFloat(),
                            ViewfinderReading.fromOrdinal(ByteBufCodecs.VAR_INT.decode(buf))));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

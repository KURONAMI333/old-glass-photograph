package com.kuronami.oldglassphotograph.network;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * server -> client。認可済みの capture token と構図（設置 Camera の位置・向き）を渡し、
 * 露光を 1 回だけ走らせる。
 *
 * <p>露光は「client が窓のあいだ複数フレームを撮って輝度を平均する」形で走る。
 * server は窓の上限と撮影間隔だけを渡し、実際に何 tick 露光したかは client が返す。
 *
 * @param settleTicks    カメラを移してから露光を始めるまでの待ち tick（構図の lerp を落ち着かせる）
 * @param maxExposeTicks 露光窓の上限 tick。ここに達したら押しっぱなしでも打ち切る
 * @param intervalTicks  撮影の間隔 tick。1 なら毎 tick（実際は毎フレーム 1 枚まで）
 */
public record PhotoCaptureRequestPayload(int token, BlockPos pos, float yaw, float pitch, int settleTicks,
                                         int maxExposeTicks, int intervalTicks)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PhotoCaptureRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "photo_capture_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhotoCaptureRequestPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        ByteBufCodecs.VAR_INT.encode(buf, p.token());
                        BlockPos.STREAM_CODEC.encode(buf, p.pos());
                        buf.writeFloat(p.yaw());
                        buf.writeFloat(p.pitch());
                        ByteBufCodecs.VAR_INT.encode(buf, p.settleTicks());
                        ByteBufCodecs.VAR_INT.encode(buf, p.maxExposeTicks());
                        ByteBufCodecs.VAR_INT.encode(buf, p.intervalTicks());
                    },
                    buf -> new PhotoCaptureRequestPayload(
                            ByteBufCodecs.VAR_INT.decode(buf),
                            BlockPos.STREAM_CODEC.decode(buf),
                            buf.readFloat(),
                            buf.readFloat(),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

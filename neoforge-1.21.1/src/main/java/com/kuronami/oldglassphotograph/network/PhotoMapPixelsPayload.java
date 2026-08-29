package com.kuronami.oldglassphotograph.network;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.component.LatentImage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * client -> server。capture token と、露光窓のあいだに撮った複数フレームの
 * <b>輝度平均</b> 128x128 の 8bit gray（16,384 byte 固定長）を返す。
 *
 * @param exposureTicks 実際に露光した tick 数。適正 80 との比が現像時のゲインになる
 * @param frames        平均に使った枚数。動体がどれだけ薄まるかを決める
 */
public record PhotoMapPixelsPayload(int token, int exposureTicks, int frames, byte[] gray)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PhotoMapPixelsPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(OldGlassPhotograph.MODID, "photo_map_pixels"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhotoMapPixelsPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        ByteBufCodecs.VAR_INT.encode(buf, payload.token());
                        ByteBufCodecs.VAR_INT.encode(buf, payload.exposureTicks());
                        ByteBufCodecs.VAR_INT.encode(buf, payload.frames());
                        buf.writeBytes(payload.gray());
                    },
                    buf -> {
                        int token = ByteBufCodecs.VAR_INT.decode(buf);
                        int ticks = ByteBufCodecs.VAR_INT.decode(buf);
                        int frames = ByteBufCodecs.VAR_INT.decode(buf);
                        byte[] gray = new byte[LatentImage.SIZE];
                        buf.readBytes(gray);
                        return new PhotoMapPixelsPayload(token, ticks, frames, gray);
                    });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

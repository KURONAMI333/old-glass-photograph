package com.kuronami.oldglassphotograph.network;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * client -> server。露光が成立しなかったことを返す。
 *
 * <p>これが無いと、押し間違いで離した player は {@code CAPTURE_TIMEOUT_TICKS}（400 tick = 20 秒）
 * のあいだ「Exposure already in progress.」を見続けることになる。
 *
 * @param frames 露光を中止した時点で撮れていた枚数。0 ならまだ静定中だった
 * @param reason {@link #REASON_PEEK} なら覗いただけ（失敗ではないので何も言わない）。
 *               {@link #REASON_TOO_SHORT} なら露光が始まった後で規定枚数に満たずに離した
 */
public record PhotoCaptureAbortPayload(int token, int ticks, int frames, int reason)
        implements CustomPacketPayload {

    /** 露光が始まる前に離した = ファインダーを覗いただけ。 */
    public static final int REASON_PEEK = 0;

    /** 露光は始まったが規定枚数に満たないうちに離した。 */
    public static final int REASON_TOO_SHORT = 1;

    public static final CustomPacketPayload.Type<PhotoCaptureAbortPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "photo_capture_abort"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhotoCaptureAbortPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        ByteBufCodecs.VAR_INT.encode(buf, p.token());
                        ByteBufCodecs.VAR_INT.encode(buf, p.ticks());
                        ByteBufCodecs.VAR_INT.encode(buf, p.frames());
                        ByteBufCodecs.VAR_INT.encode(buf, p.reason());
                    },
                    buf -> new PhotoCaptureAbortPayload(
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

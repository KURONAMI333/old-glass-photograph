package com.kuronami.oldglassphotograph.network;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * client -> server。露光が写真にならずに終わったことを返す。
 *
 * <p>これが無いと、露光をやめた player は {@code WetPlateCameraBlockEntity.CAPTURE_TIMEOUT_TICKS}
 * のあいだ「Exposure already in progress.」を見続けることになる。
 *
 * @param frames 露光をやめた時点で撮れていた枚数
 * @param reason {@link #REASON_LEFT} なら撮らずにファインダーから出た（失敗ではないので何も言わない）。
 *               {@link #REASON_TOO_SHORT} ならシャッターが開いた直後に閉じて 1 枚も像が乗らなかった
 */
public record PhotoCaptureAbortPayload(int token, int ticks, int frames, int reason)
        implements CustomPacketPayload {

    /** 撮らずにファインダーから出た（シャッターを開けていない・強制復帰も含む）。 */
    public static final int REASON_LEFT = 0;

    /** シャッターは開いたが、規定枚数に満たないうちに閉じた。板は消費しない。 */
    public static final int REASON_TOO_SHORT = 1;

    public static final CustomPacketPayload.Type<PhotoCaptureAbortPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(OldGlassPhotograph.MODID, "photo_capture_abort"));

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

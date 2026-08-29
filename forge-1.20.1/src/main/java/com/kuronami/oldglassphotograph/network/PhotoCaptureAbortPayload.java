package com.kuronami.oldglassphotograph.network;

import net.minecraft.network.FriendlyByteBuf;

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
public record PhotoCaptureAbortPayload(int token, int ticks, int frames, int reason) {

    /** 撮らずにファインダーから出た（シャッターを開けていない・強制復帰も含む）。 */
    public static final int REASON_LEFT = 0;

    /** シャッターは開いたが、規定枚数に満たないうちに閉じた。板は消費しない。 */
    public static final int REASON_TOO_SHORT = 1;

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(token);
        buf.writeVarInt(ticks);
        buf.writeVarInt(frames);
        buf.writeVarInt(reason);
    }

    public static PhotoCaptureAbortPayload read(FriendlyByteBuf buf) {
        return new PhotoCaptureAbortPayload(
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }
}

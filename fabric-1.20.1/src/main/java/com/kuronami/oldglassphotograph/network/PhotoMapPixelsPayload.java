package com.kuronami.oldglassphotograph.network;

import com.kuronami.oldglassphotograph.component.LatentImage;
import net.minecraft.network.FriendlyByteBuf;

/**
 * client -> server。capture token と、露光窓のあいだに撮った複数フレームの
 * <b>輝度平均</b> 128x128 の 8bit gray（16,384 byte 固定長）を返す。
 *
 * @param exposureTicks 実際に露光した tick 数。適正 80 との比が現像時のゲインになる
 * @param frames        平均に使った枚数。動体がどれだけ薄まるかを決める
 */
public record PhotoMapPixelsPayload(int token, int exposureTicks, int frames, byte[] gray) {

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(token);
        buf.writeVarInt(exposureTicks);
        buf.writeVarInt(frames);
        buf.writeByteArray(gray);
    }

    public static PhotoMapPixelsPayload read(FriendlyByteBuf buf) {
        int token = buf.readVarInt();
        int ticks = buf.readVarInt();
        int frames = buf.readVarInt();
        // 固定長だが、readByteArray は長さを先頭の varint から読むので wire 上の形は同じ。
        // 壊れた長さは server 側（receivePixels）で弾く。
        return new PhotoMapPixelsPayload(token, ticks, frames, buf.readByteArray(LatentImage.SIZE));
    }
}

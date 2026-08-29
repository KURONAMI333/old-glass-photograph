package com.kuronami.oldglassphotograph.network;

import com.kuronami.oldglassphotograph.capture.ViewfinderReading;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

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
                                    ViewfinderReading reading) {

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(basePos);
        buf.writeBlockPos(lensPos);
        buf.writeFloat(yaw);
        buf.writeFloat(pitch);
        buf.writeVarInt(reading.ordinal());
    }

    public static ViewfinderOpenPayload read(FriendlyByteBuf buf) {
        return new ViewfinderOpenPayload(
                buf.readBlockPos(),
                buf.readBlockPos(),
                buf.readFloat(),
                buf.readFloat(),
                ViewfinderReading.fromOrdinal(buf.readVarInt()));
    }
}

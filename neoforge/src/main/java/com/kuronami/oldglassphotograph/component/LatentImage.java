package com.kuronami.oldglassphotograph.component;

import com.kuronami.oldglassphotograph.capture.ExposureModel;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Glass Plate が抱える潜像。中身は 128x128 の 8bit gray（map 色ではない）。
 *
 * <p>map 色ではなく gray を持つ理由は {@code MODJAM_DESIGN_FIXES.md} A-3 のとおり。
 * 失敗マスク・露光不足/過多の明暗処理を合成してから 1 回だけ量子化するため、
 * 潜像の段階では階調を落とさない。
 *
 * <p><b>stream codec を持たない</b>（既定の NBT 同期に任せる）。pixel を送らない stream codec は
 * creative のスロット操作で潜像を空へ上書きさせるため外した（{@code MODJAM_DECISIONS_OGP.md} §5）。
 */
public record LatentImage(byte[] pixels, int exposureTicks, int light) {

    /** 128 x 128。 */
    public static final int SIZE = 128 * 128;

    public static final Codec<LatentImage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BYTE_BUFFER.fieldOf("pixels")
                    .forGetter(latent -> ByteBuffer.wrap(latent.pixels())),
            Codec.INT.optionalFieldOf("exposure_ticks", 0)
                    .forGetter(LatentImage::exposureTicks),
            Codec.INT.optionalFieldOf("light", ExposureModel.UNKNOWN_LIGHT)
                    .forGetter(LatentImage::light)
    ).apply(instance, (pixels, ticks, light) -> new LatentImage(toArray(pixels), ticks, light)));

    private static byte[] toArray(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.slice().get(out);
        return out;
    }

    /** pixel を実際に持っているか（client 側の複製は常に false になる）。 */
    public boolean hasPixels() {
        return pixels.length == SIZE;
    }

    /** 検証用。pixel 列の CRC32。 */
    public long checksum() {
        CRC32 crc = new CRC32();
        crc.update(pixels);
        return crc.getValue();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LatentImage(byte[] otherPixels, int otherTicks, int otherLight)
                && otherTicks == this.exposureTicks
                && otherLight == this.light
                && Arrays.equals(otherPixels, this.pixels);
    }

    @Override
    public int hashCode() {
        return (Arrays.hashCode(pixels) * 31 + exposureTicks) * 31 + light;
    }

    @Override
    public String toString() {
        return "LatentImage[bytes=" + pixels.length + ", exposureTicks=" + exposureTicks
                + ", light=" + light + ", crc32=" + checksum() + "]";
    }
}

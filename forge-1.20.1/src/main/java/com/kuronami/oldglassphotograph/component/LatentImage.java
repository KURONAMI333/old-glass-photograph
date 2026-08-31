package com.kuronami.oldglassphotograph.component;

import com.kuronami.oldglassphotograph.capture.ExposureModel;
import com.kuronami.oldglassphotograph.capture.PhotographViewGeometry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Glass Plate が抱える潜像。中身は {@link #DIM} 角の 8bit gray（map 色ではない）。
 *
 * <p>map 色ではなく gray を持つ理由は {@code MODJAM_DESIGN_FIXES.md} A-3 のとおり。
 * 失敗マスク・露光不足/過多の明暗処理を合成してから 1 回だけ量子化するため、
 * 潜像の段階では階調を落とさない。
 *
 * <p>26.x / 1.21.1 の data component（{@code LATENT_IMAGE}）に対して、この帯では
 * {@link OgpNbt} 経由で ItemStack の NBT へ載る（1.20.1 は DataComponents API を
 * 持たない帯。{@code OgpNbt} のクラス javadoc 参照）。
 */
public record LatentImage(byte[] pixels, int exposureTicks, int light) {

    /**
     * 1 辺の画素数。<b>写真の解像度はここ 1 箇所で決まる</b>（撮影の縮小・潜像・仕上がりの全部）。
     *
     * <p>128 だった頃は保存にバニラの地図データを使っていたので、地図の仕様で 128 が上限だった。
     * 保存を自前のタグへ移したので上限が外れている（2026-08-31・kura「ドットが粗すぎる」）。
     */
    public static final int DIM = PhotographViewGeometry.PHOTO_PX;

    /** {@link #DIM} の 2 乗。 */
    public static final int SIZE = DIM * DIM;

    /** NBT へ書く。{@code OgpNbt} からだけ呼ばれる。 */
    public CompoundTag save(CompoundTag tag) {
        tag.putByteArray("pixels", pixels);
        tag.putInt("exposure_ticks", exposureTicks);
        tag.putInt("light", light);
        return tag;
    }

    /** NBT から読む。{@code OgpNbt} からだけ呼ばれる。 */
    public static LatentImage load(CompoundTag tag) {
        return new LatentImage(
                tag.getByteArray("pixels"),
                tag.getInt("exposure_ticks"),
                tag.contains("light", Tag.TAG_ANY_NUMERIC)
                        ? tag.getInt("light") : ExposureModel.UNKNOWN_LIGHT);
    }

    /**
     * pixel を実際に持っているか。
     *
     * <p>26.x では pixel は client へも同期していたが、この帯でも ItemStack の NBT は
     * インベントリ同期ごと全体が届くので同じく true になる。false は保存データが壊れた時の検出用。
     */
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
        if (!(other instanceof LatentImage latent)) {
            return false;
        }
        return other == this
                || (latent.exposureTicks == this.exposureTicks
                && latent.light == this.light
                && Arrays.equals(latent.pixels, this.pixels));
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

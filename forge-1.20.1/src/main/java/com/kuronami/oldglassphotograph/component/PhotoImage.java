package com.kuronami.oldglassphotograph.component;

import net.minecraft.nbt.CompoundTag;

import java.util.Arrays;

/**
 * 仕上がった写真の像。{@link LatentImage#DIM} 角の 8bit gray を写真アイテム自身が持つ。
 *
 * <p>以前はバニラの地図データ（{@code MapItemSavedData}）に載せていたが、地図は 128x128 固定で、
 * 拡大して見たときの粗さがそのまま上限になっていた（2026-08-31 kura 判定）。自前で持つと解像度の
 * 制約が外れ、<b>同期のコードも要らない</b>（NBT は ItemStack と一緒に運ばれる）。
 *
 * <p>{@code id} はテクスチャのキャッシュ鍵。像は現像した時点で確定して以後変わらないので、
 * 同じ id なら同じ絵として扱ってよい。
 *
 * <p>26.x / 1.21.1 の {@code PhotoImage} data component に対して、この帯では
 * {@link OgpNbt} 経由で ItemStack の NBT へ載る。
 */
public record PhotoImage(long id, byte[] gray) {

    /** NBT へ書く。{@code OgpNbt} からだけ呼ばれる。 */
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("id", id);
        tag.putByteArray("gray", gray);
        return tag;
    }

    /** NBT から読む。{@code OgpNbt} からだけ呼ばれる。 */
    public static PhotoImage load(CompoundTag tag) {
        return new PhotoImage(tag.getLong("id"), tag.getByteArray("gray"));
    }

    /** 画素が揃っているか。欠けていれば描画側は白紙の写真として扱う。 */
    public boolean hasPixels() {
        return gray.length == LatentImage.SIZE;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PhotoImage image
                && image.id == this.id
                && Arrays.equals(image.gray, this.gray);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id) * 31 + Arrays.hashCode(gray);
    }

    @Override
    public String toString() {
        return "PhotoImage[id=" + id + ", bytes=" + gray.length + "]";
    }
}

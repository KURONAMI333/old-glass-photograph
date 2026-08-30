package com.kuronami.oldglassphotograph.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 仕上がった写真の像。{@link LatentImage#DIM} 角の 8bit gray を写真アイテム自身が持つ。
 *
 * <p>以前はバニラの地図データ（{@code MapItemSavedData}）に載せていたが、地図は 128x128 固定で、
 * 拡大して見たときの粗さがそのまま上限になっていた（2026-08-31 kura 判定）。自前で持つと解像度の
 * 制約が外れ、<b>同期のコードも要らない</b>（コンポーネントは ItemStack と一緒に運ばれる）。
 *
 * <p>{@code id} はテクスチャのキャッシュ鍵。像は現像した時点で確定して以後変わらないので、
 * 同じ id なら同じ絵として扱ってよい。
 *
 * <p>{@link LatentImage} と同じく <b>stream codec を持たない</b>（既定の NBT 同期に任せる）。
 */
public record PhotoImage(long id, byte[] gray) {

    public static final Codec<PhotoImage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("id").forGetter(PhotoImage::id),
            Codec.BYTE_BUFFER.fieldOf("gray").forGetter(image -> ByteBuffer.wrap(image.gray()))
    ).apply(instance, (id, gray) -> new PhotoImage(id, toArray(gray))));

    private static byte[] toArray(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.slice().get(out);
        return out;
    }

    /** 画素が揃っているか。欠けていれば描画側は白紙の写真として扱う。 */
    public boolean hasPixels() {
        return gray.length == LatentImage.SIZE;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PhotoImage(long otherId, byte[] otherGray)
                && otherId == this.id
                && Arrays.equals(otherGray, this.gray);
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

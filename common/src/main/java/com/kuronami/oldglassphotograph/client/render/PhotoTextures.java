package com.kuronami.oldglassphotograph.client.render;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.component.OgpComponents;
import com.kuronami.oldglassphotograph.component.PhotoImage;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 写真の像を貼るテクスチャの置き場。
 *
 * <p>以前は像がバニラの地図データに載っていたので {@code MapTextureManager} が面倒を見ていたが、
 * 保存を自前のコンポーネント（{@link PhotoImage}）へ移したので、テクスチャもこちらで持つ。
 * vanilla の {@code MapTextureManager} と同じ形（id ごとに 1 枚・作ったら使い回す）。
 *
 * <p>像は現像した時点で確定して以後変わらないので、<b>一度作ったら更新しない</b>。
 * 同じ id の写真が複数スタックあっても 1 枚で足りる。
 */
public final class PhotoTextures {

    private static final Map<Long, Identifier> CACHE = new HashMap<>();

    private PhotoTextures() {
    }

    /**
     * 像に対応するテクスチャを用意して返す。まだ無ければその場で作る。
     *
     * @return 貼れるテクスチャ。画素が欠けていれば null（呼び手は白紙の写真として描く）
     */
    public static @Nullable Identifier prepare(PhotoImage image) {
        if (!image.hasPixels()) {
            return null;
        }
        Identifier cached = CACHE.get(image.id());
        if (cached != null) {
            return cached;
        }
        String name = "photo/" + image.id();
        Identifier key = Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, name);
        DynamicTexture texture = new DynamicTexture(name, LatentImage.DIM, LatentImage.DIM, false);
        NativeImage pixels = texture.getPixels();
        if (pixels == null) {
            return null;
        }
        byte[] gray = image.gray();
        for (int y = 0; y < LatentImage.DIM; y++) {
            for (int x = 0; x < LatentImage.DIM; x++) {
                int v = gray[x + y * LatentImage.DIM] & 0xFF;
                pixels.setPixel(x, y, 0xFF000000 | (v << 16) | (v << 8) | v);
            }
        }
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(key, texture);
        CACHE.put(image.id(), key);
        return key;
    }

    /**
     * 写真アイテムから貼るテクスチャを決める。
     *
     * <p>0.1.2 までに撮った写真は像を地図データに持っているので、そちらも読めるようにしてある
     * （既存のワールドの写真を壊さないため）。新しく撮った写真は {@link PhotoImage} を持つ。
     *
     * @return 貼れるテクスチャ。まだ像が無ければ null
     */
    public static @Nullable Identifier resolve(ItemStack stack) {
        PhotoImage image = stack.get(OgpComponents.photoImage());
        if (image != null) {
            return prepare(image);
        }
        MapId id = stack.get(DataComponents.MAP_ID);
        if (id == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        MapItemSavedData data = level == null ? null : level.getMapData(id);
        return data == null ? null : minecraft.getMapTextureManager().prepareMapTexture(id, data);
    }
}

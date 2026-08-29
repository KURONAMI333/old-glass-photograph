package com.kuronami.oldglassphotograph.client.render;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * 写真の像（map saved data の画素）を貼る動的テクスチャの client 側レジストリ。
 *
 * <p>26.x の {@code getMapTextureManager().prepareMapTexture(id, data)} 相当。
 * この帯の {@code MapRenderer} は像のテクスチャを内部に隠すので、mod 側から
 * ResourceLocation を引き当てられない。そこで vanilla {@code MapRenderer$MapInstance} と
 * <b>同じ手順</b>を自前で行う（neoforge-1.21.1 セルの {@code PlateTextures} と同じ設計）:
 * {@code new DynamicTexture(128,128,true)} を作り、{@code data.colors} の packed id から
 * {@code MapColor.getColorFromPackedId} で転記して upload し、{@code TextureManager.register} する。
 * 1.20.1 の TextureManager は prefix 文字列を受ける register が実在する（jar 実測）のでそのまま使う。
 *
 * <p>像は現像時に確定し以後変わらないため、転記は id ごとに 1 回で足りる。
 * data がまだ届いていない写真は呼び出し側が BLANK へ落とす。
 */
public final class PlateTextures {

    /** 像がまだ client に届いていないときに貼る地。26.x セルと同じ絵・同じ理由。 */
    public static final ResourceLocation BLANK =
            new ResourceLocation(OldGlassPhotograph.MODID, "textures/item/photograph.png");

    private static final Map<Integer, ResourceLocation> TEXTURES = new HashMap<>();

    private PlateTextures() {
    }

    /**
     * 像を持つ map id の動的テクスチャ。初回呼び出しで生成し、以後は同じ RL を返す。
     *
     * @param id   像（{@code map} NBT タグの値）
     * @param data 同期済みの画素。null なら {@link #BLANK} を返す
     */
    public static ResourceLocation texture(int id, MapItemSavedData data) {
        if (data == null) {
            return BLANK;
        }
        return TEXTURES.computeIfAbsent(id, key -> register(key, data));
    }

    private static ResourceLocation register(int key, MapItemSavedData data) {
        DynamicTexture texture = new DynamicTexture(128, 128, true);
        NativeImage pixels = texture.getPixels();
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                pixels.setPixelRGBA(x, y, MapColor.getColorFromPackedId(data.colors[x + y * 128]));
            }
        }
        texture.upload();
        return Minecraft.getInstance().getTextureManager()
                .register("old_glass_photograph/map/" + key, texture);
    }
}

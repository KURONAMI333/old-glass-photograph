package com.kuronami.oldglassphotograph.client.render;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import com.kuronami.oldglassphotograph.component.OgpComponents;
import com.kuronami.oldglassphotograph.component.PhotoImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * 写真アイテムの面を描く。<b>像そのものが item の見た目</b>で、紙の地図の枠も背景も出さない。
 *
 * <p>画素は vanilla の map saved data に載っている（{@code MODJAM_DECISIONS_OGP.md} §1）ので、
 * client 側の {@link MapItemSavedData} から 128x128 の動的テクスチャを取り出して 1 枚の板に貼る。
 * {@code MapRenderer#render}（{@code MC: net/minecraft/client/renderer/MapRenderer.java:35-41}）が
 * 額縁と一人称でやっているのと同じ {@code RenderTypes.text(texture)} の板 1 枚である。
 *
 * <p>頂点はブロック空間（1.0 = 1 ブロック、原点はモデルの角）。
 * {@code ItemTransform#apply} が最後に {@code translate(-0.5, -0.5, -0.5)} を掛けるので
 * （{@code MC: net/minecraft/client/resources/model/cuboid/ItemTransform.java:38-52}）、
 * 0..1 の板は {@code minecraft:item/generated} のスプライトと同じ位置に来る。
 *
 * <p>{@code RenderTypes.TEXT} は cull を明示しないので背面は消える
 * （{@code MC: net/minecraft/client/renderer/RenderPipelines.java:85-98}、
 * {@code com/mojang/blaze3d/pipeline/RenderPipeline} の既定は cull=true）。
 * 地面のアイテムや三人称で裏から見えるように、板は表裏 2 枚出す。
 */
public final class PhotographSpecialRenderer implements SpecialModelRenderer<PhotographSpecialRenderer.Plate> {

    /** {@code items/photograph.json} の {@code "type"}。 */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "photograph");

    /**
     * 像がまだ client に届いていないときに貼る板の地。
     *
     * <p>map の画素は「その写真を持っているプレイヤー」と「額縁を見ているプレイヤー」にしか
     * 送られない（{@code MC: net/minecraft/server/level/ServerPlayer.java:769-775} は自分宛だけ、
     * {@code MC: net/minecraft/server/level/ServerEntity.java:99-103} は額縁だけ）。
     * 他人が手に持っている写真・チェストの中の写真はここに落ちるので、稀な経路ではない。
     * 何も描かないと空スロットになるので、従来と同じアイテム絵を出す。
     *
     * <p><b>ファイルの実パスをそのまま書く。</b>{@code RenderTypes.text} が渡す Identifier は
     * {@code SimpleTexture} → {@code TextureContents.load} でリソースパスとして literal に引かれる
     * （{@code MC: net/minecraft/client/renderer/texture/TextureContents.java:17-19}）。
     * {@code textures/} と {@code .png} を落とすと missing texture の市松模様になる。
     */
    private static final Identifier BLANK =
            Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "textures/item/photograph.png");

    /** 板の面。{@code item/generated} のスプライト面と同じ z。 */
    private static final float PLANE_Z = 0.5F;

    @Override
    public @Nullable Plate extractArgument(ItemStack stack) {
        PhotoImage image = stack.get(OgpComponents.photoImage());
        if (image != null) {
            // 像はアイテムに載っているので、この時点で必ず揃っている（同期待ちが無い）。
            return new Plate(image.id(), image, null, true);
        }
        MapId id = stack.get(DataComponents.MAP_ID);
        if (id == null) {
            // 像を持たない写真は全部同じ見た目なので、識別子を足さずに 1 つの GUI スロットを共有してよい。
            return null;
        }
        ClientLevel level = Minecraft.getInstance().level;
        // 同期の有無を識別子に混ぜる。GuiItemAtlas はモデル識別子でスロットをキャッシュするので
        // （MC: net/minecraft/client/gui/render/GuiItemAtlas.java:73-92）、
        // 「まだ届いていない絵」がそのまま焼き付くのを防ぐ。像は locked なので届いた後は変わらない。
        boolean synced = level != null && level.getMapData(id) != null;
        return new Plate(0L, null, id, synced);
    }

    @Override
    public void submit(@Nullable Plate plate, ItemDisplayContext displayContext, PoseStack poseStack,
                       SubmitNodeCollector collector,
                       int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
        Identifier texture = BLANK;
        if (plate != null && plate.image() != null) {
            Identifier resolved = PhotoTextures.prepare(plate.image());
            if (resolved != null) {
                texture = resolved;
            }
        } else if (plate != null && plate.mapId() != null) {
            // 0.1.2 までに撮った写真。像は地図データにある。
            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel level = minecraft.level;
            MapItemSavedData data = level == null ? null : level.getMapData(plate.mapId());
            if (data != null) {
                texture = minecraft.getMapTextureManager().prepareMapTexture(plate.mapId(), data);
            }
        }

        RenderType renderType = RenderTypes.text(texture);
        // 表（+Z 向き）。map の行 0 が上なので v は y を反転させる。
        collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            buffer.addVertex(pose, 0.0F, 0.0F, PLANE_Z).setColor(-1).setUv(0.0F, 1.0F).setLight(lightCoords);
            buffer.addVertex(pose, 1.0F, 0.0F, PLANE_Z).setColor(-1).setUv(1.0F, 1.0F).setLight(lightCoords);
            buffer.addVertex(pose, 1.0F, 1.0F, PLANE_Z).setColor(-1).setUv(1.0F, 0.0F).setLight(lightCoords);
            buffer.addVertex(pose, 0.0F, 1.0F, PLANE_Z).setColor(-1).setUv(0.0F, 0.0F).setLight(lightCoords);
        });
        // 裏（-Z 向き）。巻き方向だけ逆にする＝ガラス板を裏から見た鏡像になる。
        collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            buffer.addVertex(pose, 0.0F, 1.0F, PLANE_Z).setColor(-1).setUv(0.0F, 0.0F).setLight(lightCoords);
            buffer.addVertex(pose, 1.0F, 1.0F, PLANE_Z).setColor(-1).setUv(1.0F, 0.0F).setLight(lightCoords);
            buffer.addVertex(pose, 1.0F, 0.0F, PLANE_Z).setColor(-1).setUv(1.0F, 1.0F).setLight(lightCoords);
            buffer.addVertex(pose, 0.0F, 0.0F, PLANE_Z).setColor(-1).setUv(0.0F, 1.0F).setLight(lightCoords);
        });
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        output.accept(new Vector3f(0.0F, 0.0F, PLANE_Z));
        output.accept(new Vector3f(1.0F, 0.0F, PLANE_Z));
        output.accept(new Vector3f(1.0F, 1.0F, PLANE_Z));
        output.accept(new Vector3f(0.0F, 1.0F, PLANE_Z));
    }

    /**
     * 描くのに要る全部。<b>値で等しいこと</b>が要る（GUI アトラスのキーになる）。
     *
     * <p>等値判定は {@code imageId} / {@code mapId} / {@code ready} だけで行う。{@code image} は
     * 64KB の画素列を持つので、そのまま比較すると GUI アトラスのキー比較が毎フレーム重くなる。
     * 像は現像した時点で確定するので、id が同じなら中身も同じでよい。
     *
     * @param imageId 自前で持つ像の id（地図由来の古い写真では 0）
     * @param image   自前で持つ像。地図由来の古い写真では null
     * @param mapId   地図由来の古い写真の map id。新しい写真では null
     * @param ready   画素が client にあるか
     */
    public record Plate(long imageId, @Nullable PhotoImage image, @Nullable MapId mapId, boolean ready) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Plate(long otherImageId, PhotoImage ignored, MapId otherMapId, boolean otherReady)
                    && otherImageId == this.imageId
                    && java.util.Objects.equals(otherMapId, this.mapId)
                    && otherReady == this.ready;
        }

        @Override
        public int hashCode() {
            return (Long.hashCode(imageId) * 31 + java.util.Objects.hashCode(mapId)) * 31 + (ready ? 1 : 0);
        }
    }

    public record Unbaked() implements SpecialModelRenderer.Unbaked {

        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
            return new PhotographSpecialRenderer();
        }
    }
}

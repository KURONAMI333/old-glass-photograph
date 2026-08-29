package com.kuronami.oldglassphotograph.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * 写真アイテムの面を描く（26.x の {@code SpecialModelRenderer} の 1.21.1 相当）。
 * <b>像そのものが item の見た目</b>で、紙の地図の枠も背景も出さない。
 *
 * <p>1.21.1 には special item renderer 機構が無いため、NeoForge
 * {@code IClientItemExtensions#getCustomRenderer}（BEWLR）で全 display context の
 * アイテム描画を引き受ける。{@code ItemRenderer.render} はモデルの camera transform 適用と
 * {@code translate(-0.5,-0.5,-0.5)} を済ませた後に {@code renderByItem} を呼ぶので、
 * 頂点は 26.x 版と同じ「ブロック空間 0..1 の板」でよい。
 *
 * <p>画素は vanilla の map saved data に載っているので、{@link PlateTextures} から
 * 動的テクスチャを取り出して {@code RenderType.text(texture)} の板 1 枚に貼る。
 * vanilla {@code MapRenderer} が額縁と一人称でやっているのと同じ板である。
 *
 * <p>{@code RenderType.text} は cull を明示しないので背面は消える。
 * 地面のアイテムや三人称で裏から見えるように、板は表裏 2 枚出す。
 */
public final class PhotographItemRenderer extends BlockEntityWithoutLevelRenderer {

    /** OgpClient が {@code RegisterClientExtensionsEvent.registerItem} へ渡すインスタンス。 */
    public static final IClientItemExtensions EXTENSIONS = new IClientItemExtensions() {
        @Override
        public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            return INSTANCE;
        }
    };

    private static final PhotographItemRenderer INSTANCE = new PhotographItemRenderer();

    /** 板の面。{@code item/generated} のスプライト面と同じ z。 */
    private static final float PLANE_Z = 0.5F;

    private PhotographItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                             MultiBufferSource buffer, int light, int overlay) {
        MapId id = stack.get(DataComponents.MAP_ID);
        ResourceLocation texture = PlateTextures.BLANK;
        if (id != null) {
            net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
            MapItemSavedData data = level == null ? null : level.getMapData(id);
            texture = PlateTextures.texture(id, data);
        }
        VertexConsumer vc = buffer.getBuffer(RenderType.text(texture));
        // 表（+Z 向き）。map の行 0 が上なので v は y を反転させる。
        vc.addVertex(poseStack.last().pose(), 0.0F, 0.0F, PLANE_Z).setColor(-1).setUv(0.0F, 1.0F).setLight(light);
        vc.addVertex(poseStack.last().pose(), 1.0F, 0.0F, PLANE_Z).setColor(-1).setUv(1.0F, 1.0F).setLight(light);
        vc.addVertex(poseStack.last().pose(), 1.0F, 1.0F, PLANE_Z).setColor(-1).setUv(1.0F, 0.0F).setLight(light);
        vc.addVertex(poseStack.last().pose(), 0.0F, 1.0F, PLANE_Z).setColor(-1).setUv(0.0F, 0.0F).setLight(light);
        // 裏（-Z 向き）。巻き方向だけ逆にする＝ガラス板を裏から見た鏡像になる。
        vc.addVertex(poseStack.last().pose(), 0.0F, 1.0F, PLANE_Z).setColor(-1).setUv(0.0F, 0.0F).setLight(light);
        vc.addVertex(poseStack.last().pose(), 1.0F, 1.0F, PLANE_Z).setColor(-1).setUv(1.0F, 0.0F).setLight(light);
        vc.addVertex(poseStack.last().pose(), 1.0F, 0.0F, PLANE_Z).setColor(-1).setUv(1.0F, 1.0F).setLight(light);
        vc.addVertex(poseStack.last().pose(), 0.0F, 0.0F, PLANE_Z).setColor(-1).setUv(0.0F, 1.0F).setLight(light);
    }
}

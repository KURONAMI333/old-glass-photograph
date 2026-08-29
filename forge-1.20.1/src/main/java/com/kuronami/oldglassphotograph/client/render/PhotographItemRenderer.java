package com.kuronami.oldglassphotograph.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import com.kuronami.oldglassphotograph.component.OgpNbt;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

/**
 * 写真アイテムの面を描く（26.x の {@code SpecialModelRenderer} の、この帯での相当品）。
 * <b>像そのものが item の見た目</b>で、紙の地図の枠も背景も出さない。
 *
 * <p>Forge 1.20.1 は {@code IClientItemExtensions#getCustomRenderer}（BEWLR）で全 display context の
 * アイテム描画を引き受けられる。{@code ItemRenderer.render} はモデルの camera transform 適用と
 * {@code translate(-0.5,-0.5,-0.5)} を済ませた後に {@code renderByItem} を呼ぶので、
 * 頂点は 26.x special renderer と同じ「ブロック空間 0..1 の板」でよい
 * （neoforge-1.21.1 セルと同じ設計。頂点 API だけこの帯の旧形）。
 *
 * <p>画素は vanilla の map saved data に載っているので、{@link PlateTextures} から
 * 動的テクスチャを取り出して {@code RenderType.text(texture)} の板に貼る。
 * 表裏 2 枚出す（{@code RenderType.text} は背面を落とす）。
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
        Integer id = OgpNbt.mapId(stack);
        ResourceLocation texture = PlateTextures.BLANK;
        if (id != null) {
            ClientLevel level = Minecraft.getInstance().level;
            MapItemSavedData data = level == null ? null : level.getMapData(PhotographHandRenderer.mapKey(id));
            texture = PlateTextures.texture(id, data);
        }
        VertexConsumer vc = buffer.getBuffer(RenderType.text(texture));
        var pose = poseStack.last().pose();
        // 表（+Z 向き）。map の行 0 が上なので v は y を反転させる。
        vc.vertex(pose, 0.0F, 0.0F, PLANE_Z).color(-1).uv(0.0F, 1.0F).uv2(light).endVertex();
        vc.vertex(pose, 1.0F, 0.0F, PLANE_Z).color(-1).uv(1.0F, 1.0F).uv2(light).endVertex();
        vc.vertex(pose, 1.0F, 1.0F, PLANE_Z).color(-1).uv(1.0F, 0.0F).uv2(light).endVertex();
        vc.vertex(pose, 0.0F, 1.0F, PLANE_Z).color(-1).uv(0.0F, 0.0F).uv2(light).endVertex();
        // 裏（-Z 向き）。巻き方向だけ逆にする＝ガラス板を裏から見た鏡像になる。
        vc.vertex(pose, 0.0F, 1.0F, PLANE_Z).color(-1).uv(0.0F, 0.0F).uv2(light).endVertex();
        vc.vertex(pose, 1.0F, 1.0F, PLANE_Z).color(-1).uv(1.0F, 0.0F).uv2(light).endVertex();
        vc.vertex(pose, 1.0F, 0.0F, PLANE_Z).color(-1).uv(1.0F, 1.0F).uv2(light).endVertex();
        vc.vertex(pose, 0.0F, 0.0F, PLANE_Z).color(-1).uv(0.0F, 1.0F).uv2(light).endVertex();
    }
}

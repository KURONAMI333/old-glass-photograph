package com.kuronami.oldglassphotograph.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 写真アイテムの面を描く（26.x の {@code SpecialModelRenderer} / 1.21.1 NeoForge セルの
 * BEWLR の、この帯での相当品）。<b>像そのものが item の見た目</b>で、紙の地図の枠も背景も出さない。
 *
 * <p>この帯の Fabric には item renderer 差し替えの loader 機能が無いため、
 * fabric-rendering-v1 の {@link BuiltinItemRenderer}（{@code builtin/entity} モデル経路）を使う。
 * models/item/photograph.json が {@code "parent": "builtin/entity"} を指し、
 * display 変換はモデル側が持つ。{@code ItemRenderer} は camera transform と
 * translate(-0.5,-0.5,-0.5) を済ませてから呼ぶので、頂点は 0..1 の板でよい。
 *
 * <p>画素は写真アイテムのタグに載っているので（0.1.2 までの写真は map saved data）、
 * {@link PlateTextures} から
 * 動的テクスチャを取り出して {@code RenderType.text(texture)} の板に貼る。
 * 表裏 2 枚出す（{@code RenderType.text} は背面を落とす）。
 */
public enum PhotographItemRenderer implements BuiltinItemRenderer {
    INSTANCE;

    /** 板の面。{@code item/generated} のスプライト面と同じ z。 */
    private static final float PLANE_Z = 0.5F;

    @Override
    public void render(ItemStack stack, PoseStack poseStack, MultiBufferSource buffer, int light, int overlay) {
        ResourceLocation texture = PlateTextures.resolve(stack);
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

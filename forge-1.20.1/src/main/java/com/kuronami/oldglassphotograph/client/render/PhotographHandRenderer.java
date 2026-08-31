package com.kuronami.oldglassphotograph.client.render;

import com.kuronami.oldglassphotograph.item.PhotographItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/**
 * 一人称の手持ちを横取りして、写真を<b>地図と同じ構えで正面に</b>掲げる。
 *
 * <p><b>なぜ横取りが要るか</b>: vanilla の {@code ItemInHandRenderer#renderArmWithItem} は
 * {@code itemStack.is(Items.FILLED_MAP)}（アイテム同一性）で地図の描画へ分岐するため、
 * 写真は何もしないと普通のスプライトとして出る。写真の面そのものを出すには、
 * この 1 枚だけを差し替える必要がある。
 *
 * <p>NeoForge 1.20.1 は一人称の手ごとの描画で {@code RenderHandEvent} を発火する。
 * Fabric 1.20.1 に等価イベントが無いため、同一位置
 * （{@code ItemInHandRenderer#renderArmWithItem} の HEAD・cancellable。引数並びは
 * 26.x の {@code submitArmWithItem} と同じ形＝bytecode 実測）に mixin で割り込んで
 * 共通の {@code trySubmit} を呼ぶ（mixin 側）。ここはその cancel 先として vanilla
 * {@code renderTwoHandedMap} / {@code renderOneHandedMap} / {@code renderMap} の写しを置く。
 *
 * <h2>姿勢</h2>
 * 要件は「完成した写真を持つ時、地図みたいに正面から見れるような持ち方」
 * （2026-08-23 実機）。<b>vanilla の地図の姿勢をそのまま借りて、中身だけ写真に差し替える。</b>
 */
public final class PhotographHandRenderer {

    private PhotographHandRenderer() {
    }

    /**
     * 写真の一人称描画を引き受ける。引き受けた caller は vanilla の該当手の描画を飛ばすこと。
     *
     * @return 描画を引き受けたか（true なら vanilla の経路は cancel 済みとして扱う）
     */
    public static boolean trySubmit(AbstractClientPlayer player, float partialTick, float interpolatedPitch,
                                    InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
                                    PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (!(stack.getItem() instanceof PhotographItem)) {
            return false;
        }
        if (player == null) {
            return false;
        }
        // ここから先は vanilla の代わりを務めるので、描かない場合も必ず引き受ける。
        if (player.isScoping()) {
            return true;
        }

        boolean mainHand = hand == InteractionHand.MAIN_HAND;
        int light = packedLight;
        float attack = swingProgress;
        float inverseArmHeight = equipProgress;

        poseStack.pushPose();
        if (mainHand && player.getOffhandItem().isEmpty()) {
            twoHanded(poseStack, bufferSource, light, interpolatedPitch, inverseArmHeight,
                    attack, player, stack);
        } else {
            HumanoidArm arm = mainHand ? player.getMainArm() : player.getMainArm().getOpposite();
            oneHanded(poseStack, bufferSource, light, inverseArmHeight, arm, attack, player, stack);
        }
        poseStack.popPose();
        return true;
    }

    // ------------------------------------------------------------------ 姿勢

    /** 両手で正面に構える。{@code ItemInHandRenderer#renderTwoHandedMap} の写し。 */
    private static void twoHanded(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                                  float xRot, float inverseArmHeight, float attack,
                                  AbstractClientPlayer player, ItemStack stack) {
        float sqrtAttack = Mth.sqrt(attack);
        float ySwing = -0.2F * Mth.sin(attack * (float) Math.PI);
        float zSwing = -0.4F * Mth.sin(sqrtAttack * (float) Math.PI);
        poseStack.translate(0.0F, -ySwing / 2.0F, zSwing);
        float mapTilt = mapTilt(xRot);
        poseStack.translate(0.0F, 0.04F + inverseArmHeight * -1.2F + mapTilt * -0.5F, -0.72F);
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(mapTilt * -85.0F));
        if (!player.isInvisible()) {
            poseStack.pushPose();
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(90.0F));
            hand(poseStack, bufferSource, light, player, HumanoidArm.RIGHT);
            hand(poseStack, bufferSource, light, player, HumanoidArm.LEFT);
            poseStack.popPose();
        }

        float xzSwing = Mth.sin(sqrtAttack * (float) Math.PI);
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(xzSwing * 20.0F));
        poseStack.scale(2.0F, 2.0F, 2.0F);
        plate(poseStack, bufferSource, light, stack);
    }

    /** 片手で持つ。{@code ItemInHandRenderer#renderOneHandedMap} の写し。 */
    private static void oneHanded(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                                  float inverseArmHeight, HumanoidArm arm, float attack,
                                  AbstractClientPlayer player, ItemStack stack) {
        float invert = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.translate(invert * 0.125F, -0.125F, 0.0F);
        if (!player.isInvisible()) {
            poseStack.pushPose();
            poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(invert * 10.0F));
            arm(poseStack, bufferSource, light, inverseArmHeight, attack, arm, player);
            poseStack.popPose();
        }

        poseStack.pushPose();
        poseStack.translate(invert * 0.51F, -0.08F + inverseArmHeight * -1.2F, -0.75F);
        float sqrtAttack = Mth.sqrt(attack);
        float xSwing = Mth.sin(sqrtAttack * (float) Math.PI);
        float xSwingPosition = -0.5F * xSwing;
        float ySwingPosition = 0.4F * Mth.sin(sqrtAttack * (float) (Math.PI * 2));
        float zSwingPosition = -0.3F * Mth.sin(attack * (float) Math.PI);
        poseStack.translate(invert * xSwingPosition, ySwingPosition - 0.3F * xSwing, zSwingPosition);
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(xSwing * -45.0F));
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(invert * xSwing * -30.0F));
        plate(poseStack, bufferSource, light, stack);
        poseStack.popPose();
    }

    /** 視線の上下から板の寝かせ具合を出す。{@code ItemInHandRenderer#calculateMapTilt} の写し。 */
    private static float mapTilt(float xRot) {
        float tilt = Mth.clamp(1.0F - xRot / 45.0F + 0.1F, 0.0F, 1.0F);
        return -Mth.cos(tilt * (float) Math.PI) * 0.5F + 0.5F;
    }

    /** 板を支える手。{@code ItemInHandRenderer#renderMapHand} の写し。 */
    private static void hand(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                             AbstractClientPlayer player, HumanoidArm arm) {
        PlayerRenderer renderer = playerRenderer(player);
        poseStack.pushPose();
        float invert = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(92.0F));
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(45.0F));
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(invert * -41.0F));
        poseStack.translate(invert * 0.3F, -1.1F, 0.45F);
        if (arm == HumanoidArm.RIGHT) {
            renderer.renderRightHand(poseStack, bufferSource, light, player);
        } else {
            renderer.renderLeftHand(poseStack, bufferSource, light, player);
        }
        poseStack.popPose();
    }

    /** 片手持ちの腕。{@code ItemInHandRenderer#renderPlayerArm} の写し。 */
    private static void arm(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                            float inverseArmHeight, float attack, HumanoidArm arm, AbstractClientPlayer player) {
        PlayerRenderer renderer = playerRenderer(player);
        boolean right = arm == HumanoidArm.RIGHT;
        float invert = right ? 1.0F : -1.0F;
        float sqrtAttack = Mth.sqrt(attack);
        float xSwingPosition = -0.3F * Mth.sin(sqrtAttack * (float) Math.PI);
        float ySwingPosition = 0.4F * Mth.sin(sqrtAttack * (float) (Math.PI * 2));
        float zSwingPosition = -0.4F * Mth.sin(attack * (float) Math.PI);
        poseStack.translate(invert * (xSwingPosition + 0.64000005F),
                ySwingPosition + -0.6F + inverseArmHeight * -0.6F,
                zSwingPosition + -0.71999997F);
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(invert * 45.0F));
        float xzSwing = Mth.sin(attack * attack * (float) Math.PI);
        float ySwing = Mth.sin(sqrtAttack * (float) Math.PI);
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(invert * ySwing * 70.0F));
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(invert * xzSwing * -20.0F));
        poseStack.translate(invert * -1.0F, 3.6F, 3.5F);
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(invert * 120.0F));
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(200.0F));
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(invert * -135.0F));
        poseStack.translate(invert * 5.6F, 0.0F, 0.0F);
        if (right) {
            renderer.renderRightHand(poseStack, bufferSource, light, player);
        } else {
            renderer.renderLeftHand(poseStack, bufferSource, light, player);
        }
    }

    /** この帯の player renderer 取得（26.x の getPlayerRenderer は無い）。 */
    private static PlayerRenderer playerRenderer(AbstractClientPlayer player) {
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        return (PlayerRenderer) dispatcher.getRenderer(player);
    }

    // ------------------------------------------------------------------ 板

    /**
     * 写真そのもの。{@code renderMap} の座標系をそのまま通し、<b>紙地図の背景は敷かない</b>。
     *
     * <p>vanilla は {@code scale(1/128)} の後で 128 単位の板を出すが、ここは 0..1 の板を
     * 直に出す（同じ大きさで、余計な桁を経由しない）。裏面も出す
     * （{@code RenderType.text} は背面を落とすので、巻き方向を逆にした板を重ねる）。
     */
    private static void plate(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                              ItemStack stack) {
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180.0F));
        poseStack.scale(0.38F, 0.38F, 0.38F);
        poseStack.translate(-0.5F, -0.5F, 0.0F);

        VertexConsumer vc = bufferSource.getBuffer(RenderType.text(texture(stack)));
        var pose = poseStack.last().pose();
        // 1.20.x の Vertex API（addVertex/setUv ではなく vertex/color/uv/uv2/endVertex）。
        vc.vertex(pose, 0.0F, 1.0F, 0.0F).color(-1).uv(0.0F, 1.0F).uv2(light).endVertex();
        vc.vertex(pose, 1.0F, 1.0F, 0.0F).color(-1).uv(1.0F, 1.0F).uv2(light).endVertex();
        vc.vertex(pose, 1.0F, 0.0F, 0.0F).color(-1).uv(1.0F, 0.0F).uv2(light).endVertex();
        vc.vertex(pose, 0.0F, 0.0F, 0.0F).color(-1).uv(0.0F, 0.0F).uv2(light).endVertex();
        vc.vertex(pose, 0.0F, 0.0F, 0.0F).color(-1).uv(0.0F, 0.0F).uv2(light).endVertex();
        vc.vertex(pose, 1.0F, 0.0F, 0.0F).color(-1).uv(1.0F, 0.0F).uv2(light).endVertex();
        vc.vertex(pose, 1.0F, 1.0F, 0.0F).color(-1).uv(1.0F, 1.0F).uv2(light).endVertex();
        vc.vertex(pose, 0.0F, 1.0F, 0.0F).color(-1).uv(0.0F, 1.0F).uv2(light).endVertex();
    }

    /** 像が client に届いていれば動的テクスチャ、まだなら BLANK。 */
    private static ResourceLocation texture(ItemStack stack) {
        return PlateTextures.resolve(stack);
    }
}

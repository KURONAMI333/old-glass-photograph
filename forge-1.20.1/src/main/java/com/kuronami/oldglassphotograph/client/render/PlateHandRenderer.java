package com.kuronami.oldglassphotograph.client.render;

import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;

/**
 * 板を左腕で振っている間だけ、刷毛の姿勢を<b>右腕の鏡像</b>に差し替える。
 *
 * <p>板の工程は {@code UseAnim.BRUSH} を借りていて、左右どちらの手でも進められる（§37）。
 * ところが vanilla の {@code ItemInHandRenderer#applyBrushTransform} は左右で式の形が違う。
 * 回転は鏡像になっているが<b>平行移動が鏡像になっていない</b>ため、左腕だけ振りの弧が
 * 腕から外れて板が画面の逆側へ流れる（2026-08-23 実機指摘）。
 *
 * <p>ここでは<b>右腕の式をそのまま鏡映して</b>左腕に使う。右手で振った時と同じ弧が
 * 左右反転で出る。右腕は vanilla のまま通す。差し替えは Fabric の mixin
 * （{@code ItemInHandRendererMixin}）が NeoForge {@code RenderHandEvent} 相当の位置から呼ぶ。
 */
public final class PlateHandRenderer {

    /** 刷毛の 1 振りの長さ（tick）。vanilla の {@code applyBrushTransform} と同じ。 */
    private static final float SWIPE_PERIOD = 10.0F;

    private PlateHandRenderer() {
    }

    /**
     * 板の一人称描画を引き受ける。引き受けた caller は vanilla の該当手の描画を飛ばすこと。
     *
     * @return 描画を引き受けたか（true なら vanilla の経路は cancel 済みとして扱う）
     */
    public static boolean trySubmit(AbstractClientPlayer player, float partialTick, float interpolatedPitch,
                                    InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
                                    PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (!(stack.getItem() instanceof GlassPlateItem)) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (player == null || player.isScoping()) {
            return false;
        }
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND
                ? player.getMainArm()
                : player.getMainArm().getOpposite();
        if (arm != HumanoidArm.LEFT) {
            // 右腕は vanilla の式で正しい。触らない。
            return false;
        }
        if (!player.isUsingItem()
                || player.getUsedItemHand() != hand
                || player.getUseItemRemainingTicks() <= 0
                || stack.getUseAnimation() != UseAnim.BRUSH) {
            // 振っていない板はふつうの持ち方。vanilla に任せる。
            return false;
        }

        float frameInterp = partialTick;
        poseStack.pushPose();

        // applyItemArmTransform。左腕なので invert = -1。
        poseStack.translate(-0.56F, -0.52F + equipProgress * -0.6F, -0.72F);

        // applyBrushTransform の右腕の式を x 軸で鏡映したもの。
        poseStack.translate(0.25F, 0.22F, 0.35F);
        poseStack.mulPose(Axis.XP.rotationDegrees(-80.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(swipeAngle(player.getUseItemRemainingTicks(), frameInterp)));

        // vanilla ItemInHandRenderer と同じ renderStatic 経路
        // （seed も同クラスと同じ式: player.getId() + displayContext.ordinal()）。
        ItemDisplayContext context = ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        minecraft.getItemRenderer().renderStatic(
                player, stack, context, false, poseStack, bufferSource, player.level(),
                packedLight, OverlayTexture.NO_OVERLAY, player.getId() + context.ordinal());
        poseStack.popPose();
        return true;
    }

    /** 振りの角度。vanilla の {@code applyBrushTransform} と同じ式。 */
    private static float swipeAngle(int remainingTicks, float frameInterp) {
        float withinSwipe = remainingTicks % SWIPE_PERIOD;
        float sinceUpdate = withinSwipe - frameInterp + 1.0F;
        float scaledUsageTime = 1.0F - sinceUpdate / SWIPE_PERIOD;
        return -15.0F + 75.0F * Mth.cos(scaledUsageTime * 2.0F * (float) Math.PI);
    }
}

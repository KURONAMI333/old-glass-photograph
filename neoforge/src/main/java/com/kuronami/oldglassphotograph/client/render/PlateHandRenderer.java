package com.kuronami.oldglassphotograph.client.render;

import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 板を左腕で振っている間だけ、刷毛の姿勢を<b>右腕の鏡像</b>に差し替える。
 *
 * <p>板の工程は {@code ItemUseAnimation.BRUSH} を借りていて、左右どちらの手でも進められる
 * （{@code MODJAM_DECISIONS_OGP.md} §37「どちらの手かは規律にしない」）。
 * ところが vanilla の {@code ItemInHandRenderer#applyBrushTransform}
 * （{@code MC: net/minecraft/client/renderer/ItemInHandRenderer.java:295-318}）は
 * 左右で式の形が違う。
 *
 * <pre>
 * 右腕: translate(-0.25, 0.22, 0.35) → XP(-80) YP( 90) ZP(0) XP(振り)
 * 左腕: translate( 0.1,  0.83, 0.35) → XP(-80) YP(-90)       XP(振り) → translate(-0.3, 0.22, 0.35)
 * </pre>
 *
 * <p>回転は鏡像になっている（x 軸の鏡映では {@code XP} が不変・{@code YP} と {@code ZP} が符号反転）
 * が、<b>平行移動が鏡像になっていない</b>。左腕だけ前後に 0.61 ずれた位置から振り始め、
 * さらに回転の<b>後</b>に平行移動が 1 つ足されるので、振りの弧が腕から外れて板が画面の逆側へ流れる
 * （2026-08-23 実機指摘「定着作業の時、写真をオフハンドにもつと、振り方が変だ」）。
 *
 * <p>ここでは<b>右腕の式をそのまま鏡映して</b>左腕に使う。姿勢を新しく作らないので、
 * 右手で振った時と同じ弧が左右反転で出る。右腕は vanilla のまま通す（触らない）。
 *
 * <h2>境界</h2>
 *
 * <ul>
 *   <li>差し替えるのは<b>左腕で板を使っている最中だけ</b>。使っていない板・右腕・
 *       他のアイテムは vanilla の経路をそのまま通る</li>
 *   <li>{@code BRUSH} 以外のアニメが将来入っても、ここは反応しない</li>
 * </ul>
 */
public final class PlateHandRenderer {

    /** 刷毛の 1 振りの長さ（tick）。vanilla の {@code applyBrushTransform} と同じ。 */
    private static final float SWIPE_PERIOD = 10.0F;

    private PlateHandRenderer() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(PlateHandRenderer::onRenderHand);
    }

    private static void onRenderHand(RenderHandEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof GlassPlateItem)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || player.isScoping()) {
            return;
        }
        HumanoidArm arm = event.getHand() == InteractionHand.MAIN_HAND
                ? player.getMainArm()
                : player.getMainArm().getOpposite();
        if (arm != HumanoidArm.LEFT) {
            // 右腕は vanilla の式で正しい。触らない。
            return;
        }
        if (!player.isUsingItem()
                || player.getUsedItemHand() != event.getHand()
                || player.getUseItemRemainingTicks() <= 0
                || stack.getUseAnimation() != ItemUseAnimation.BRUSH) {
            // 振っていない板はふつうの持ち方。vanilla に任せる。
            return;
        }
        event.setCanceled(true);

        float frameInterp = event.getPartialTick();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();

        // applyItemArmTransform（同 :330-333）。左腕なので invert = -1。
        poseStack.translate(-0.56F, -0.52F + event.getEquipProgress() * -0.6F, -0.72F);

        // applyBrushTransform の右腕の式を x 軸で鏡映したもの。
        // translate は x だけ符号を反転し、XP はそのまま・YP は符号を反転する。
        poseStack.translate(0.25F, 0.22F, 0.35F);
        poseStack.mulPose(Axis.XP.rotationDegrees(-80.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(swipeAngle(player.getUseItemRemainingTicks(), frameInterp)));

        ItemStackRenderState renderState = new ItemStackRenderState();
        ItemDisplayContext context = ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        minecraft.getItemModelResolver().updateForTopItem(
                renderState, stack, context, player.level(), player, player.getId() + context.ordinal());
        renderState.submit(poseStack, event.getSubmitNodeCollector(), event.getPackedLight(),
                OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    /** 振りの角度。vanilla の {@code applyBrushTransform}（同 {@code :296-305}）と同じ式。 */
    private static float swipeAngle(int remainingTicks, float frameInterp) {
        float withinSwipe = remainingTicks % SWIPE_PERIOD;
        float sinceUpdate = withinSwipe - frameInterp + 1.0F;
        float scaledUsageTime = 1.0F - sinceUpdate / SWIPE_PERIOD;
        return -15.0F + 75.0F * Mth.cos(scaledUsageTime * 2.0F * (float) Math.PI);
    }
}

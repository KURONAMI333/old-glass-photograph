package com.kuronami.oldglassphotograph.client.render;

import com.kuronami.oldglassphotograph.item.PhotographItem;
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
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 一人称の手持ちを横取りして、写真をふつうのアイテムとして描く。
 *
 * <p><b>なぜ要るか</b>: {@code ItemInHandRenderer#submitArmWithItem} は
 * {@code itemStack.getItem() instanceof MapItem} で地図の描画へ分岐する
 * （{@code MC: net/minecraft/client/renderer/ItemInHandRenderer.java:436}）。
 * 写真は map の保存・同期をそのまま借りるために {@code MapItem} を継承しているので、
 * 何もしないと {@code renderMap} が紙地図の背景（{@code MAP_BACKGROUND_CHECKERBOARD}）を描く
 * （同 {@code :227-247}）。<b>いちばん地図に見える状態</b>になる。
 *
 * <p>{@code IClientItemExtensions#applyForgeHandTransform} はこの分岐より後の {@code else} 側
 * （同 {@code :487-488}）にしか無く、{@code MapItem} には届かない。
 * 分岐そのものを迂回できる正規の入口は {@code ClientHooks#renderSpecificFirstPersonHand} が投げる
 * {@link RenderHandEvent} で、これは分岐より前（同 {@code :348} / {@code :367}）に来る。
 *
 * <p>姿勢は vanilla の「ふつうのアイテム」経路をそのまま写している
 * （{@code applyItemArmTransform} = 同 {@code :331-333}、
 * {@code swingArm} = 同 {@code :603-608}、
 * {@code applyItemArmAttackTransform} = 同 {@code :321-328}）。見せ方を新しく作らない。
 */
public final class PhotographHandRenderer {

    private PhotographHandRenderer() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(PhotographHandRenderer::onRenderHand);
    }

    private static void onRenderHand(RenderHandEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof PhotographItem)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        // ここから先は vanilla の代わりを務めるので、描かない場合も必ず cancel する。
        event.setCanceled(true);
        if (player.isScoping()) {
            // vanilla の同じ判定は迂回した先（同 :429）にあるので、こちらで持つ。
            return;
        }

        HumanoidArm arm = event.getHand() == InteractionHand.MAIN_HAND
                ? player.getMainArm()
                : player.getMainArm().getOpposite();
        boolean rightArm = arm == HumanoidArm.RIGHT;
        int invert = rightArm ? 1 : -1;
        // RenderHandEvent の getter 名は vanilla の引数名と揃っていない。
        // ClientHooks:255-256 の呼び出し（ItemInHandRenderer:348）では
        // swingProgress = attack、equipProgress = inverseArmHeight。
        float attack = event.getSwingProgress();
        float inverseArmHeight = event.getEquipProgress();

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(invert * 0.56F, -0.52F + inverseArmHeight * -0.6F, -0.72F);

        float xSwingPosition = -0.4F * Mth.sin(Mth.sqrt(attack) * (float) Math.PI);
        float ySwingPosition = 0.2F * Mth.sin(Mth.sqrt(attack) * (float) (Math.PI * 2));
        float zSwingPosition = -0.2F * Mth.sin(attack * (float) Math.PI);
        poseStack.translate(invert * xSwingPosition, ySwingPosition, zSwingPosition);

        float ySwingRotation = Mth.sin(attack * attack * (float) Math.PI);
        poseStack.mulPose(Axis.YP.rotationDegrees(invert * (45.0F + ySwingRotation * -20.0F)));
        float xzSwingRotation = Mth.sin(Mth.sqrt(attack) * (float) Math.PI);
        poseStack.mulPose(Axis.ZP.rotationDegrees(invert * xzSwingRotation * -20.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(xzSwingRotation * -80.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(invert * -45.0F));

        ItemDisplayContext context = rightArm
                ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                : ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        ItemStackRenderState renderState = new ItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(
                renderState, stack, context, player.level(), player, player.getId() + context.ordinal());
        renderState.submit(poseStack, event.getSubmitNodeCollector(), event.getPackedLight(),
                OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }
}

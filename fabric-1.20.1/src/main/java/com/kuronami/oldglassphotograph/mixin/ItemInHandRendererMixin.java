package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.client.render.PhotographHandRenderer;
import com.kuronami.oldglassphotograph.client.render.PlateHandRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 一人称の手持ち差し替え（写真の地図構え・板の左腕振り）。
 *
 * <p>NeoForge 1.20.1 は一人称の手ごとの描画で RenderHandEvent を発火し、mod が cancel すると
 * vanilla の該当手の描画を飛ばせる。Fabric 1.20.1 に等価イベントが無いため、同一位置に割り込む。
 *
 * <p>注入先は {@code ItemInHandRenderer#renderArmWithItem(AbstractClientPlayer, float, float,
 * InteractionHand, float, ItemStack, float, PoseStack, MultiBufferSource, int)}（private・jar 実測）。
 * 引数並びは 26.x の {@code submitArmWithItem} と同じで、renderHandsWithItems の呼び出し側
 * bytecode から「partialTick / interpolatedPitch / swingProgress / equipProgress」の役割を確認済み。
 * 写真を先に判定するのは NeoForge 版のリスナー登録順と同じ。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void ogp$renderArmWithItem(AbstractClientPlayer player, float partialTick, float interpolatedPitch,
                                       InteractionHand hand, float swingProgress, ItemStack stack,
                                       float equipProgress, PoseStack poseStack,
                                       MultiBufferSource bufferSource, int packedLight, CallbackInfo ci) {
        if (PhotographHandRenderer.trySubmit(player, partialTick, interpolatedPitch,
                hand, swingProgress, stack, equipProgress, poseStack, bufferSource, packedLight)) {
            ci.cancel();
            return;
        }
        if (PlateHandRenderer.trySubmit(player, partialTick, interpolatedPitch,
                hand, swingProgress, stack, equipProgress, poseStack, bufferSource, packedLight)) {
            ci.cancel();
        }
    }
}

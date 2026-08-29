package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.client.render.PhotographHandRenderer;
import com.kuronami.oldglassphotograph.client.render.PlateHandRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 一人称の手持ち差し替え（写真の地図構え・板の左腕振り）。
 *
 * <p>NeoForge は {@code ItemInHandRenderer.submitArmWithItem} の冒頭で RenderHandEvent を発火し、
 * mod が cancel すると vanilla の該当手の描画を飛ばせる。Fabric に等価イベントが無いため、
 * 同一位置（メソッド HEAD・cancellable）に割り込んで共通の {@code trySubmit} を呼ぶ。
 * 写真を先に判定するのは NeoForge 版のリスナー登録順と同じ。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void ogp$submitArmWithItem(AbstractClientPlayer player, float partialTick, float interpolatedPitch,
                                       InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
                                       PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                                       CallbackInfo ci) {
        if (PhotographHandRenderer.trySubmit(player, partialTick, interpolatedPitch,
                hand, swingProgress, stack, equipProgress, poseStack, collector, packedLight)) {
            ci.cancel();
            return;
        }
        if (PlateHandRenderer.trySubmit(player, partialTick, interpolatedPitch,
                hand, swingProgress, stack, equipProgress, poseStack, collector, packedLight)) {
            ci.cancel();
        }
    }
}

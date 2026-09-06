package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 露光の 1 フレームを落とす位置。{@code LevelRenderer#renderLevel} が<b>戻った直後</b>に置く。
 *
 * <p>{@code WorldRenderEvents.END} はここではない。あれは
 * {@code LevelRenderer#renderLevel} の RETURN へ挿さる＝<b>まだレベル描画の内側</b>で、
 * Iris が自前の framebuffer からメインへ合成し戻す {@code finalizeLevelRendering} と
 * 同じ命令位置を取り合う（どちらが先かは mixin の優先度次第）。シェーダーを入れると
 * 写真が真っ白になる経路がここにある（26.2 Fabric での CF 報告 kirrkirrka 2026-09-05）。
 *
 * <p>呼び出し側の {@code GameRenderer#renderLevel} で戻った直後に取れば、内側の injector
 * すべてより後ろになる。Forge/NeoForge が {@code RenderLevelStageEvent} の AFTER_LEVEL を
 * 出しているのも同じ位置で（1.20.1〜26.2 の patched source で確認）、あちら側の各セルが
 * この不具合を持たないのはそのため。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCaptureMixin {

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
                    shift = At.Shift.AFTER))
    private void ogp$captureAfterLevel(float partialTick, long finishTimeNano, PoseStack poseStack,
                                       CallbackInfo ci) {
        PhotoCaptureClient.onLevelRenderEnd();
    }
}

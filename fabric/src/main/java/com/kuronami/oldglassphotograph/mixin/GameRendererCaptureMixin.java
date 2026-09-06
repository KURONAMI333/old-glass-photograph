package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 露光の 1 フレームを落とす位置。{@code renderLevel} が<b>戻った直後</b>に置く。
 *
 * <p>{@code LevelRenderEvents.END_MAIN} はここではない。あれは frame graph の main pass
 * （{@code LevelRenderer#lambda$addMainPass$0}）の末尾で、<b>まだレベル描画の内側</b>にいる。
 * Iris はレベルの絵を自前の framebuffer へ描いて、mainRenderTarget へ合成し戻すのを
 * {@code LevelRenderer#render} の末尾（frame graph を実行し切った後）で行うので、
 * main pass の中で main を読むと<b>クリア色の真っ白</b>しか入っていない。
 * バニラでは main pass が main へ直接描くので、同じ位置でも絵が入る——
 * だからシェーダーを入れた時だけ真っ白になった（CF 報告 kirrkirrka 2026-09-05）。
 *
 * <p>{@code GameRenderer#renderLevel} の TAIL には Iris も {@code iris$runColorSpace} を
 * 挿しているので、そこへ並べると mixin の優先度勝負になる。呼び出し側の
 * {@code GameRenderer#render} で戻った直後に取れば、どちらの injector よりも確実に後ろになる。
 * バニラ自身のワールドアイコン用スクリーンショットも同じ位置にいる。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCaptureMixin {

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER))
    private void ogp$captureAfterLevel(DeltaTracker deltaTracker, boolean bl, CallbackInfo ci) {
        PhotoCaptureClient.onLevelRenderEnd();
    }
}

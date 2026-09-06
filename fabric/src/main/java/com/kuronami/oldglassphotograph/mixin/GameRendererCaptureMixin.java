package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 露光の 1 フレームを落とす位置。{@code LevelRenderer#render} が<b>戻った直後</b>に置く。
 *
 * <p><b>ここより早く撮ってはいけない。</b>{@code LevelRenderEvents.END_MAIN} は frame graph の
 * main pass（{@code LevelRenderer#lambda$addMainPass$0}）の末尾で、まだレベル描画の内側にいる。
 * Iris はレベルの絵を自前の framebuffer へ描き、mainRenderTarget へ合成し戻すのを
 * {@code LevelRenderer#render} の末尾（frame graph を実行し切った後）で行うので、
 * main pass の中で main を読むと<b>クリア色の一様な面</b>しか入っていない。
 * バニラでは main pass が main へ直接描くので同じ位置でも絵が入る——だからシェーダーを
 * 入れた時だけ写真が白くなった（CF 報告 kirrkirrka 2026-09-05）。
 * 雲・天候・ワールドボーダーも main pass より後ろの frame graph パスなので、ここまで待って初めて入る。
 *
 * <p><b>ここより遅く撮ってもいけない。</b>呼び出し側の {@code GameRenderer#render} まで出ると、
 * {@code GameRenderer#renderLevel} の中の<b>スクリーンエフェクト</b>
 * （{@code ScreenEffectRenderer#submit}）を通過した後になる。水中・炎・顔面ブロックの全画面
 * オーバーレイは HUD 非表示では消えず（消えるのは {@code renderItemActivationAnimation} だけ）、
 * カメラ実体を移していても<b>実プレイヤーの状態</b>で描かれるので、写真に焼き込まれる。
 * 手と 3D クロスヘアは HUD 非表示で消えるが、この 2 つは消えない。
 * 実測（26.2 の {@code GameRenderer} を javap）: {@code LevelRenderer.render} が 405、
 * 深度クリア 498、手 507、スクリーンエフェクト 549、クロスヘア 636。
 *
 * <p>この位置は NeoForge が {@code RenderLevelStageEvent.AfterLevel} を出しているのと同じ命令位置
 * （{@code GameRenderer#renderLevel} の {@code levelRenderer.render(...)} 直後・{@code popPush("hand")} の前）。
 * ローダー間で撮影点が揃う。{@code GameRenderer#renderLevel} の TAIL には Iris も
 * {@code iris$runColorSpace} を挿しているが、こちらは INVOKE 位置なので優先度勝負にならない。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCaptureMixin {

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
                    shift = At.Shift.AFTER))
    private void ogp$captureAfterLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        PhotoCaptureClient.onLevelRenderEnd();
    }
}

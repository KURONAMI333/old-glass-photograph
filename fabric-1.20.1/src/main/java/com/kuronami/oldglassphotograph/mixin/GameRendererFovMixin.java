package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ファインダー中の FOV。NeoForge では ViewportEvent.ComputeFov が担う箇所で、
 * Fabric 1.20.1 に等価イベントが無いためここで再現する。
 *
 * <p>この帯の FOV 算出は {@code GameRenderer#getFov(Camera, float, boolean)}（private・double 戻り。
 * jar 実測）にあるので、HEAD cancellable で固定値へ差し替える＝NeoForge の event.setFOV(70) と同じ効果。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFovMixin {

    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void ogp$fixedFov(Camera camera, float partialTick, boolean useFovSetting,
                              CallbackInfoReturnable<Double> cir) {
        float fov = PhotoCaptureClient.fovOverride();
        if (!Float.isNaN(fov)) {
            cir.setReturnValue((double) fov);
        }
    }
}

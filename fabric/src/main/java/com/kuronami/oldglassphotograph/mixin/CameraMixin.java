package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ファインダー中のカメラ（角度と FOV）。NeoForge では ViewportEvent.ComputeCameraAngles /
 * ComputeFov が担う箇所で、Fabric に等価イベントが無いためここで再現する。
 *
 * <p>角度は camera entity（自前の Marker）の回転を毎フレーム書き戻すことで決める。
 * Camera.update は entity の yRotO→yRot を補間して読むので、両辺を同じ値に置けば
 * 首振りがそのままカメラに反映される。FOV は vanilla の算出（calculateFov）を
 * 固定値で差し替える＝NeoForge の event.setFOV(70) と同じ効果。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Inject(method = "update", at = @At("HEAD"))
    private void ogp$beforeCameraUpdate(DeltaTracker tracker, CallbackInfo ci) {
        PhotoCaptureClient.beforeCameraUpdate();
        Entity marker = PhotoCaptureClient.cameraMarker();
        if (!PhotoCaptureClient.isEngaged() || marker == null) {
            return;
        }
        marker.setYRot(PhotoCaptureClient.desiredYaw());
        marker.setXRot(PhotoCaptureClient.desiredPitch());
        marker.yRotO = marker.getYRot();
        marker.xRotO = marker.getXRot();
    }

    @Inject(method = "calculateFov", at = @At("HEAD"), cancellable = true)
    private void ogp$fixedFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        float fov = PhotoCaptureClient.fovOverride();
        if (!Float.isNaN(fov)) {
            cir.setReturnValue(fov);
        }
    }
}

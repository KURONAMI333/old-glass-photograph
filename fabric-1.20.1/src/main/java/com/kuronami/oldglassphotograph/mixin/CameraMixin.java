package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ファインダー中のカメラの角度。NeoForge では ViewportEvent.ComputeCameraAngles が担う箇所で、
 * Fabric 1.20.1 に等価イベントが無いためここで再現する。
 *
 * <p>26.2 fabric セルは {@code Camera#update} に割り込んでいたが、この帯には update が無く、
 * 角度を確定させる入口は {@code Camera#setup(BlockGetter, Entity, boolean, boolean, float)}
 * （jar 実測）である。setup は後続処理で camera entity の yRot/xRot を読むので、
 * HEAD で自前 Marker の両辺を同じ値に書けば首振りがそのまま反映される。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Inject(method = "setup", at = @At("HEAD"))
    private void ogp$onSetup(BlockGetter level, Entity entity, boolean detached, boolean mirror,
                             float partialTick, CallbackInfo ci) {
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
}

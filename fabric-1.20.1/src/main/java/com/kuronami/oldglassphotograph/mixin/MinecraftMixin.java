package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.kuronami.oldglassphotograph.client.view.PhotographViewer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 覗き中の入力抑止と、写真の面が開いている時の画面開始の扱い。
 *
 * <p>NeoForge では InputEvent.InteractionKeyMappingTriggered / ScreenEvent.Opening が担う箇所で、
 * Fabric 1.20.1 に等価イベントが無いためここで再現する。この帯では use／attack の起点も
 * 画面の開閉も {@code Minecraft} のメソッドに留まっている（jar 実測:
 * {@code startUseItem()} / {@code startAttack()} / public {@code setScreen(Screen)}）。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    /** 覗き・露光のあいだは右クリック（use）を握り潰す。クリックは共通側の tick が直接拾う。 */
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void ogp$blockUse(CallbackInfo ci) {
        if (PhotoCaptureClient.shouldBlockInteractions()) {
            ci.cancel();
        }
    }

    /** 同じく左クリック（attack）。露光中に殴ってしまうのを防ぐ。 */
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void ogp$blockAttack(CallbackInfoReturnable<Boolean> cir) {
        if (PhotoCaptureClient.shouldBlockInteractions()) {
            cir.cancel();
        }
    }

    /**
     * 写真の面が開いている時に画面が開こうとしたら、面を閉じてから続ける。
     * 判定はファインダー → 写真の面 の順。
     */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void ogp$onSetScreen(Screen screen, CallbackInfo ci) {
        if (PhotoCaptureClient.onScreenOpening(screen)
                || PhotographViewer.onScreenOpening(screen)) {
            ci.cancel();
        }
    }
}

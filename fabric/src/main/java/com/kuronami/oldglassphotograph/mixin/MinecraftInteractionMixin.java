package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 覗き中の入力抑止。NeoForge では InputEvent.InteractionKeyMappingTriggered が
 * 担う箇所で、Fabric に等価イベントが無いためここで再現する。
 *
 * <p>26.2 の vanilla 実装（javap 実測）では use／attack の起点はどちらも
 * {@code Minecraft} の private メソッドに留まっている。startAttack は戻り値 boolean のため
 * CallbackInfoReturnable を受ける。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftInteractionMixin {

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
}

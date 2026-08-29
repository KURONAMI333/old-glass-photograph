package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.client.view.PhotographViewer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 写真の面が開いている時の画面開始の扱い。NeoForge では ScreenEvent.Opening が担う箇所で、
 * Fabric に等価イベントが無いためここで再現する。
 *
 * <p>26.2 は画面の開閉が {@code Minecraft#setScreen} から {@code Gui#setScreen} へ移動済み
 * （{@code MC: net/minecraft/client/gui/Gui.java} の javap 実測）なので Gui をターゲットにする。
 * NeoForge の ScreenEvent.Opening が発火していた位置（実際に Screen が差し替わる入口）と
 * 同じ意味の場所である。
 */
@Mixin(Gui.class)
public abstract class GuiSetScreenMixin {

    /**
     * 写真の面が開いている時に画面が開こうとしたら、面を閉じてから続ける。
     * ポーズ画面だけは開かずに止める（{@code PhotographViewer#onScreenOpening} の判定）。
     */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void ogp$onSetScreen(Screen screen, CallbackInfo ci) {
        if (PhotographViewer.onScreenOpening(screen)) {
            ci.cancel();
        }
    }
}

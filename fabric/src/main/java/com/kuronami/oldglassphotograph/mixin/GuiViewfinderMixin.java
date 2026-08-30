package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ファインダーの面を HUD の外側で描く。NeoForge の {@code RegisterGuiLayersEvent#registerAboveAll} が
 * 担う箇所で、Fabric に等価な登録口が無いためここで再現する。
 *
 * <p>{@code HudElementRegistry#addLast} ではこの面は出ない。覗きに入る時に
 * {@link PhotoCaptureClient} が {@code Gui.hud.toggle()} で HUD を消しており、
 * {@code Gui#extractRenderState} は HUD が消えている間 {@code Hud#extractRenderState} を
 * 呼ばない（26.2 の Gui.class を javap で確認）。addLast で足した要素はその中に並ぶので、
 * 暗幕もすりガラスの枠も一緒に消えて、素の視点だけが残る。
 *
 * <p>写真の拡大面（{@code PhotographViewer}）は HUD を消さないので addLast のままでよい。
 */
@Mixin(Gui.class)
public abstract class GuiViewfinderMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void ogp$renderViewfinderAboveAll(DeltaTracker deltaTracker, boolean bl, boolean bl2,
                                              CallbackInfo ci,
                                              @Local GuiGraphicsExtractor graphics) {
        PhotoCaptureClient.renderViewfinder(graphics, deltaTracker);
    }
}

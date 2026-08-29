package com.kuronami.oldglassphotograph.mixin;

import com.kuronami.oldglassphotograph.menu.CartographyPhotographGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * server 側の製図台ガード。NeoForge の PlayerContainerEvent.Open（menu 構築直後に発火）
 * に相当する位置。Fabric に等価イベントが無いため initMenu の末尾で再現する。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "initMenu", at = @At("TAIL"))
    private void ogp$onInitMenu(AbstractContainerMenu menu, CallbackInfo ci) {
        CartographyPhotographGuard.apply(menu);
    }
}

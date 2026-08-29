package com.kuronami.oldglassphotograph.mixin;

import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 大釜の interaction 登録用。vanilla の {@code Dispatcher.put} は package-private で、
 * NeoForge は RegisterCauldronInteractionEvent から届くが Fabric にはイベントが無い。
 * {@code CauldronInteractions.WATER} へ登録するための橋渡し。
 */
@Mixin(CauldronInteraction.Dispatcher.class)
public interface DispatcherAccessor {

    @Invoker("put")
    void ogp$put(Item item, CauldronInteraction interaction);
}

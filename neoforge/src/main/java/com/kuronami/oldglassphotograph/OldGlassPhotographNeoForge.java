package com.kuronami.oldglassphotograph;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(OldGlassPhotograph.MODID)
public final class OldGlassPhotographNeoForge {
    public OldGlassPhotographNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        OgpRegistry.init(modEventBus);
        com.kuronami.oldglassphotograph.menu.CartographyPhotographGuard.init();
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            // client 専用クラスはこの分岐の中でだけロードする（dedicated server で触らせない）
            com.kuronami.oldglassphotograph.client.OgpClient.init(modEventBus);
        }
    }
}

package com.kuronami.oldglassphotograph;

import com.kuronami.oldglassphotograph.client.OgpClient;
import com.kuronami.oldglassphotograph.component.OgpNbt;
import com.kuronami.oldglassphotograph.network.OgpNet;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge 1.20.1 の entry。登録（DeferredRegister）と common setup での配線だけを持ち、
 * client 専用クラスは {@code Dist.isClient()} 分岐の中でだけ触る。
 */
@Mod(OldGlassPhotograph.MODID)
public final class OldGlassPhotographForge {

    public OldGlassPhotographForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        OgpRegistry.init(modBus);
        modBus.addListener(OldGlassPhotographForge::onCommonSetup);

        // common 側の遅延ホルダへ、確定済み RegistryObject を渡す。
        OgpObjects.wire(
                OgpRegistry.CAMERA_BLOCK_ENTITY::get,
                OgpRegistry.DARKROOM_TABLE_BLOCK_ENTITY::get,
                OgpRegistry.WET_PLATE_CAMERA::get,
                OgpRegistry.DARKROOM_TABLE::get,
                OgpRegistry.GLASS_PLATE::get,
                OgpRegistry.PHOTOGRAPH::get,
                OgpRegistry.COLLODION_KIT::get,
                OgpRegistry.DEVELOPER::get,
                OgpRegistry.FIXER::get);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            // client 専用クラスはこの分岐の中でだけロードする（dedicated server で触らせない）
            OgpClient.init(modBus);
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // メッセージ登録と大釜登録は、レジストリ凍結後・ネットワーク開始前のここで行う。
            OgpNet.register();
            OgpRegistry.registerCauldronInteractions();
        });
    }
}

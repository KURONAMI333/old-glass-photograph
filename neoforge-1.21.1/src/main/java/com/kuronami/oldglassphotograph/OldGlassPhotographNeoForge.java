package com.kuronami.oldglassphotograph;

import com.kuronami.oldglassphotograph.component.OgpComponents;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.network.OgpNet;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;

@Mod(OldGlassPhotograph.MODID)
public final class OldGlassPhotographNeoForge {
    public OldGlassPhotographNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        OgpRegistry.init(modEventBus);
        // 製図台ガード（server 側）。menu 構築後に来る PlayerContainerEvent.Open で適用する。
        // Fabric では同じ位置を ServerPlayerMixin（initMenu TAIL）が担う。
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open event) ->
                        com.kuronami.oldglassphotograph.menu.CartographyPhotographGuard.apply(event.getContainer()));
        // common 側から payload を送れるよう、ローダー実装を渡す（server → client 専用経路）。
        OgpNet.setSendToPlayer(PacketDistributor::sendToPlayer);
        // common ホルダは遅延解決なので、登録が完了した後に解決される。タイミング障害は構造的に起きない。
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> wireCommonHolders());
        if (FMLEnvironment.dist.isClient()) {
            // client 専用クラスはこの分岐の中でだけロードする（dedicated server で触らせない）
            com.kuronami.oldglassphotograph.client.OgpClient.init(modEventBus);
            // 1.21.1 には ClientPacketDistributor が無い。client → server も PacketDistributor で通る。
            OgpNet.setSendToServer(net.neoforged.neoforge.network.PacketDistributor::sendToServer);
        }
    }

    private static void wireCommonHolders() {
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
        OgpComponents.wire(
                OgpDataComponents.LATENT_IMAGE::get,
                OgpDataComponents.PLATE_PROCESS::get,
                OgpDataComponents.PLATE_FOG::get,
                OgpDataComponents.PHOTO_CREDIT::get,
                OgpDataComponents.PHOTO_IMAGE::get);
    }
}

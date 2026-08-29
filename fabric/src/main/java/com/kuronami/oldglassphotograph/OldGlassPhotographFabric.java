package com.kuronami.oldglassphotograph;

import com.kuronami.oldglassphotograph.capture.PhotoCaptureController;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.kuronami.oldglassphotograph.network.OgpNet;
import com.kuronami.oldglassphotograph.network.PhotoCaptureAbortPayload;
import com.kuronami.oldglassphotograph.network.PhotoMapPixelsPayload;
import com.kuronami.oldglassphotograph.network.ShutterOpenPayload;
import com.kuronami.oldglassphotograph.network.ShutterRequestPayload;
import com.kuronami.oldglassphotograph.network.ViewfinderClosePayload;
import com.kuronami.oldglassphotograph.network.ViewfinderOpenPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.cauldron.CauldronInteractions;
import net.minecraft.world.item.ItemStack;

/**
 * Fabric entry。登録・payload 型の登録・server 受信配線・creative タブ内容・
 * 水入り大釜での洗いを設定する。client 側は {@code OldGlassPhotographFabricClient}。
 */
public final class OldGlassPhotographFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // static 初期化で即時登録（onInitialize 中はレジストリが開いている）→ common ホルダへ wire。
        OgpFabricRegistry.init();

        // server からの payload 送信経路。client からの送信は client entry で wire する。
        OgpNet.setSendToPlayer(ServerPlayNetworking::send);

        // payload 型の登録。26.2 fabric-networking: playC2S/playS2C は serverboundPlay/clientboundPlay に rename。
        PayloadTypeRegistry.clientboundPlay().register(ViewfinderOpenPayload.TYPE, ViewfinderOpenPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShutterOpenPayload.TYPE, ShutterOpenPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ViewfinderClosePayload.TYPE, ViewfinderClosePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ShutterRequestPayload.TYPE, ShutterRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PhotoMapPixelsPayload.TYPE, PhotoMapPixelsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PhotoCaptureAbortPayload.TYPE, PhotoCaptureAbortPayload.CODEC);

        // server 受信。NeoForge の payload handler は main thread で走るので、ここも execute で揃える。
        ServerPlayNetworking.registerGlobalReceiver(ShutterRequestPayload.TYPE, (payload, context) ->
                context.server().execute(() -> PhotoCaptureController.openShutter(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(PhotoMapPixelsPayload.TYPE, (payload, context) ->
                context.server().execute(() -> PhotoCaptureController.receivePixels(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(PhotoCaptureAbortPayload.TYPE, (payload, context) ->
                context.server().execute(() -> PhotoCaptureController.abortCapture(context.player(), payload)));

        // 独自タブの中身を工程順で流す（OgpRegistry.buildCreativeTab と同じ並び）。
        CreativeModeTabEvents.modifyOutputEvent(OgpFabricRegistry.TAB_KEY)
                .register(output -> {
                    output.accept(new ItemStack(OgpFabricRegistry.GLASS_PLATE));
                    output.accept(new ItemStack(OgpFabricRegistry.COLLODION_KIT));
                    output.accept(new ItemStack(OgpFabricRegistry.DEVELOPER));
                    output.accept(new ItemStack(OgpFabricRegistry.FIXER));
                    output.accept(new ItemStack(OgpFabricRegistry.WET_PLATE_CAMERA_ITEM));
                    output.accept(new ItemStack(OgpFabricRegistry.DARKROOM_TABLE_ITEM));
                    // 写真はタブに出さない（NeoForge 版と同じ判断）。
                });

        // 水入り大釜で Glass Plate を洗える形にする。NeoForge は RegisterCauldronInteractionEvent、
        // Fabric は vanilla Dispatcher への put（mixin accessor で開く）。id "water" の dispatcher 実体は
        // CauldronInteractions.WATER（AbstractCauldronBlock が直持ちするインスタンスと同じもの）。
        ((com.kuronami.oldglassphotograph.mixin.DispatcherAccessor) (Object) CauldronInteractions.WATER)
                .ogp$put(OgpFabricRegistry.GLASS_PLATE, GlassPlateItem::washInCauldron);

        // server 側の製図台ガード。NeoForge の PlayerContainerEvent.Open 相当は
        // mixin（ServerPlayerMixin）が initMenu の後で拾い、判定本体（CartographyPhotographGuard.apply）を
        // 共通クラスから呼ぶ。init() は NeoForge のイベントバス登録なので Fabric では呼ばない。
    }
}

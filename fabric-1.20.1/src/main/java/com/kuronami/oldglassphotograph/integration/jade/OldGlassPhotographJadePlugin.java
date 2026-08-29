package com.kuronami.oldglassphotograph.integration.jade;

import com.kuronami.oldglassphotograph.block.DarkroomTableBlock;
import com.kuronami.oldglassphotograph.block.DarkroomTableBlockEntity;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlock;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade からだけ発見される Old Glass Photograph の表示連携。
 *
 * <p>Jade を必須依存にしないため、このクラスは MOD 本体から参照しない。
 * 発見は fabric.mod.json の {@code "jade"} entrypoint（26.2 fabric セルと同じ機構。
 * {@code @WailaPlugin} アノテーションは Fabric では発見に使われない）。
 */
@WailaPlugin
public final class OldGlassPhotographJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        // Jade 11.x（1.20.1）の registerBlockDataProvider は Class<? extends BlockEntity> を受ける
        // （26.x / 1.21.x の Class<? extends Block> とは違う。jade jar javap 実測）。
        registration.registerBlockDataProvider(OldGlassPhotographJadeDataProvider.INSTANCE,
                WetPlateCameraBlockEntity.class);
        registration.registerBlockDataProvider(OldGlassPhotographJadeDataProvider.INSTANCE,
                DarkroomTableBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        // Jade は専用サーバーでこのメソッドを呼ばないため、表示側 provider はここでだけ参照する。
        registration.registerBlockComponent(OldGlassPhotographJadeClientProvider.INSTANCE, WetPlateCameraBlock.class);
        registration.registerBlockComponent(OldGlassPhotographJadeClientProvider.INSTANCE, DarkroomTableBlock.class);
    }
}

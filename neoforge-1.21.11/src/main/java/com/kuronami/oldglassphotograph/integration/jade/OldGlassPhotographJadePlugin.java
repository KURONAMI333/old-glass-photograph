package com.kuronami.oldglassphotograph.integration.jade;

import com.kuronami.oldglassphotograph.block.DarkroomTableBlock;
import com.kuronami.oldglassphotograph.block.DarkroomTableBlockEntity;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlock;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade からだけ発見される Old Glass Photograph の表示連携。
 *
 * <p>Jade を必須依存にしないため、このクラスは MOD 本体から参照しない。
 */
@WailaPlugin
public final class OldGlassPhotographJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(OldGlassPhotographJadeDataProvider.INSTANCE,
                WetPlateCameraBlock.class);
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

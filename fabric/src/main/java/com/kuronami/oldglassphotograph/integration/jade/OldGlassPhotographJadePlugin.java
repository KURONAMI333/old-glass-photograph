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
 * Jade からだけ発見される Old Glass Photograph の表示連携（Fabric 版）。
 *
 * <p>Jade を必須依存にしないため、このクラスは MOD 本体から参照しない。
 * Fabric 版の発見機構は fabric.mod.json の {@code "jade"} エントリポイントで
 * （Jade 本体の fabric.mod.json が自前プラグインを同じキーで列挙しているのと同じ形。
 * {@code CommonProxy$Entrypoint} が {@code EntrypointContainer<IWailaPlugin>} を包む＝jar 実測）。
 * Jade が無い環境ではこのエントリポイントは誰にも要求されず、クラスもロードされない。
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

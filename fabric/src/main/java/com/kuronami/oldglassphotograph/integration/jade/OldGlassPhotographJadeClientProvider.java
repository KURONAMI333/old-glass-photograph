package com.kuronami.oldglassphotograph.integration.jade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade のクライアント側ツールチップへ、同期済みの写真機材情報を追加する。
 *
 * <p>このクラスは {@link OldGlassPhotographJadePlugin#registerClient} からだけ参照する。
 */
final class OldGlassPhotographJadeClientProvider implements IBlockComponentProvider {

    static final OldGlassPhotographJadeClientProvider INSTANCE = new OldGlassPhotographJadeClientProvider();

    private static final Identifier UID = Identifier.fromNamespaceAndPath("old_glass_photograph", "jade");

    private OldGlassPhotographJadeClientProvider() {
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (data.getIntOr(OldGlassPhotographJadeDataProvider.TYPE, 0) == OldGlassPhotographJadeDataProvider.CAMERA) {
            appendCamera(tooltip, data);
        } else if (data.getIntOr(OldGlassPhotographJadeDataProvider.TYPE, 0)
                == OldGlassPhotographJadeDataProvider.DARKROOM) {
            appendDarkroom(tooltip, data);
        }
    }

    @Override
    public Identifier getUid() {
        return UID;
    }

    private static void appendCamera(ITooltip tooltip, CompoundTag data) {
        if (data.contains(OldGlassPhotographJadeDataProvider.PLATE_NAME)) {
            tooltip.add(Component.translatable("jade.old_glass_photograph.camera.plate",
                    data.getStringOr(OldGlassPhotographJadeDataProvider.PLATE_NAME, "")));
        } else {
            tooltip.add(Component.translatable("jade.old_glass_photograph.camera.empty"));
        }
    }

    private static void appendDarkroom(ITooltip tooltip, CompoundTag data) {
        if (data.contains(OldGlassPhotographJadeDataProvider.PLATE_NAME)) {
            tooltip.add(Component.translatable("jade.old_glass_photograph.darkroom.plate",
                    data.getStringOr(OldGlassPhotographJadeDataProvider.PLATE_NAME, "")));
        } else {
            tooltip.add(Component.translatable("jade.old_glass_photograph.darkroom.empty"));
        }
        if (data.getBooleanOr(OldGlassPhotographJadeDataProvider.WORKING, false)) {
            tooltip.add(Component.translatable("jade.old_glass_photograph.darkroom.progress",
                    data.getIntOr(OldGlassPhotographJadeDataProvider.PROGRESS, 0)));
        }
    }
}

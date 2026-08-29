package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.kuronami.oldglassphotograph.item.PlateUseProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * client 側の共通処理。ローダー型を持たないので、Fabric のイベント／mixin から
 * 同じメソッドを呼んで挙動を揃える（NeoForge セルの同名クラスと同じ役割）。
 */
public final class OgpClientCommon {

    private OgpClientCommon() {
    }

    /**
     * 手で進めている板の進み具合を毎 tick 拾って {@link PlateUseProgress} へ置く。
     *
     * <p>1.20.1 の {@code ItemStack#getUseDuration} は player を受けないので、
     * 長さは stack 単位で引く（{@link GlassPlateItem#getUseDuration} 参照）。
     */
    public static void trackPlateUseProgress() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.isUsingItem()) {
            PlateUseProgress.clear();
            return;
        }
        ItemStack using = player.getUseItem();
        int duration = using.getUseDuration();
        if (!(using.getItem() instanceof GlassPlateItem) || duration <= 0) {
            PlateUseProgress.clear();
            return;
        }
        PlateUseProgress.set(using, Math.min(1.0F, player.getTicksUsingItem() / (float) duration));
    }
}

package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.kuronami.oldglassphotograph.item.PlateUseProgress;
import com.kuronami.oldglassphotograph.menu.CartographyPhotographGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * client 側の共通処理。ローダー型を持たないので、NeoForge のイベントでも
 * Fabric のイベント／mixin から同じメソッドを呼んで挙動を揃える。
 */
public final class OgpClientCommon {

    private OgpClientCommon() {
    }

    /**
     * 手で進めている板の進み具合を毎 tick 拾って {@link PlateUseProgress} へ置く。
     *
     * <p>{@code useItemRemaining} は client でも同じように減るので、server へ問い合わせずに読める。
     * 使っていない tick は必ず消すので、バーが満ちたまま残る経路が無い。
     */
    public static void trackPlateUseProgress() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.isUsingItem()) {
            PlateUseProgress.clear();
            return;
        }
        ItemStack using = player.getUseItem();
        int duration = using.getUseDuration(player);
        if (!(using.getItem() instanceof GlassPlateItem) || duration <= 0) {
            PlateUseProgress.clear();
            return;
        }
        PlateUseProgress.set(using, Math.min(1.0F, player.getTicksUsingItem() / (float) duration));
    }

    /**
     * client 側の製図台 menu にも写真よけを掛ける。
     *
     * <p>server 側の container open 時の適用が触るのは server の menu だけで、
     * client は別インスタンスを持つ（{@code ClientboundOpenScreenPacket} から組む）。
     * client が素のままだとクリックを「置けた」と予測してしまい、server の拒否で跳ね返る。
     * 判定の正本は server 側で、ここは見た目のちらつきを消すためだけにある。
     */
    public static void applyMenuGuard(Screen screen) {
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            CartographyPhotographGuard.apply(containerScreen.getMenu());
        }
    }
}

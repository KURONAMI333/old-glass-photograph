package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.kuronami.oldglassphotograph.client.render.PhotographHandRenderer;
import com.kuronami.oldglassphotograph.client.render.PhotographSpecialRenderer;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.kuronami.oldglassphotograph.item.PlateUseProgress;
import com.kuronami.oldglassphotograph.menu.CartographyPhotographGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** client 側の入口。 */
public final class OgpClient {

    private OgpClient() {
    }

    public static void init(IEventBus modBus) {
        PhotoCaptureClient.init(modBus);
        modBus.addListener(PlateStageProperty::register);
        modBus.addListener(OgpClient::registerSpecialModelRenderers);
        // RenderHandEvent は game bus（NeoForge.EVENT_BUS）側。
        PhotographHandRenderer.init();
        NeoForge.EVENT_BUS.addListener(OgpClient::onScreenInit);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, OgpClient::onClientTick);
    }

    /**
     * 手で進めている板の進み具合を毎 tick 拾って {@link PlateUseProgress} へ置く。
     *
     * <p>{@code useItemRemaining} は client でも同じように減るので、server へ問い合わせずに読める。
     * 使っていない tick は必ず消すので、バーが満ちたまま残る経路が無い。
     */
    private static void onClientTick(ClientTickEvent.Post event) {
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
     * <p>server 側の {@code PlayerContainerEvent.Open} が触るのは server の menu だけで、
     * client は別インスタンスを持つ（{@code ClientboundOpenScreenPacket} から組む）。
     * client が素のままだとクリックを「置けた」と予測してしまい、server の拒否で跳ね返る。
     * 判定の正本は server 側で、ここは見た目のちらつきを消すためだけにある。
     */
    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?> screen) {
            CartographyPhotographGuard.apply(screen.getMenu());
        }
    }

    private static void registerSpecialModelRenderers(RegisterSpecialModelRendererEvent event) {
        event.register(PhotographSpecialRenderer.ID, PhotographSpecialRenderer.Unbaked.MAP_CODEC);
    }
}

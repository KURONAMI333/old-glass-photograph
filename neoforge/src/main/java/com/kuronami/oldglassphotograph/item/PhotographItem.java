package com.kuronami.oldglassphotograph.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.function.Consumer;

/**
 * A wet-plate photograph backed by vanilla map saved data.
 *
 * <p>Keeping the {@link MapItem} base class is intentional: it preserves the vanilla persistence
 * and dedicated-server synchronization path for the attached map ID. The public item surface is
 * deliberately photographic rather than map-like.
 */
public final class PhotographItem extends MapItem {

    public PhotographItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.old_glass_photograph.photograph");
    }

    @Override
    protected MapItemSavedData getCustomMapData(ItemStack stack, Level level) {
        // 両側で実データを返す。client 側で null を返すと、額縁が state.mapId を立てられず
        // （MC: net/minecraft/client/renderer/entity/ItemFrameRenderer.java:135）
        // どの写真も同じ 16px のアイテム絵になる。
        //
        // 一人称の紙地図の枠は「ここを null にする」ではなく、描画側の分岐を
        // RenderHandEvent で迂回して外している（PhotographHandRenderer 参照）。
        return super.getCustomMapData(stack, level);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> adder, TooltipFlag flag) {
        // Do not delegate: MapItem adds the map ID, scale, and locked-map tooltip lines.
    }

    /**
     * じっくり見る面を開く・閉じる（{@code MODJAM_DECISIONS_OGP.md} §32-5）。
     *
     * <p><b>server では何もしない。</b>見る面は client の描画層 1 枚で、持ち物も画面も変えない。
     * server が {@code CONSUME} を返すのは「このクリックはここで終わり」を伝えるためだけで、
     * 腕を振るのは client 側の {@code SUCCESS}（{@code SwingSource.CLIENT}）に任せる。
     *
     * <p>ブロックを狙ってのクリックは {@code useItemOn} が先に処理されるので、
     * 写真を持ったままチェストを開く経路は塞がらない。
     */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) {
            return InteractionResult.CONSUME;
        }
        return PhotographViewRequest.toggle(hand) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    @Override
    public void onCraftedPostProcess(ItemStack stack, Level level) {
        // Ignore MAP_POST_PROCESSING so cartography-table scale/lock operations cannot affect a photograph.
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // MapItem normally adds/removes banner decorations here; photographs never carry map markers.
        return InteractionResult.PASS;
    }
}

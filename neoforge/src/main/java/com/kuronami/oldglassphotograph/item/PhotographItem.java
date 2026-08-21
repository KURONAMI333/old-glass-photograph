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
        // The server still resolves map data for vanilla persistence and synchronization. On the
        // client, returning null keeps map-specific renderers (including item frames) on our model.
        return level.isClientSide() ? null : super.getCustomMapData(stack, level);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> adder, TooltipFlag flag) {
        // Do not delegate: MapItem adds the map ID, scale, and locked-map tooltip lines.
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // A viewer belongs to a later feature; do not invoke any map-specific use behavior here.
        return InteractionResult.CONSUME;
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

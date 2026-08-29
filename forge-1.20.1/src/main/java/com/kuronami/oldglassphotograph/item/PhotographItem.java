package com.kuronami.oldglassphotograph.item;

import com.kuronami.oldglassphotograph.OgpAdvancements;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A wet-plate photograph backed by vanilla map saved data.
 *
 * <p>Keeping the {@link MapItem} base class is intentional: it preserves the vanilla persistence
 * and dedicated-server synchronization path for the attached map id. On 1.20.1 the map id lives in
 * the vanilla {@code map} NBT tag, written through
 * {@code com.kuronami.oldglassphotograph.component.OgpNbt} at develop time;
 * {@code MapItem.inventoryTick} then keeps the pixels flowing to whoever holds the photo, and
 * {@code ItemFrame.getFramedMapId()} reads the same tag, so framed photographs render without any
 * extra code. The public item surface is deliberately photographic rather than map-like.
 */
public final class PhotographItem extends MapItem {

    public PhotographItem(Properties properties) {
        super(properties);
    }

    /**
     * Forge 1.20.1 の BEWLR 差し替え（この帯には RegisterClientExtensionsEvent が無く、
     * Item への patch メソッド {@code initializeClient} が唯一の登録面。jar bytecode 実測:
     * ItemRenderer は {@code isCustomRenderer()==true} のときだけ extensions を引くので、
     * models/item/photograph.json を builtin/entity にしてある）。client 専用クラスは
     * lambda 本体でだけ参照する＝dedicated server で解決されない。
     */
    @Override
    public void initializeClient(java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> consumer.accept(
                        com.kuronami.oldglassphotograph.client.render.PhotographItemRenderer.EXTENSIONS));
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.old_glass_photograph.photograph");
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        // Do not delegate: MapItem adds the map ID, scale, and locked-map tooltip lines.
    }

    /** じっくり見る面を開く・閉じる（§32-5）。server では節目の進捗を与えるだけ。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) {
            if (player instanceof ServerPlayer serverPlayer) {
                OgpAdvancements.award(serverPlayer, OgpAdvancements.A_CLOSER_LOOK);
            }
            return InteractionResultHolder.consume(player.getItemInHand(hand));
        }
        ItemStack stack = player.getItemInHand(hand);
        return PhotographViewRequest.toggle(hand)
                ? InteractionResultHolder.success(stack)
                : InteractionResultHolder.pass(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // MapItem normally adds/removes banner decorations here; photographs never carry map markers.
        return InteractionResult.PASS;
    }
}

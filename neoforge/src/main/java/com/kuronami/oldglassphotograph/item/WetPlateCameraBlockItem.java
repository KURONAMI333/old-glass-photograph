package com.kuronami.oldglassphotograph.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

/**
 * Wet Plate Camera を設置する BlockItem。設置時の向きが撮影方向になる。
 *
 * <p>高さ2ブロックの構造物（{@code MODJAM_DECISIONS_OGP.md} §18）。1アイテムで下＝台・
 * 上＝カメラ本体を置く。vanilla の {@link net.minecraft.world.item.DoubleHighBlockItem}
 * と同じ型で、置く前に上のマスを明示的に空ける（水源ブロックなら水のまま残す）。
 */
public class WetPlateCameraBlockItem extends BlockItem {
    public WetPlateCameraBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    protected boolean placeBlock(BlockPlaceContext context, BlockState placementState) {
        Level level = context.getLevel();
        BlockPos above = context.getClickedPos().above();
        BlockState aboveState = level.isWaterAt(above) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        level.setBlock(above, aboveState, 27);
        return super.placeBlock(context, placementState);
    }

    /**
     * 撮り方の導線。<b>GUI を作らない</b>ので（{@code MODJAM_DECISIONS_OGP.md} §10）、
     * 置く前に分かる場所はここしかない。
     *
     * <p>2 行に絞る。1 行目は設置の向き（これを知らないと構図が作れない）、
     * 2 行目は立ち位置（B-1 で後ろからしか操作できないので、知らないと何も起きない）。
     * 秒数や光量は書かない（§15）。
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> adder, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, adder, flag);
        adder.accept(line("The lens points the way you are facing when you place it."));
        adder.accept(line("Stand close behind it and right-click to look through it."));
    }

    private static Component line(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }
}

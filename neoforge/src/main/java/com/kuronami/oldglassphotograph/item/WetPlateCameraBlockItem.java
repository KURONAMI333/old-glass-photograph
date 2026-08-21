package com.kuronami.oldglassphotograph.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

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
}

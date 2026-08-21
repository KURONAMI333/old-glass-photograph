package com.kuronami.oldglassphotograph.item;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;

/** Wet Plate Camera を設置する BlockItem。設置時の向きが撮影方向になる。 */
public class WetPlateCameraBlockItem extends BlockItem {
    public WetPlateCameraBlockItem(Block block, Properties properties) {
        super(block, properties);
    }
}

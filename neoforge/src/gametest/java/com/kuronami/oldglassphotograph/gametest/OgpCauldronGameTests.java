package com.kuronami.oldglassphotograph.gametest;

import com.kuronami.oldglassphotograph.OgpRegistry;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * commit 7f03517 の検証: 水入り大釜で Glass Plate を洗うと、工程のどの段階からでも
 * 一回で素のガラス板に戻ること。ストア説明文が謳う
 * "Rinsing a plate in a water cauldron does the same at any stage, and it's the only way
 * to give up on a developed plate without spending Fixer." を段階ごとに直接確かめる。
 *
 * <p>登録配線（{@code OgpRegistry.registerCauldronInteractions} が mod bus 上の
 * {@code RegisterCauldronInteractionEvent.Interaction} 経由で {@code water} dispatcher に登録する）
 * ごと検証するため、{@code GlassPlateItem.washInCauldron} を直接呼ばず、
 * 実際のブロック右クリック経路（{@code BlockState#useItemOn} → {@code AbstractCauldronBlock}
 * の dispatcher lookup）を {@link GameTestHelper#useBlock(BlockPos, Player)} で踏む。
 */
public final class OgpCauldronGameTests {

    private static final BlockPos CAULDRON_POS = new BlockPos(1, 1, 1);

    private OgpCauldronGameTests() {
    }

    private static void failAt(GameTestHelper helper, String message, BlockPos pos) {
        helper.fail(Component.literal(message), pos);
    }

    /** 塗布済み（SENSITIZED）。まだ潜像は無い。 */
    private static ItemStack sensitizedPlate(long gameTime) {
        ItemStack stack = new ItemStack(OgpRegistry.GLASS_PLATE.get());
        stack.set(OgpDataComponents.PLATE_PROCESS.get(), new PlateProcess(
                PlateProcess.Stage.SENSITIZED, gameTime + GlassPlateItem.WET_TICKS, GlassPlateItem.WET_TICKS / 20));
        return stack;
    }

    /** 露光済み（EXPOSED）。潜像を持つ。 */
    private static ItemStack exposedPlate(long gameTime) {
        ItemStack stack = new ItemStack(OgpRegistry.GLASS_PLATE.get());
        stack.set(OgpDataComponents.PLATE_PROCESS.get(), new PlateProcess(
                PlateProcess.Stage.EXPOSED, gameTime + GlassPlateItem.WET_TICKS, GlassPlateItem.WET_TICKS / 20));
        stack.set(OgpDataComponents.LATENT_IMAGE.get(), new LatentImage(new byte[LatentImage.SIZE], 0, 0));
        return stack;
    }

    /** 現像済み（DEVELOPED）。乾燥期限を持たない（wetUntil=0）。定着液を使わずここでだけ回収できる。 */
    private static ItemStack developedPlate() {
        ItemStack stack = new ItemStack(OgpRegistry.GLASS_PLATE.get());
        stack.set(OgpDataComponents.PLATE_PROCESS.get(), new PlateProcess(PlateProcess.Stage.DEVELOPED, 0L, 0));
        stack.set(OgpDataComponents.LATENT_IMAGE.get(), new LatentImage(new byte[LatentImage.SIZE], 0, 0));
        return stack;
    }

    /** 水位2の水入り大釜を用意し、板を洗い、素のガラス板へ戻ること＋水位が1減ることを確認する。 */
    private static void washAndAssertReset(GameTestHelper helper, ItemStack plate, String stageLabel) {
        BlockState cauldron = Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 2);
        helper.setBlock(CAULDRON_POS, cauldron);

        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.setItemInHand(InteractionHand.MAIN_HAND, plate);
        helper.useBlock(CAULDRON_POS, player);

        ItemStack result = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (GlassPlateItem.process(result) != null) {
            failAt(helper, stageLabel + ": still has PLATE_PROCESS after washing", CAULDRON_POS);
            return;
        }
        if (result.has(OgpDataComponents.LATENT_IMAGE.get())) {
            failAt(helper, stageLabel + ": still has LATENT_IMAGE after washing", CAULDRON_POS);
            return;
        }
        if (result.getMaxStackSize() != GlassPlateItem.BLANK_MAX_STACK) {
            failAt(helper, stageLabel + ": max stack size " + result.getMaxStackSize()
                    + " does not match blank plate's " + GlassPlateItem.BLANK_MAX_STACK, CAULDRON_POS);
            return;
        }

        BlockState afterCauldron = helper.getLevel().getBlockState(helper.absolutePos(CAULDRON_POS));
        if (!afterCauldron.is(Blocks.WATER_CAULDRON) || afterCauldron.getValue(LayeredCauldronBlock.LEVEL) != 1) {
            failAt(helper, stageLabel + ": cauldron water level did not drop from 2 to 1 (state=" + afterCauldron + ")",
                    CAULDRON_POS);
            return;
        }
        helper.succeed();
    }

    public static void washSensitizedPlateResetsToBlank(GameTestHelper helper) {
        washAndAssertReset(helper, sensitizedPlate(helper.getLevel().getGameTime()), "sensitized");
    }

    public static void washExposedPlateResetsToBlank(GameTestHelper helper) {
        washAndAssertReset(helper, exposedPlate(helper.getLevel().getGameTime()), "exposed");
    }

    public static void washDevelopedPlateResetsToBlank(GameTestHelper helper) {
        washAndAssertReset(helper, developedPlate(), "developed");
    }

    /**
     * 水位1の水入り大釜で洗うと空の {@code minecraft:cauldron} に変わること
     * （{@code LayeredCauldronBlock.lowerFillLevel} が level 0 で empty cauldron に切り替える）。
     */
    public static void washAtCauldronLevelOneEmptiesCauldron(GameTestHelper helper) {
        BlockState cauldron = Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 1);
        helper.setBlock(CAULDRON_POS, cauldron);

        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.setItemInHand(InteractionHand.MAIN_HAND, developedPlate());
        helper.useBlock(CAULDRON_POS, player);

        BlockState afterCauldron = helper.getLevel().getBlockState(helper.absolutePos(CAULDRON_POS));
        if (!afterCauldron.is(Blocks.CAULDRON)) {
            failAt(helper, "cauldron did not empty after washing at water level 1 (state=" + afterCauldron + ")",
                    CAULDRON_POS);
            return;
        }
        ItemStack result = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (GlassPlateItem.process(result) != null) {
            failAt(helper, "plate still has PLATE_PROCESS after washing at water level 1", CAULDRON_POS);
            return;
        }
        helper.succeed();
    }
}

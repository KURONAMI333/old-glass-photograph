package com.kuronami.oldglassphotograph.block;

import com.kuronami.oldglassphotograph.OgpRegistry;
import com.kuronami.oldglassphotograph.capture.PhotoCaptureController;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * 設置型の湿板カメラ。<b>構図は設置位置と FACING だけで決まる</b>（kura 受理済み）。
 * 高さ2ブロック（1×1×2）の構造物（{@code MODJAM_DECISIONS_OGP.md} §18）。
 * vanilla の {@link net.minecraft.world.level.block.DoorBlock} /
 * {@link net.minecraft.world.level.block.DoublePlantBlock} と同じ型で、
 * 1アイテムで下＝台・上＝カメラ本体の2ブロックを置く。
 *
 * <p>この塊で通す操作:
 * <ul>
 *   <li>Glass Plate を持って右クリック = 装填（どの向きからでもよい）</li>
 *   <li>素手で右クリック = ファインダーを覗く。<b>カメラの後ろに立っている時だけ</b>
 *       （{@link #isBehind}）。撮影はここからもう一度クリックする
 *       （{@code MODJAM_DECISIONS_OGP.md} §31。露光の進行は
 *       {@link com.kuronami.oldglassphotograph.capture.PhotoCaptureController} が持つ）</li>
 *   <li>スニーク + 素手で右クリック = 装填 Plate の取り出し（どの向きからでもよい）</li>
 * </ul>
 * <b>BlockEntity は下半分だけが持つ。</b>上半分への操作は全て下半分へ転送する
 * （player はレンズのある上半分を触るので、上半分でも同じように効くことが必須）。
 */
public class WetPlateCameraBlock extends BaseEntityBlock {

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    public static final MapCodec<WetPlateCameraBlock> CODEC = simpleCodec(WetPlateCameraBlock::new);

    public WetPlateCameraBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    /** 覗ける立ち位置の上限距離（水平・ブロック）。これより遠いと「覗いている」と言えない。 */
    private static final double PEEK_RANGE = 2.5;

    /** 下半分の位置。上半分を渡されたら1つ下げる。どちらの半分に対しても呼べる。 */
    private static BlockPos basePos(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    /**
     * player がカメラの<b>後ろ</b>に立っているか（{@code MODJAM_DECISIONS_OGP.md} B-1）。
     * 見ている向きではなく立ち位置で決める。
     *
     * <p>範囲は<b>真後ろを中心に左右 45 度の扇形・水平 {@value #PEEK_RANGE} マス以内</b>。
     * 45 度は「横成分が後ろ成分を超えない」＝カメラの側面より後ろ寄りに居ること。
     * 設置者は必ず<b>後ろ側</b>に居る（{@link #getStateForPlacement} が
     * レンズを設置者の見ている先へ向ける）が、離して置けば距離が足りないので
     * 覗くには寄る必要がある。距離の上限は「遠くからも撮れてしまう」（kura 指摘）を塞ぐ。
     *
     * @param facing  カメラの FACING（レンズの向き）
     * @param basePos 下半分の位置
     */
    public static boolean isBehind(Direction facing, BlockPos basePos, Player player) {
        double dx = player.getX() - (basePos.getX() + 0.5);
        double dz = player.getZ() - (basePos.getZ() + 0.5);
        // レンズの向きの逆方向の成分。正なら後ろ側の半空間に居る。
        double back = -(dx * facing.getStepX() + dz * facing.getStepZ());
        if (back <= 0.0) {
            return false;
        }
        double side = Math.abs(dx * facing.getStepZ() - dz * facing.getStepX());
        return side <= back && dx * dx + dz * dz <= PEEK_RANGE * PEEK_RANGE;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        // 天井が低い場所には置けない（上に空きが無い = above が置換できない）。
        if (pos.getY() >= level.getMaxY() || !level.getBlockState(pos.above()).canBeReplaced(context)) {
            return null;
        }
        // レンズは設置者が見ている先を向く。したがって設置者は必ずカメラの後ろ側に立つ
        // （B-1 の立ち位置判定 isBehind が見る半空間の中）。
        //
        // 向きの鎖（26.2 の一次ソースで確認済み・2026-08-22）:
        //   getHorizontalDirection() = player.getDirection()   … UseOnContext L68-70
        //   blockstate の facing=east → y:90 が model の -z を east へ回す
        //                                     … vanilla furnace の blockstate + orientable_with_bottom
        //                                       ("north": "#front") と同じ写像
        //   model の -z 端 = lens_barrel / +z 端 = rear_standard（すりガラス）
        //   撮影視点の yaw = FACING.toYRot()（NORTH=180 等）→ 視線ベクトル (0,0,-1) = north
        // したがってレンズ・blockstate・撮影視点は全部 FACING で一致している。
        // ここに getOpposite() を足すとレンズが設置者を向く（＝自撮り）ので足さない。
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection())
                .setValue(HALF, DoubleBlockHalf.LOWER);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        // 1アイテムで2ブロック分を置く（vanilla のドア/ベッドと同じ型）。下＝台、上＝カメラ本体。
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), 3);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
        }
        return super.canSurvive(state, level, pos);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction directionToNeighbour, BlockPos neighbourPos, BlockState neighbourState,
                                     RandomSource random) {
        // 下が消えた上半分は独りで浮けない（DoublePlantBlock と同じ物理フォールバック。
        // ピストン・爆発など playerWillDestroy を通らない除去の受け皿）。
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER && directionToNeighbour == Direction.DOWN
                && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    /**
     * player が破壊した瞬間（実際の除去より前）に対になる半分も静かに消す
     * （vanilla {@code DoorBlock.playerWillDestroy} と同じ型。どちらを壊しても両方消える）。
     *
     * <p>装填 Plate の救出はここではなく {@link WetPlateCameraBlockEntity#preRemoveSideEffects}
     * が持つ（BE が生きている間に効く hook。どちらの半分をどう壊しても、下半分の BE が
     * 除去されるところで必ず通る）。
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            DoubleBlockHalf half = state.getValue(HALF);
            BlockPos otherPos = half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState otherState = level.getBlockState(otherPos);
            if (otherState.is(this) && otherState.getValue(HALF) != half) {
                level.setBlock(otherPos, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(player, 2001, otherPos, Block.getId(otherState));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // BlockEntity は下半分だけが持つ。
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            return null;
        }
        return new WetPlateCameraBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                           BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, OgpRegistry.CAMERA_BLOCK_ENTITY.get(),
                WetPlateCameraBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(OgpRegistry.GLASS_PLATE.get())) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos basePos = basePos(pos, state);
        if (!(level.getBlockEntity(basePos) instanceof WetPlateCameraBlockEntity camera)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.FAIL;
        }
        if (camera.hasPlate()) {
            serverPlayer.sendSystemMessage(Component.literal("A plate is already loaded."), true);
            return InteractionResult.FAIL;
        }
        // 入れてよいのは「濡れていて、まだ潜像を持たない」板だけ。それ以外は理由を出して
        // 何も消費しない（板が黙って失われる経路を作らない）。
        if (GlassPlateItem.resolveDryOut(stack, level.getGameTime())) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "The collodion dried out. The plate is clean again."), true);
            return InteractionResult.FAIL;
        }
        if (GlassPlateItem.isExposed(stack)) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "This plate already holds a latent image. Develop it first."), true);
            return InteractionResult.FAIL;
        }
        if (!GlassPlateItem.isReadyToLoad(stack)) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "This plate is not sensitized. Coat it with a Collodion Kit first."), true);
            return InteractionResult.FAIL;
        }
        camera.setPlate(stack.split(1));
        serverPlayer.sendSystemMessage(Component.literal(
                "Plate loaded. Stand behind the camera and right-click to look through it."), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos basePos = basePos(pos, state);
        if (!(level.getBlockEntity(basePos) instanceof WetPlateCameraBlockEntity camera)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.FAIL;
        }
        if (player.isShiftKeyDown()) {
            return ejectPlate(camera, player);
        }
        // 覗けるのはカメラの後ろに立っている時だけ（MODJAM_DECISIONS_OGP.md B-1）。
        // 前や横から触れると自撮りができ、遠くからも撮れてしまう。史実でも写真家は
        // 暗幕を被ってカメラの後ろに立つので、§12 の忠実性とも一致する。
        if (!isBehind(state.getValue(FACING), basePos, player)) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "You have to stand close behind the camera to look through it."), true);
            return InteractionResult.CONSUME;
        }
        // 板が無くても・撮れない板でもファインダーには入る。構図と、いま動いているものを
        // 撮る前に見られること自体が要件（MODJAM_DECISIONS_OGP.md §2 Fun 案1）。
        // 撮影原点はレンズのある上半分（basePos.above()）。下半分を右クリックしても同じ絵になる。
        BlockPos lensPos = basePos.above();
        PhotoCaptureController.openViewfinder(serverPlayer, camera, basePos, lensPos, state.getValue(FACING));
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult ejectPlate(WetPlateCameraBlockEntity camera, Player player) {
        if (!camera.hasPlate()) {
            return InteractionResult.FAIL;
        }
        ItemStack plate = camera.getPlate();
        // カメラの中では inventoryTick が回らないので、出す時点で乾燥を清算する。
        // ここを抜かすと、乾いた板が濡れた表示のまま手に戻る（1 tick 後に直るが、
        // チェストへ直行させると次に持ち出すまで古い秒が出たままになる）。
        if (GlassPlateItem.resolveDryOut(plate, player.level().getGameTime())
                && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "The plate dried out inside the camera. The plate is clean again."), true);
        }
        camera.setPlate(ItemStack.EMPTY);
        camera.clearCapture();
        if (!player.addItem(plate)) {
            player.drop(plate, false);
        }
        return InteractionResult.SUCCESS;
    }
}

package com.kuronami.oldglassphotograph.block;

import com.kuronami.oldglassphotograph.OgpObjects;
import com.kuronami.oldglassphotograph.capture.PhotoCaptureController;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * 設置型の湿板カメラ。<b>構図は設置位置と FACING だけで決まる</b>（実機検証済み）。
 * 高さ2ブロック（1×1×2）の構造物（{@code MODJAM_DECISIONS_OGP.md} §18）。
 *
 * <p>この塊で通す操作:
 * <ul>
 *   <li>Glass Plate を持って右クリック = 装填（どの向きからでもよい）</li>
 *   <li>素手で右クリック = ファインダーを覗く。<b>カメラの後ろに立っている時だけ</b>
 *       （{@link #isBehind}）</li>
 *   <li>スニーク + 素手で右クリック = 装填 Plate の取り出し（どの向きからでもよい）</li>
 * </ul>
 * <b>BlockEntity は下半分だけが持つ。</b>上半分への操作は全て下半分へ転送する。
 *
 * <p>1.20.1 は use 単一入口なので、装填と覗きを 1 本に統合する
 * （後段セルの {@code useItemOn} / {@code useWithoutItem} の分岐順と同じ優先度）。
 */
public class WetPlateCameraBlock extends BaseEntityBlock {

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    public WetPlateCameraBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    /**
     * {@code BaseEntityBlock} の既定は {@code INVISIBLE}（1.20.1 jar の bytecode 実測）で、
     * blockstate のモデルが一切描かれない。26.x にこの既定は無いので、移植でここが落ちていた。
     */
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    // ------------------------------------------------------------------ 当たり判定
    //
    // MODJAM_DECISIONS_OGP.md §17/§18/§36。モデル（wet_plate_camera_lower.json /
    // wet_plate_camera_upper.json、a4_studio_trestle）の element 座標を FACING=NORTH 基準で
    // そのまま箱にし、他の向きは回転で作る。はみ出し無し（0〜16 の内側のみ）。

    /**
     * 下半分（トレッスル）。判定は見た目（逆V・脚4本）と別に、台形の中身を詰めた形にする
     * ＋中央の柱。FACING=NORTH 基準。
     */
    private static final double[][] LOWER_BOXES_NORTH = {
            {0.29, 0, 3, 15.71, 3.25, 13},
            {1.63, 3.25, 3, 14.37, 6.5, 13},
            {2.98, 6.5, 3, 13.02, 9.75, 13},
            {4.33, 9.75, 3, 11.67, 13, 13},
            {6, 13, 6, 10, 16, 10},
    };

    /** 上半分（カメラ本体）。柱の続きの首 + 蛇腹・箱を1枚にまとめた太い胴体。FACING=NORTH 基準。 */
    private static final double[][] UPPER_BOXES_NORTH = {
            {6, 0, 6, 10, 3, 10},
            {2, 3, 2, 14, 12, 16},
    };

    private static final Map<Direction, VoxelShape> LOWER_SHAPES = buildShapes(LOWER_BOXES_NORTH);
    private static final Map<Direction, VoxelShape> UPPER_SHAPES = buildShapes(UPPER_BOXES_NORTH);

    private static Map<Direction, VoxelShape> buildShapes(double[][] boxesNorth) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (double[] box : boxesNorth) {
                double[] xz = rotateNorthToFacing(dir, box[0], box[2], box[3], box[5]);
                shape = Shapes.or(shape, Block.box(xz[0], box[1], xz[1], xz[2], box[4], xz[3]));
            }
            shapes.put(dir, shape);
        }
        return shapes;
    }

    private static double[] rotateNorthToFacing(Direction dir, double x1, double z1, double x2, double z2) {
        return switch (dir) {
            case EAST -> new double[]{16 - z2, x1, 16 - z1, x2};
            case SOUTH -> new double[]{16 - x2, 16 - z2, 16 - x1, 16 - z1};
            case WEST -> new double[]{z1, 16 - x2, z2, 16 - x1};
            default -> new double[]{x1, z1, x2, z2};
        };
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Map<Direction, VoxelShape> shapes = state.getValue(HALF) == DoubleBlockHalf.UPPER
                ? UPPER_SHAPES : LOWER_SHAPES;
        return shapes.get(state.getValue(FACING));
    }

    /** 覗ける立ち位置の上限距離（水平・ブロック）。これより遠いと「覗いている」と言えない。 */
    private static final double PEEK_RANGE = 2.5;

    /** 下半分の位置。上半分を渡されたら1つ下げる。どちらの半分に対しても呼べる。 */
    public static BlockPos basePos(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    /**
     * player がカメラの<b>後ろ</b>に立っているか（{@code MODJAM_DECISIONS_OGP.md} B-1）。
     * 範囲は真後ろを中心に左右 45 度の扇形・水平 {@value #PEEK_RANGE} マス以内。
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
        if (pos.getY() >= level.getMaxBuildHeight() || !level.getBlockState(pos.above()).canBeReplaced(context)) {
            return null;
        }
        // レンズは設置者が見ている先を向く。したがって設置者は必ずカメラの後ろ側に立つ。
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
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
        }
        return super.canSurvive(state, level, pos);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction directionFromThisToNeighbour, BlockState neighbourState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        // 下が消えた上半分は独りで浮けない（DoublePlantBlock と同じ物理フォールバック）。
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER && directionFromThisToNeighbour == Direction.DOWN
                && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, directionFromThisToNeighbour, neighbourState, level, pos, neighbourPos);
    }

    /**
     * player が破壊した瞬間（実際の除去より前）に対になる半分も静かに消す
     * （vanilla {@code DoorBlock.playerWillDestroy} と同じ型）。
     */
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            DoubleBlockHalf half = state.getValue(HALF);
            BlockPos otherPos = half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState otherState = level.getBlockState(otherPos);
            if (otherState.is(this) && otherState.getValue(HALF) != half) {
                level.setBlock(otherPos, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(player, 2001, otherPos, Block.getId(otherState));
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    /** ブロック除去時に装填 Plate を落とす。BE がまだ生きている {@code onRemove} を使う。 */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(basePos(pos, state)) instanceof WetPlateCameraBlockEntity camera
                && camera.hasPlate()) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), camera.getPlate());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
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
        return createTickerHelper(type, OgpObjects.cameraBlockEntity(),
                WetPlateCameraBlockEntity::serverTick);
    }

    /**
     * 右クリック一本化（1.20.1 は use 単一入口）。板を持っていれば装填、
     * それ以外は覗き／取り出し。
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (held.is(OgpObjects.glassPlate())) {
            return tryLoad(level, pos, state, player, held);
        }
        return peekOrEject(level, pos, state, player);
    }

    private InteractionResult tryLoad(Level level, BlockPos pos, BlockState state, Player player, ItemStack stack) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos base = basePos(pos, state);
        if (!(level.getBlockEntity(base) instanceof WetPlateCameraBlockEntity camera)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.FAIL;
        }
        if (camera.hasPlate()) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.old_glass_photograph.camera.plate_already_loaded"), true);
            return InteractionResult.FAIL;
        }
        // 入れてよいのは「濡れていて、まだ潜像を持たない」板だけ。それ以外は理由を出して
        // 何も消費しない（板が黙って失われる経路を作らない）。
        if (GlassPlateItem.resolveDryOut(stack, level.getGameTime())) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.old_glass_photograph.plate.dried"), true);
            return InteractionResult.FAIL;
        }
        if (GlassPlateItem.isExposed(stack)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.old_glass_photograph.camera.already_exposed"), true);
            return InteractionResult.FAIL;
        }
        if (!GlassPlateItem.isReadyToLoad(stack)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.old_glass_photograph.camera.not_sensitized"), true);
            return InteractionResult.FAIL;
        }
        camera.setPlate(stack.split(1));
        serverPlayer.sendSystemMessage(
                Component.translatable("message.old_glass_photograph.camera.plate_loaded"), true);
        return InteractionResult.SUCCESS;
    }

    private InteractionResult peekOrEject(Level level, BlockPos pos, BlockState state, Player player) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos base = basePos(pos, state);
        if (!(level.getBlockEntity(base) instanceof WetPlateCameraBlockEntity camera)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.FAIL;
        }
        if (player.isShiftKeyDown()) {
            return ejectPlate(camera, player);
        }
        // 覗けるのはカメラの後ろに立っている時だけ（MODJAM_DECISIONS_OGP.md B-1）。
        if (!isBehind(state.getValue(FACING), base, player)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.old_glass_photograph.camera.stand_behind"), true);
            return InteractionResult.CONSUME;
        }
        // 板が無くても・撮れない板でもファインダーには入る。撮影原点は上半分（base.above()）。
        BlockPos lensPos = base.above();
        PhotoCaptureController.openViewfinder(serverPlayer, camera, base, lensPos, state.getValue(FACING));
        return InteractionResult.SUCCESS;
    }

    private InteractionResult ejectPlate(WetPlateCameraBlockEntity camera, Player player) {
        if (!camera.hasPlate()) {
            return InteractionResult.FAIL;
        }
        ItemStack plate = camera.getPlate();
        // カメラの中では inventoryTick が回らないので、出す時点で乾燥を清算する。
        if (GlassPlateItem.resolveDryOut(plate, player.level().getGameTime())
                && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.old_glass_photograph.camera.plate_dried_inside"), true);
        }
        camera.setPlate(ItemStack.EMPTY);
        camera.clearCapture();
        if (!player.addItem(plate)) {
            player.drop(plate, false);
        }
        return InteractionResult.SUCCESS;
    }
}

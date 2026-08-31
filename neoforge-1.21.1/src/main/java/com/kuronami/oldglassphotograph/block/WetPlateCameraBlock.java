package com.kuronami.oldglassphotograph.block;

import com.kuronami.oldglassphotograph.OgpObjects;
import com.kuronami.oldglassphotograph.capture.PhotoCaptureController;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
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
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
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

    /**
     * {@code BaseEntityBlock} の既定は {@code INVISIBLE}（1.21.1 jar の bytecode 実測）で、
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
    // そのまま箱にし、他の向きは回転で作る（モデル自体は blockstate の y 回転で作られている
    // ので、当たり判定も同じ回転を掛ければ一致する）。はみ出し無し（0〜16 の内側のみ）。
    //
    // 下半分＝トレッスル（脚は細く4本、天板の下で支える中央の柱だけ太い）。
    // 上半分＝カメラ本体（架台の続きの首は細く、蛇腹・箱の胴体は太い1枚板にして
    // 天面 Y=12 をまるごと乗れる面にする。レンズ鏡胴など前面の小さな出っ張りは
    // 当たり判定に起こさない＝過度な細分化をしない）。

    /**
     * 下半分（トレッスル）。判定は見た目（逆V・脚4本）と別に、台形の中身を詰めた形にする
     * ＋中央の柱。FACING=NORTH 基準。
     *
     * <p>採用モデル {@code a4_studio_trestle} の脚は見た目上は素の直方体ではなく、
     * {@code wet_plate_camera_lower.json} の element {@code rotation} により
     * z軸まわり±22.5°で斜めに開く（八の字トレッスル）。当初はこの脚形状をそのまま
     * 4本×高さ4段の階段状の箱（16箱）で当たり判定にしていたが、脚と脚の間が空洞になり
     * レイキャスト（プレイヤーの視線）がそこを抜けて奥のブロックに当たってしまう問題が
     * あった（kura 指摘 2026-08-23「三角形みたいに、中を潰してくれ」）。判定は忠実さより
     * 実用を優先し、高さバンドごとに x=6 側の脚の外端〜x=10 側の脚の外端、
     * z=3〜13（前脚〜後脚の間）を1つの箱で埋め、正面・側面のどちらから見ても
     * 中身の詰まった三角形（台形）になるようにしている。
     *
     * <p>脚の中心線・外端の計算は元の階段近似（trestle_6_4 等、y'(高さ) の関数
     * {@code x'(y') = 6 - (13 - y') * tan(22.5°)}）をそのまま流用し、4段の高さバンド
     * [0,3.25][3.25,6.5][6.5,9.75][9.75,13] ごとに、x=6 側脚のバンド内 x 範囲の最小値
     * （外端）から x=10 側脚のバンド内 x 範囲の最大値（外端）までを1本の箱にした。
     * 見た目（モデル JSON・テクスチャ）はこの変更で一切変わらない。
     */
    private static final double[][] LOWER_BOXES_NORTH = {
            // バンド1（y 0-3.25）。x=6側脚の外端0.29 〜 x=10側脚の外端15.71、z=3〜13。
            {0.29, 0, 3, 15.71, 3.25, 13},
            // バンド2（y 3.25-6.5）。外端1.63 〜 14.37。
            {1.63, 3.25, 3, 14.37, 6.5, 13},
            // バンド3（y 6.5-9.75）。外端2.98 〜 13.02。
            {2.98, 6.5, 3, 13.02, 9.75, 13},
            // バンド4（y 9.75-13）。外端4.33 〜 11.67。
            {4.33, 9.75, 3, 11.67, 13, 13},
            // 中央の柱（column element、rotation なし）。
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

    /**
     * FACING=NORTH 基準の (x1,z1)-(x2,z2) を、blockstate の y 回転と同じ向きで dir へ回す。
     * NORTH→EAST は y:90（{@code getStateForPlacement} のコメントにある写像と同じ）。
     */
    private static double[] rotateNorthToFacing(Direction dir, double x1, double z1, double x2, double z2) {
        return switch (dir) {
            case EAST -> new double[]{16 - z2, x1, 16 - z1, x2};
            case SOUTH -> new double[]{16 - x2, 16 - z2, 16 - x1, 16 - z1};
            case WEST -> new double[]{z1, 16 - x2, z2, 16 - x1};
            default -> new double[]{x1, z1, x2, z2};
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Map<Direction, VoxelShape> shapes = state.getValue(HALF) == DoubleBlockHalf.UPPER
                ? UPPER_SHAPES : LOWER_SHAPES;
        return shapes.get(state.getValue(FACING));
    }

    /** 覗ける立ち位置の上限距離（水平・ブロック）。これより遠いと「覗いている」と言えない。 */
    private static final double PEEK_RANGE = 2.5;

    /**
     * 下半分の位置。上半分を渡されたら1つ下げる。どちらの半分に対しても呼べる。
     *
     * <p>BlockEntity は下半分にしか無い（{@code newBlockEntity} が上半分では null を返す）ので、
     * 外から状態を読む側は必ずここを通してから {@code getBlockEntity} を引く。
     */
    public static BlockPos basePos(BlockPos pos, BlockState state) {
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
     * 覗くには寄る必要がある。距離の上限は「遠くからも撮れてしまう」（実機指摘）を塞ぐ。
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
        if (pos.getY() >= level.getMaxBuildHeight() || !level.getBlockState(pos.above()).canBeReplaced(context)) {
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
    protected BlockState updateShape(BlockState state, Direction directionFromThisToNeighbour, BlockState neighbourState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        // 下が消えた上半分は独りで浮けない（DoublePlantBlock と同じ物理フォールバック。
        // ピストン・爆発など playerWillDestroy を通らない除去の受け皿）。
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER && directionFromThisToNeighbour == Direction.DOWN
                && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, directionFromThisToNeighbour, neighbourState, level, pos, neighbourPos);
    }

    /**
     * player が破壊した瞬間（実際の除去より前）に対になる半分も静かに消す
     * （vanilla {@code DoorBlock.playerWillDestroy} と同じ型。どちらを壊しても両方消える）。
     *
     * <p>装填 Plate の救出は {@link #onRemove} が持つ。
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

    /**
     * ブロック除去時に装填 Plate を落とす。26.x の
     * {@code BlockEntity#preRemoveSideEffects} は 1.21.1 に無いので、BE がまだ
     * 生きている {@code onRemove} で代用する（vanilla コンテナ系と同じ hook）。
     * 同じブロックへの state 差し替え（上下半分の更新等）では落とさない。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
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
     * 板を持って右クリック = 装填（どの向きからでもよい）。
     * 1.21.1 の {@code useItemOn} は {@link ItemInteractionResult} を返す。
     * 板以外を持っている時は {@code PASS_TO_DEFAULT_BLOCK_INTERACTION} で素手扱いへ落とす
     * （26.x の {@code TRY_WITH_EMPTY_HAND} 相当）。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(OgpObjects.glassPlate())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        BlockPos basePos = basePos(pos, state);
        if (!(level.getBlockEntity(basePos) instanceof WetPlateCameraBlockEntity camera)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return ItemInteractionResult.FAIL;
        }
        if (camera.hasPlate()) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.old_glass_photograph.camera.plate_already_loaded"), true);
            return ItemInteractionResult.FAIL;
        }
        // 入れてよいのは「濡れていて、まだ潜像を持たない」板だけ。それ以外は理由を出して
        // 何も消費しない（板が黙って失われる経路を作らない）。
        if (GlassPlateItem.resolveDryOut(stack, level.getGameTime())) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.old_glass_photograph.plate.dried"), true);
            return ItemInteractionResult.FAIL;
        }
        if (GlassPlateItem.isExposed(stack)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.old_glass_photograph.camera.already_exposed"), true);
            return ItemInteractionResult.FAIL;
        }
        if (!GlassPlateItem.isReadyToLoad(stack)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.old_glass_photograph.camera.not_sensitized"), true);
            return ItemInteractionResult.FAIL;
        }
        camera.setPlate(stack.split(1));
        serverPlayer.sendSystemMessage(
                Component.translatable("message.old_glass_photograph.camera.plate_loaded"), true);
        return ItemInteractionResult.SUCCESS;
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
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.old_glass_photograph.camera.stand_behind"), true);
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

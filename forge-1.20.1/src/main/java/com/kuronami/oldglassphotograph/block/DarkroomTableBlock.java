package com.kuronami.oldglassphotograph.block;

import com.kuronami.oldglassphotograph.OgpObjects;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Darkroom Table。脚立に載った携帯暗箱（1881 {@code Practical Photography} p.52 の
 * trestle dark box。{@code MODJAM_DECISIONS_OGP.md} §30 決定3）。
 *
 * <p><b>暗いのはこの箱の中</b>で、部屋の明るさは工程に一切効かない（同 §30 決定2）。
 *
 * <p>操作はレクターン／ジュークボックスと同じ右クリックの出し入れだけで、GUI は持たない（§10 維持）。
 * <b>開ける → 入れる → 閉じる</b>の 3 手:
 * <ol>
 *   <li>閉じた箱を素手で右クリック = <b>開く</b></li>
 *   <li>開いた箱に板を持って右クリック = <b>板が入る</b>（工程はまだ始まらない・薬品も減らない）</li>
 *   <li><b>薬品を手に持って</b>右クリック = <b>閉じる。ここで工程が始まる</b>（手の薬品を 1 個消費）</li>
 *   <li>終わったら開けて像を見る。もう一度で回収</li>
 * </ol>
 *
 * <p>1.20.1 の {@code BlockBehaviour.use} は「板を持っている時」と「素手の時」を分ける
 * {@code useItemOn} / {@code useWithoutItem} の二分を持たない単一入口なので、
 * 後段（26.x / 1.21.x セル）で分かれていた処理をここで 1 本に統合する
 * （板を持っていて箱の仕事になる板なら投入、それ以外は蓋の開閉へ落ちる＝
 * 元の {@code PASS_TO_DEFAULT_BLOCK_INTERACTION} と同じ流れ）。
 */
public class DarkroomTableBlock extends BaseEntityBlock {

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 蓋。開いている間だけ中が見え、走っている工程にかぶりが溜まる。 */
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    /** 蓋を開けたときに何が見えるか。閉じている間は絵に出ない。 */
    public static final EnumProperty<Content> CONTENT = EnumProperty.create("content", Content.class);

    // ------------------------------------------------------------------ 当たり判定
    //
    // MODJAM_DECISIONS_OGP.md §17/§36。モデル（d1_closed.json 他）の element 座標を
    // FACING=NORTH 基準でそのまま箱にし、他の向きは blockstate と同じ y 回転で作る。

    private static final double[][] SHAPE_BOXES_NORTH = {
            {2, 0, 3, 4, 4, 5},
            {2, 0, 11, 4, 4, 13},
            {12, 0, 3, 14, 4, 5},
            {12, 0, 11, 14, 4, 13},
            {1, 4, 2, 15, 13, 14},
    };

    private static final Map<Direction, VoxelShape> SHAPES = buildShapes(SHAPE_BOXES_NORTH);

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

    /** FACING=NORTH 基準の (x1,z1)-(x2,z2) を、blockstate の y 回転と同じ向きで dir へ回す。 */
    private static double[] rotateNorthToFacing(Direction dir, double x1, double z1, double x2, double z2) {
        return switch (dir) {
            case EAST -> new double[]{16 - z2, x1, 16 - z1, x2};
            case SOUTH -> new double[]{16 - x2, 16 - z2, 16 - x1, 16 - z1};
            case WEST -> new double[]{z1, 16 - x2, z2, 16 - x1};
            default -> new double[]{x1, z1, x2, z2};
        };
    }

    public DarkroomTableBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(OPEN, false)
                .setValue(CONTENT, Content.EMPTY));
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
        builder.add(FACING, OPEN, CONTENT);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        // 蓋と前板は正面（モデルの -z）側に付く。設置者へ正面が向くように置く。
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DarkroomTableBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                            BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, OgpObjects.darkroomTableBlockEntity(),
                DarkroomTableBlockEntity::serverTick);
    }

    /**
     * ブロック除去時に中の板を落とす。BE がまだ生きている {@code onRemove} を使う
     * （vanilla コンテナ系と同じ hook。同じブロックへの state 差し替えでは落とさない）。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof DarkroomTableBlockEntity table) {
            ItemStack plate = table.releasePlateForRemoval();
            if (!plate.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), plate);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // ------------------------------------------------------------------ 操作

    /**
     * 右クリック一本化（1.20.1 は use 単一入口）。
     *
     * <ul>
     *   <li>工程に入る板を持っている → 入れるだけ（工程は始まらず、薬品も減らない）</li>
     *   <li>それ以外 → 蓋の開閉と、板の回収
     *     （閉 → 開 / 開 + 取り出し待ち → 回収 / 開 → 閉（薬品を持っていれば工程開始） /
     *      スニーク + 開 + 停止中 → 板取り戻し）</li>
     * </ul>
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (held.is(OgpObjects.glassPlate())) {
            GlassPlateItem.Step insertStep = GlassPlateItem.nextStep(held, level.getGameTime());
            if (insertStep != null && insertStep.inDarkroomBox()) {
                return tryInsert(state, level, pos, player, held, insertStep);
            }
        }
        return openCloseOrTake(state, level, pos, player);
    }

    private InteractionResult tryInsert(BlockState state, Level level, BlockPos pos, Player player,
                                        ItemStack stack, GlassPlateItem.Step step) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof DarkroomTableBlockEntity table)) {
            return InteractionResult.FAIL;
        }
        if (!state.getValue(OPEN)) {
            say(player, Component.translatable("message.old_glass_photograph.darkroom.lid_shut"));
            return InteractionResult.CONSUME;
        }
        if (table.hasPlate()) {
            say(player, Component.translatable(table.isWorking()
                    ? "message.old_glass_photograph.darkroom.working"
                    : "message.old_glass_photograph.darkroom.occupied"));
            return InteractionResult.CONSUME;
        }
        ItemStack inserted = stack.split(1);
        table.insertPlate(inserted);
        level.setBlock(pos, state.setValue(CONTENT, contentOf(inserted)), Block.UPDATE_ALL);
        // 次に何をすればいいかを、薬品の名前まで込みで言う（§32-1 で「手に持つ」が条件になった）。
        say(player, Component.translatable("message.old_glass_photograph.darkroom.plate_in",
                step.chemicalName()));
        return InteractionResult.SUCCESS;
    }

    /**
     * 素手扱いの右クリック。蓋の開閉と、板の回収。
     *
     * <p>工程中でも蓋は開く。開けても板は失われず工程も止まらない（§30 の「失敗を増やさない」）。
     */
    private InteractionResult openCloseOrTake(BlockState state, Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof DarkroomTableBlockEntity table)) {
            return InteractionResult.FAIL;
        }
        long gameTime = level.getGameTime();
        if (!state.getValue(OPEN)) {
            level.setBlock(pos, state.setValue(OPEN, true), Block.UPDATE_ALL);
            playLid(level, pos, true);
            if (table.isWorking()) {
                say(player, Component.translatable("message.old_glass_photograph.darkroom.light_leak"));
            } else if (!table.hasPlate()) {
                say(player, Component.translatable("message.old_glass_photograph.darkroom.open_empty"));
            } else if (!table.isAwaitingPickup(gameTime)) {
                // 入れたまま閉じずに開け直した板。取り出し待ちの板には何も言わない。
                GlassPlateItem.Step waiting = GlassPlateItem.nextStep(table.getPlate(), gameTime);
                say(player, Component.translatable("message.old_glass_photograph.darkroom.plate_waiting",
                        waiting != null && waiting.inDarkroomBox()
                                ? waiting.chemicalName() : Component.empty()));
            }
            return InteractionResult.SUCCESS;
        }
        // 取り出し待ち（工程を終えた板・箱ではもう何もできない板）。
        if (table.isAwaitingPickup(gameTime)) {
            return takeOut(level, pos, state, player, table);
        }
        // 入れたがまだ始めていない板を取り戻す。工程が走っている間は取り出せない。
        if (player.isShiftKeyDown() && table.hasPlate() && !table.isWorking()) {
            return takeOut(level, pos, state, player, table);
        }
        // 閉じる。始めていない板が中にあるならここで工程が始まる。
        GlassPlateItem.Step step = table.hasPlate() && !table.isWorking()
                ? GlassPlateItem.nextStep(table.getPlate(), gameTime)
                : null;
        Component note = null;
        if (step != null && step.inDarkroomBox()) {
            if (!GlassPlateItem.holdsChemical(player, step)) {
                // 蓋は閉じるが工程は始まらない。板は無事のまま中で待つ。
                note = Component.translatable("message.old_glass_photograph.darkroom.need_chemical",
                        step.chemicalName());
            } else if (GlassPlateItem.consumeHeldChemical(player, step)) {
                table.startProcess(step);
                note = step.startMessage();
            }
        }
        level.setBlock(pos, state.setValue(OPEN, false), Block.UPDATE_ALL);
        playLid(level, pos, false);
        if (note != null) {
            say(player, note);
        }
        return InteractionResult.SUCCESS;
    }

    /** 中の板を player の手へ戻す。蓋は開いたままにする。 */
    private InteractionResult takeOut(Level level, BlockPos pos, BlockState state, Player player,
                                      DarkroomTableBlockEntity table) {
        ItemStack taken = table.removePlate();
        // 箱の中では inventoryTick が回らないので、出す時点で乾燥を清算する。
        if (GlassPlateItem.resolveDryOut(taken, level.getGameTime())) {
            say(player, Component.translatable("message.old_glass_photograph.darkroom.dried_inside"));
        }
        if (!player.addItem(taken)) {
            player.drop(taken, false);
        }
        level.setBlock(pos, state.setValue(CONTENT, Content.EMPTY), Block.UPDATE_ALL);
        return InteractionResult.SUCCESS;
    }

    /**
     * 中の板の見え方を blockstate へ映す。蓋が閉じている間は絵に出ないが、開けたときに正しく出る。
     *
     * <p>差し替える元の state は<b>その場で読み直す</b>。
     */
    static void syncContent(Level level, BlockPos pos, ItemStack plate) {
        BlockState current = level.getBlockState(pos);
        if (!current.is(OgpObjects.darkroomTable())) {
            return;
        }
        Content content = contentOf(plate);
        if (current.getValue(CONTENT) != content) {
            level.setBlock(pos, current.setValue(CONTENT, content), Block.UPDATE_ALL);
        }
    }

    private static Content contentOf(ItemStack plate) {
        if (plate.isEmpty()) {
            return Content.EMPTY;
        }
        PlateProcess process = GlassPlateItem.process(plate);
        if (process == null) {
            return Content.PLATE;
        }
        return switch (process.stage()) {
            case SENSITIZED, EXPOSED -> Content.SENSITIZED;
            case DEVELOPED -> Content.PHOTO;
        };
    }

    private static void playLid(Level level, BlockPos pos, boolean opening) {
        level.playSound(null, pos,
                opening ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE,
                SoundSource.BLOCKS, 0.7F, 1.1F);
    }

    private static void say(Player player, Component text) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(text, true);
        }
    }

    /** 蓋を開けたときに見えるもの。 */
    public enum Content implements StringRepresentable {
        EMPTY("empty"),
        /** 塗布待ちで入れた素のガラス板。 */
        PLATE("plate"),
        /** コロジオンを塗った板と、露光して潜像を持つ板。アイテム側と同じで絵は共通。 */
        SENSITIZED("sensitized"),
        /** 現像が済んで像の出た板。blockstate の値名は既存のワールドに合わせて photo のまま。 */
        PHOTO("photo");

        private final String name;

        Content(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}

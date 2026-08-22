package com.kuronami.oldglassphotograph.block;

import com.kuronami.oldglassphotograph.OgpRegistry;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
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
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Darkroom Table。脚立に載った携帯暗箱（1881 {@code Practical Photography} p.52 の
 * trestle dark box。{@code MODJAM_DECISIONS_OGP.md} §30 決定3）。
 *
 * <p><b>暗いのはこの箱の中</b>で、部屋の明るさは工程に一切効かない（同 §30 決定2）。
 * 野外の写真家が引いていたのは暗室ワゴンと携帯テントであって暗い部屋ではないので、
 * 遮光された容器そのものを再現するほうが史実にも近い（同 §10 の一次資料）。
 *
 * <p>操作はレクターン／ジュークボックスと同じ右クリックの出し入れだけで、GUI は持たない（§10 維持）:
 * <ul>
 *   <li>板を持って右クリック = 板が入り、蓋が閉じて工程が始まる（薬品を 1 個消費）</li>
 *   <li>素手で右クリック = 蓋の開閉。工程を終えた板が見えているならその板を回収</li>
 * </ul>
 *
 * <p>箱で回すのは<b>塗布（PREPARE）と現像（DEVELOP）の 2 つ</b>。
 * 定着は史実でも暗室の外でよいので手に持ったまま行う。
 */
public class DarkroomTableBlock extends BaseEntityBlock {

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 蓋。開いている間だけ中が見え、走っている工程にかぶりが溜まる。 */
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    /** 蓋を開けたときに何が見えるか。閉じている間は絵に出ない。 */
    public static final EnumProperty<Content> CONTENT = EnumProperty.create("content", Content.class);

    public static final MapCodec<DarkroomTableBlock> CODEC = simpleCodec(DarkroomTableBlock::new);

    /** モデルの実寸（高さ 13、水平は全幅）。{@code cube_all} ではないので当たり判定も合わせる。 */
    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 13.0, 16.0);

    public DarkroomTableBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(OPEN, false)
                .setValue(CONTENT, Content.EMPTY));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN, CONTENT);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
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
        return createTickerHelper(type, OgpRegistry.DARKROOM_TABLE_BLOCK_ENTITY.get(),
                DarkroomTableBlockEntity::serverTick);
    }

    // ------------------------------------------------------------------ 操作

    /**
     * 板を持って右クリック。
     *
     * <p>箱の仕事でない板（露光待ち・定着待ち・乾いた板）は {@link InteractionResult#PASS} で
     * 手の中の操作へ落とす。判定は<b>手の板だけ</b>で決まるので client と server で必ず一致する。
     */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(OgpRegistry.GLASS_PLATE.get())) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        GlassPlateItem.Step step = GlassPlateItem.nextStep(stack, level.getGameTime());
        if (step == null || !step.inDarkroomBox()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof DarkroomTableBlockEntity table)) {
            return InteractionResult.FAIL;
        }
        if (table.hasPlate()) {
            say(player, table.isWorking()
                    ? "The box is working on a plate."
                    : "A plate is waiting inside. Take it out first.");
            return InteractionResult.CONSUME;
        }
        if (!GlassPlateItem.hasChemical(player, step)) {
            say(player, "You need " + step.chemicalName() + ".");
            return InteractionResult.CONSUME;
        }
        if (!GlassPlateItem.consumeChemical(player, step)) {
            return InteractionResult.CONSUME;
        }
        table.beginProcess(stack.split(1), step);
        BlockState closed = state.setValue(OPEN, false).setValue(CONTENT, Content.PLATE);
        level.setBlock(pos, closed, Block.UPDATE_ALL);
        playLid(level, pos, false);
        say(player, step.startMessage());
        return InteractionResult.SUCCESS;
    }

    /**
     * 素手（または箱に関係ない物を持って）右クリック。蓋の開閉と、終わった板の回収。
     *
     * <p>工程中でも蓋は開く。開けても板は失われず工程も止まらない（§30 の「失敗を増やさない」）。
     * 入るのは光だけで、その分だけ写真の痕跡が濃くなる。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof DarkroomTableBlockEntity table)) {
            return InteractionResult.FAIL;
        }
        if (!state.getValue(OPEN)) {
            level.setBlock(pos, state.setValue(OPEN, true), Block.UPDATE_ALL);
            playLid(level, pos, true);
            if (table.isWorking()) {
                say(player, "Light is getting in. The plate will fog.");
            }
            return InteractionResult.SUCCESS;
        }
        if (table.hasFinishedPlate()) {
            ItemStack taken = table.removePlate();
            // 箱の中では inventoryTick が回らないので、出す時点で乾燥を清算する
            // （カメラの取り出しと同じ扱い。乾いた板が濡れた表示のまま手に戻るのを防ぐ）。
            if (GlassPlateItem.resolveDryOut(taken, level.getGameTime())) {
                say(player, "The collodion dried out inside the box. The plate is clean again.");
            }
            if (!player.addItem(taken)) {
                player.drop(taken, false);
            }
            level.setBlock(pos, state.setValue(CONTENT, Content.EMPTY), Block.UPDATE_ALL);
            return InteractionResult.SUCCESS;
        }
        level.setBlock(pos, state.setValue(OPEN, false), Block.UPDATE_ALL);
        playLid(level, pos, false);
        return InteractionResult.SUCCESS;
    }

    /**
     * 中の板の見え方を blockstate へ映す。蓋が閉じている間は絵に出ないが、開けたときに正しく出る。
     *
     * <p>差し替える元の state は<b>その場で読み直す</b>。呼び出し側が持っている state を土台にすると、
     * 途中で誰かが蓋を動かしていた場合に {@link #OPEN} や {@link #FACING} まで巻き戻してしまう。
     */
    static void syncContent(Level level, BlockPos pos, ItemStack plate) {
        BlockState current = level.getBlockState(pos);
        if (!current.is(OgpRegistry.DARKROOM_TABLE.get())) {
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
        return process != null && process.stage() == PlateProcess.Stage.DEVELOPED
                ? Content.PHOTO
                : Content.PLATE;
    }

    private static void playLid(Level level, BlockPos pos, boolean opening) {
        level.playSound(null, pos,
                opening ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE,
                SoundSource.BLOCKS, 0.7F, 1.1F);
    }

    private static void say(Player player, String text) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.literal(text), true);
        }
    }

    /** 蓋を開けたときに見えるもの。 */
    public enum Content implements StringRepresentable {
        EMPTY("empty"),
        /** まだ像の無い板（塗布済み、または塗布待ちで入れた素のガラス）。 */
        PLATE("plate"),
        /** 現像が済んで像の出た板。 */
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

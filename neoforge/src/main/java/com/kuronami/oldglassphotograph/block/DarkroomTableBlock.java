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
 * <p>操作はレクターン／ジュークボックスと同じ右クリックの出し入れだけで、GUI は持たない（§10 維持）。
 * <b>開ける → 入れる → 閉じる</b>の 3 手（2026-08-22 kura 指示 B-2。
 * 蓋が閉じたまま板が入ってしまうのは箱として筋が通らない）:
 * <ol>
 *   <li>閉じた箱を素手で右クリック = <b>開く</b></li>
 *   <li>開いた箱に板を持って右クリック = <b>板が入る</b>（工程はまだ始まらない・薬品も減らない）</li>
 *   <li>開いた箱を素手で右クリック = <b>閉じる。ここで工程が始まる</b>（薬品を 1 個消費）</li>
 *   <li>終わったら開けて像を見る。もう一度で回収</li>
 * </ol>
 * 入れた板をやめたくなったら<b>スニーク + 素手</b>で取り戻せる（工程が走っていない間だけ）。
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
     * 板を持って右クリック = <b>入れるだけ</b>。工程は始まらず、薬品も減らない
     * （始まるのは蓋を閉じた時＝{@link #useWithoutItem}）。
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
        table.insertPlate(stack.split(1));
        level.setBlock(pos, state.setValue(CONTENT, Content.PLATE), Block.UPDATE_ALL);
        say(player, Component.translatable("message.old_glass_photograph.darkroom.plate_in"));
        return InteractionResult.SUCCESS;
    }

    /**
     * 素手（または箱に関係ない物を持って）右クリック。蓋の開閉と、板の回収。
     *
     * <ul>
     *   <li>閉じている → 開く</li>
     *   <li>開いていて取り出し待ちの板がある → その板を回収（蓋は開いたまま）</li>
     *   <li>開いている → 閉じる。中に始めていない板があればここで工程が始まる</li>
     *   <li>スニーク + 開いている + 走っていない板 → 入れた板を取り戻す</li>
     * </ul>
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
        long gameTime = level.getGameTime();
        if (!state.getValue(OPEN)) {
            level.setBlock(pos, state.setValue(OPEN, true), Block.UPDATE_ALL);
            playLid(level, pos, true);
            if (table.isWorking()) {
                say(player, Component.translatable("message.old_glass_photograph.darkroom.light_leak"));
            } else if (!table.hasPlate()) {
                say(player, Component.translatable("message.old_glass_photograph.darkroom.open_empty"));
            } else if (!table.isAwaitingPickup(gameTime)) {
                // 入れたまま閉じずに開け直した板。取り出し待ちの板には何も言わない
                // （像が見えているので、次のクリックで取れることは絵で分かる）。
                say(player, Component.translatable("message.old_glass_photograph.darkroom.plate_waiting"));
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
            if (!GlassPlateItem.hasChemical(player, step)) {
                // 蓋は閉じるが工程は始まらない。板は無事のまま中で待つ（§30 決定4 の
                // 「失敗の状態を 1 つも増やさない」）。
                note = Component.translatable("message.old_glass_photograph.darkroom.need_chemical",
                        step.chemicalName());
            } else if (GlassPlateItem.consumeChemical(player, step)) {
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
    private static InteractionResult takeOut(Level level, BlockPos pos, BlockState state, Player player,
                                             DarkroomTableBlockEntity table) {
        ItemStack taken = table.removePlate();
        // 箱の中では inventoryTick が回らないので、出す時点で乾燥を清算する
        // （カメラの取り出しと同じ扱い。乾いた板が濡れた表示のまま手に戻るのを防ぐ）。
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

    private static void say(Player player, Component text) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(text, true);
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

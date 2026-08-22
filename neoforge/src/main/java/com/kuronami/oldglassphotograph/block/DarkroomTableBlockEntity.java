package com.kuronami.oldglassphotograph.block;

import com.kuronami.oldglassphotograph.OgpRegistry;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Darkroom Table の中身。<b>板 1 枚と、その板に走っている工程</b>を持つ。
 *
 * <p>暗さは周囲の光量ではなくこの箱そのものが担保する（{@code MODJAM_DECISIONS_OGP.md} §30 決定2）。
 * 蓋を閉じている限り中は暗室で、部屋の明るさは工程に一切効かない。
 *
 * <p>工程中に蓋を開けても<b>失敗は増えない</b>。板も潜像も失われず工程は進み続け、
 * 代わりに開けていた tick 数が「かぶり」として板へ溜まる（{@link OgpDataComponents#PLATE_FOG}）。
 * かぶりは現像の痕跡を濃くする（{@code capture.PhotoDeveloper}）。
 */
public class DarkroomTableBlockEntity extends BlockEntity {

    private ItemStack plate = ItemStack.EMPTY;

    /** 走っている工程。null = 何も走っていない（空、または工程を終えた板が入っている）。 */
    private GlassPlateItem.@Nullable Step step;

    /** 残り tick。{@code step != null} の間だけ意味を持つ。 */
    private int workTicks;

    /** この工程で蓋を開けていた tick 数。工程の完了時に板の component へ足して 0 に戻す。 */
    private int fogTicks;

    public DarkroomTableBlockEntity(BlockPos pos, BlockState state) {
        super(OgpRegistry.DARKROOM_TABLE_BLOCK_ENTITY.get(), pos, state);
    }

    public ItemStack getPlate() {
        return plate;
    }

    public boolean hasPlate() {
        return !plate.isEmpty();
    }

    public boolean isWorking() {
        return step != null;
    }

    /**
     * 箱の中の板が<b>取り出し待ち</b>か。工程を終えた板と、箱ではもう何もできない板の両方。
     *
     * <p>「入れたがまだ始めていない板」と区別が要る（{@code MODJAM_DECISIONS_OGP.md} B-2 で
     * 開ける→入れる→閉じるの 3 手になったので、蓋を開けたまま板が待っている状態が生まれた）。
     * 区別は板そのものから引く。始めていない板は箱の仕事（PREPARE / DEVELOP）が残っており、
     * 終わった板は残っていない。フィールドを増やさないので保存・読み込みの往復でもずれない。
     */
    public boolean isAwaitingPickup(long gameTime) {
        if (!hasPlate() || step != null) {
            return false;
        }
        GlassPlateItem.Step next = GlassPlateItem.nextStep(plate, gameTime);
        return next == null || !next.inDarkroomBox();
    }

    /** 蓋の開いた箱へ板を入れる。工程はまだ始まらない（薬品も消費しない）。 */
    public void insertPlate(ItemStack incoming) {
        this.plate = incoming;
        this.step = null;
        this.workTicks = 0;
        this.fogTicks = 0;
        setChanged();
    }

    /** 蓋を閉じて工程を始める。薬品の消費は呼び出し側（ブロック）が済ませてある。 */
    public void startProcess(GlassPlateItem.Step begun) {
        this.step = begun;
        this.workTicks = begun.durationTicks();
        this.fogTicks = 0;
        setChanged();
    }

    /** 板を取り出す。工程が走っている間は呼ばない。 */
    public ItemStack removePlate() {
        ItemStack taken = this.plate;
        this.plate = ItemStack.EMPTY;
        this.step = null;
        this.workTicks = 0;
        this.fogTicks = 0;
        setChanged();
        return taken;
    }

    /**
     * server tick。工程を進め、蓋が開いていればかぶりを溜める。
     *
     * <p>工程が終わっても蓋は開かない（{@code MODJAM_DECISIONS_OGP.md} §30 決定1）。
     * 像が出た板は player が自分で開けたときに初めて見える。
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, DarkroomTableBlockEntity table) {
        if (table.step == null) {
            return;
        }
        if (state.getValue(DarkroomTableBlock.OPEN)) {
            table.fogTicks++;
        }
        if (--table.workTicks > 0) {
            // 毎 tick の setChanged() は要らない。startProcess で既にチャンクは dirty で、
            // saveAdditional はその時点のフィールドをそのまま書く。
            return;
        }
        GlassPlateItem.Step finished = table.step;
        table.step = null;
        table.workTicks = 0;
        if (table.fogTicks > 0) {
            int before = table.plate.getOrDefault(OgpDataComponents.PLATE_FOG.get(), 0);
            table.plate.set(OgpDataComponents.PLATE_FOG.get(), before + table.fogTicks);
            table.fogTicks = 0;
        }
        GlassPlateItem.applyDarkroomResult(table.plate, finished, level.getGameTime());
        table.setChanged();
        DarkroomTableBlock.syncContent(level, pos, table.plate);
    }

    /**
     * ブロック除去の直前、BE がまだ生きている間に呼ばれる。
     *
     * <p>{@code Block.affectNeighborsAfterRemoval} は使えない。
     * {@code LevelChunk.setBlockState} は BE をチャンクの map から外してからそれを呼ぶので、
     * その中で {@code level.getBlockEntity(pos)} を引いても常に null になる
     * （{@code MODJAM_DECISIONS_OGP.md} §23。カメラで実際に踏んだ罠）。
     * ここなら {@code this.level} も中の板もまだ有効で、破壊経路（player 直接・爆発・
     * ピストン・他 MOD）を問わず必ず通る。
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (hasPlate() && this.level != null) {
            if (fogTicks > 0) {
                int before = plate.getOrDefault(OgpDataComponents.PLATE_FOG.get(), 0);
                plate.set(OgpDataComponents.PLATE_FOG.get(), before + fogTicks);
                fogTicks = 0;
            }
            Containers.dropItemStack(this.level, pos.getX(), pos.getY(), pos.getZ(), plate);
            this.plate = ItemStack.EMPTY;
            this.step = null;
            this.workTicks = 0;
        }
        super.preRemoveSideEffects(pos, state);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.plate = input.read("Plate", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        this.step = GlassPlateItem.Step.byName(input.getStringOr("Step", ""));
        this.workTicks = input.getIntOr("WorkTicks", 0);
        this.fogTicks = input.getIntOr("FogTicks", 0);
        if (this.plate.isEmpty() || this.workTicks <= 0) {
            // 板の無い工程・残り 0 の工程は成立しない（外部から壊れた NBT を渡された場合の受け皿）。
            this.step = null;
            this.workTicks = 0;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!this.plate.isEmpty()) {
            output.store("Plate", ItemStack.CODEC, this.plate);
        }
        if (this.step != null) {
            output.putString("Step", this.step.name());
            output.putInt("WorkTicks", this.workTicks);
        }
        if (this.fogTicks > 0) {
            output.putInt("FogTicks", this.fogTicks);
        }
    }
}

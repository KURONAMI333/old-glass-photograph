package com.kuronami.oldglassphotograph.block;

import com.kuronami.oldglassphotograph.OgpRegistry;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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

    /**
     * 蓋の隙間から薬品の匂いが漏れる間隔（tick）。
     *
     * <p>閉じた箱は中で何が起きているか一切見えず、終わりも分からない
     * （2026-08-22 実機指摘「これも完了がわかるきっかけがない　不親切かも」）。
     * <b>走っている間だけ細く煙が出て、終わると止まる。</b>数値も割合も出さずに
     * 「動いている / 終わった」だけが外から読める。
     *
     * <p>コロジオンはエーテルとアルコールの溶液で、暗室の中は実際に匂いが強かった。
     * 湿板の写真家が携帯暗室で嫌われた理由そのものなので、絵としても史実の側にある。
     */
    private static final int FUME_INTERVAL = 10;

    /** 煙を出す高さ（ブロックの上面 13/16 が蓋）。 */
    private static final double FUME_Y = 12.0 / 16.0;

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
     * 動いている工程の進捗を百分率で返す。工程がない時は 0。
     *
     * <p>Jade のように BlockEntity の状態を外へ表示する連携は、この値だけを読む。
     * 工程の進行や保存の挙動は変えない。
     *
     * @return 工程全体に対する完了済みの百分率
     */
    public int getWorkProgressPercent() {
        if (step == null || step.durationTicks() <= 0) {
            return 0;
        }
        return Math.clamp((step.durationTicks() - workTicks) * 100 / step.durationTicks(), 0, 100);
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
            if (table.workTicks % FUME_INTERVAL == 0) {
                fume(level, pos, state);
            }
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
        done(level, pos);
    }

    /**
     * 工程が終わった合図。<b>箱は開かない</b>ので、音だけが外へ出る
     * （{@code MODJAM_DECISIONS_OGP.md} §30 決定1「像が出た板は player が自分で開けたときに初めて見える」）。
     *
     * <p>{@code block.brewing_stand.brew} は vanilla が「液を使う工程が仕上がった」に当てている音で、
     * ここで実際に起きていること（薬液の中で像が上がりきる）と同じ出来事から出る音になる。
     * 蓋の開閉（{@code wooden_trapdoor}）とも定着の終わり（{@code bottle.empty}）とも重ならない。
     *
     * <p>player を渡さずに放送する。箱の前に居ない player にも届いてよい
     * （閉じた箱の前で待つのでなく、他のことをしていて呼ばれるほうが工程として自然）。
     */
    private static void done(Level level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.8F, 1.0F);
    }

    /** 蓋の隙間から漏れる薬品の匂い。走っている間だけ。 */
    private static void fume(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        // 正面（蓋の合わせ目のある側）の縁から出す。真上から出すと蓋を開けているように見える。
        Direction front = state.getValue(DarkroomTableBlock.FACING);
        double x = pos.getX() + 0.5 + front.getStepX() * 0.45;
        double z = pos.getZ() + 0.5 + front.getStepZ() * 0.45;
        server.sendParticles(ParticleTypes.WHITE_SMOKE, x, pos.getY() + FUME_Y, z,
                1, 0.12, 0.0, 0.12, 0.0);
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

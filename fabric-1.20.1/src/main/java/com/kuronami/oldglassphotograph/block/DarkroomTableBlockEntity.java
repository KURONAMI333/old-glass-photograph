package com.kuronami.oldglassphotograph.block;

import com.kuronami.oldglassphotograph.OgpObjects;
import com.kuronami.oldglassphotograph.component.OgpNbt;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Darkroom Table の中身。<b>板 1 枚と、その板に走っている工程</b>を持つ。
 *
 * <p>暗さは周囲の光量ではなくこの箱そのものが担保する（{@code MODJAM_DECISIONS_OGP.md} §30 決定2）。
 *
 * <p>工程中に蓋を開けても<b>失敗は増えない</b>。板も潜像も失われず工程は進み続け、
 * 代わりに開けていた tick 数が「かぶり」として板へ溜まる（{@link OgpNbt} の FOG タグ）。
 * かぶりは現像の痕跡を濃くする（{@code capture.PhotoDeveloper}）。
 */
public class DarkroomTableBlockEntity extends BlockEntity {

    /** 蓋の隙間から薬品の匂いが漏れる間隔（tick）。走っている間だけ細く煙が出て、終わると止まる。 */
    private static final int FUME_INTERVAL = 10;

    /** 煙を出す高さ（ブロックの上面 13/16 が蓋）。 */
    private static final double FUME_Y = 12.0 / 16.0;

    private ItemStack plate = ItemStack.EMPTY;

    /** 走っている工程。null = 何も走っていない（空、または工程を終えた板が入っている）。 */
    private GlassPlateItem.@Nullable Step step;

    /** 残り tick。{@code step != null} の間だけ意味を持つ。 */
    private int workTicks;

    /** この工程で蓋を開けていた tick 数。工程の完了時に板の NBT へ足して 0 に戻す。 */
    private int fogTicks;

    public DarkroomTableBlockEntity(BlockPos pos, BlockState state) {
        super(OgpObjects.darkroomTableBlockEntity(), pos, state);
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
     */
    public int getWorkProgressPercent() {
        if (step == null || step.durationTicks() <= 0) {
            return 0;
        }
        return Mth.clamp((step.durationTicks() - workTicks) * 100 / step.durationTicks(), 0, 100);
    }

    /**
     * 箱の中の板が<b>取り出し待ち</b>か。工程を終えた板と、箱ではもう何もできない板の両方。
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
     * ブロック除去時に呼ぶ。溜まっていたかぶりを板へ書いてから中身を返す
     * （BE がまだ生きている間だけ有効）。
     */
    public ItemStack releasePlateForRemoval() {
        if (hasPlate()) {
            if (fogTicks > 0) {
                OgpNbt.setFog(plate, OgpNbt.fog(plate) + fogTicks);
                fogTicks = 0;
            }
            return removePlate();
        }
        return ItemStack.EMPTY;
    }

    /** server tick。工程を進め、蓋が開いていればかぶりを溜める。 */
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
            OgpNbt.setFog(table.plate, OgpNbt.fog(table.plate) + table.fogTicks);
            table.fogTicks = 0;
        }
        GlassPlateItem.applyDarkroomResult(table.plate, finished, level.getGameTime());
        table.setChanged();
        DarkroomTableBlock.syncContent(level, pos, table.plate);
        done(level, pos);
    }

    /**
     * 工程が終わった合図。<b>箱は開かない</b>ので、音だけが外へ出る。
     * {@code block.brewing_stand.brew} は vanilla が「液を使う工程が仕上がった」に当てている音。
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
        server.sendParticles(ParticleTypes.SMOKE, x, pos.getY() + FUME_Y, z,
                1, 0.12, 0.0, 0.12, 0.0);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Plate", 10)) {
            this.plate = ItemStack.of(tag.getCompound("Plate"));
        } else {
            this.plate = ItemStack.EMPTY;
        }
        this.step = GlassPlateItem.Step.byName(tag.getString("Step"));
        this.workTicks = tag.getInt("WorkTicks");
        this.fogTicks = tag.getInt("FogTicks");
        if (this.plate.isEmpty() || this.workTicks <= 0) {
            // 板の無い工程・残り 0 の工程は成立しない（外部から壊れた NBT を渡された場合の受け皿）。
            this.step = null;
            this.workTicks = 0;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!this.plate.isEmpty()) {
            CompoundTag plateTag = this.plate.save(new CompoundTag());
            tag.put("Plate", plateTag);
        }
        if (this.step != null) {
            tag.putString("Step", this.step.name());
            tag.putInt("WorkTicks", this.workTicks);
        }
        if (this.fogTicks > 0) {
            tag.putInt("FogTicks", this.fogTicks);
        }
    }
}

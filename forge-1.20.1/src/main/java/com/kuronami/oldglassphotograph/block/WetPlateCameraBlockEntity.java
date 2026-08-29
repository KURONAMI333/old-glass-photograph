package com.kuronami.oldglassphotograph.block;

import com.kuronami.oldglassphotograph.OgpObjects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 設置 Camera の状態。装填された Glass Plate と、撮影中の session を持つ。
 *
 * <p>session（token・撮影者・timeout）は <b>永続化しない</b>。
 * 世界を抜けた・chunk が unload した時点で capture 待ちは成立しないので、
 * 破棄して装填 Plate だけを残す（{@code MODJAM_DESIGN_FIXES.md} A-1 の (a) 行）。
 */
public class WetPlateCameraBlockEntity extends BlockEntity {

    /**
     * capture 待ちの上限。超えたら session を捨てる。
     *
     * <p>ここが露光より短いと、<b>暗い場所の長い露光ほど token が先に無効化されて
     * 写真が黙って消える</b>（取り返しのつかない失敗を作らないという受理済みの原則に反する）。
     */
    public static final int CAPTURE_TIMEOUT_TICKS = 600;

    private ItemStack plate = ItemStack.EMPTY;

    private int pendingToken;
    private @Nullable UUID pendingPlayer;
    private int pendingTicks;

    public WetPlateCameraBlockEntity(BlockPos pos, BlockState state) {
        super(OgpObjects.cameraBlockEntity(), pos, state);
    }

    public ItemStack getPlate() {
        return plate;
    }

    public void setPlate(ItemStack stack) {
        this.plate = stack;
        setChanged();
    }

    public boolean hasPlate() {
        return !plate.isEmpty();
    }

    public void beginCapture(int token, UUID player) {
        this.pendingToken = token;
        this.pendingPlayer = player;
        this.pendingTicks = CAPTURE_TIMEOUT_TICKS;
    }

    public boolean isAwaitingCapture() {
        return pendingPlayer != null;
    }

    public boolean matchesToken(int token, UUID player) {
        return pendingPlayer != null && pendingToken == token && pendingPlayer.equals(player);
    }

    public void clearCapture() {
        this.pendingToken = 0;
        this.pendingPlayer = null;
        this.pendingTicks = 0;
    }

    /** server tick。capture 待ちの timeout だけを見る。 */
    public static void serverTick(Level level, BlockPos pos, BlockState state, WetPlateCameraBlockEntity be) {
        if (be.pendingPlayer != null && --be.pendingTicks <= 0) {
            be.clearCapture();
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Plate", 10)) {
            this.plate = ItemStack.of(tag.getCompound("Plate"));
        } else {
            this.plate = ItemStack.EMPTY;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!this.plate.isEmpty()) {
            CompoundTag plateTag = this.plate.save(new CompoundTag());
            tag.put("Plate", plateTag);
        }
    }
}

package com.kuronami.oldglassphotograph.integration.jade;

import com.kuronami.oldglassphotograph.block.DarkroomTableBlockEntity;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlock;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * Jade がクライアントへ同期する、ブロックエンティティの表示用データを収集する。
 */
final class OldGlassPhotographJadeDataProvider implements IServerDataProvider<BlockAccessor> {

    static final OldGlassPhotographJadeDataProvider INSTANCE = new OldGlassPhotographJadeDataProvider();

    static final String TYPE = "Type";
    static final String PLATE_NAME = "PlateName";
    static final String WORKING = "Working";
    static final String PROGRESS = "Progress";
    static final int CAMERA = 1;
    static final int DARKROOM = 2;

    private static final Identifier UID = Identifier.fromNamespaceAndPath("old_glass_photograph", "jade");

    private OldGlassPhotographJadeDataProvider() {
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        // カメラは2段ブロックで、BlockEntity は下半分にしか無い。上半分に照準を合わせても
        // 出るように、Block から下半分を引き直す（上半分だけ何も出ない、を防ぐ）。
        if (accessor.getBlockState().getBlock() instanceof WetPlateCameraBlock) {
            BlockPos base = WetPlateCameraBlock.basePos(accessor.getPosition(), accessor.getBlockState());
            if (accessor.getLevel().getBlockEntity(base) instanceof WetPlateCameraBlockEntity camera) {
                data.putInt(TYPE, CAMERA);
                writePlate(data, camera.getPlate(), accessor.getLevel());
            }
        } else if (accessor.getBlockEntity() instanceof DarkroomTableBlockEntity darkroom) {
            data.putInt(TYPE, DARKROOM);
            writePlate(data, darkroom.getPlate(), accessor.getLevel());
            data.putBoolean(WORKING, darkroom.isWorking());
            if (darkroom.isWorking()) {
                data.putInt(PROGRESS, darkroom.getWorkProgressPercent());
            }
        }
    }

    @Override
    public Identifier getUid() {
        return UID;
    }

    /**
     * 板の表示名を書き込む。
     *
     * <p>板の名前に出る残り秒（{@code PlateProcess#secondsLeft}）は表示用の写しで、
     * server の {@code inventoryTick} が 1 秒ごとに書き直している。
     * カメラや暗室台の中の板にはその tick が回らないので、写しは箱へ入れた時刻のまま止まる。
     * 乾燥の期限（{@code wetUntil}）は絶対時刻で、板が箱の中でも進むため、
     * ここでは現在の game time から引き直した値で名前を作る。
     */
    private static void writePlate(CompoundTag data, ItemStack plate, Level level) {
        if (plate.isEmpty()) {
            return;
        }
        ItemStack shown = plate;
        PlateProcess process = plate.get(OgpDataComponents.PLATE_PROCESS.get());
        if (process != null) {
            int seconds = (int) Math.max(0, (process.wetUntil() - level.getGameTime() + 19) / 20);
            if (seconds != process.secondsLeft()) {
                shown = plate.copy();
                shown.set(OgpDataComponents.PLATE_PROCESS.get(), process.withSecondsLeft(seconds));
            }
        }
        data.putString(PLATE_NAME, shown.getHoverName().getString());
    }
}

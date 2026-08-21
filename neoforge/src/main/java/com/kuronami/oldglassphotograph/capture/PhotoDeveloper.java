package com.kuronami.oldglassphotograph.capture;

import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 潜像 -> 完成写真。露光量のゲインを掛けてから map パレットへ量子化して locked filled map を作る。
 *
 * <p>量子化は <b>1 回だけ</b>ここで行う（{@code MODJAM_DESIGN_FIXES.md} A-3）。
 */
public final class PhotoDeveloper {

    private static final Logger LOG = LoggerFactory.getLogger("ogp");

    private PhotoDeveloper() {
    }

    public static boolean develop(ServerPlayer player, ItemStack plate) {
        LatentImage latent = plate.get(OgpDataComponents.LATENT_IMAGE.get());
        if (latent == null) {
            return false;
        }
        if (!latent.hasPixels()) {
            // client 側の複製（stream codec が pixel を送らない）を掴んでいる。server では起きないはず。
            LOG.error("[ogp][measure-1] latent has {} bytes (expected {}) - pixels were lost",
                    latent.pixels().length, LatentImage.SIZE);
            player.sendSystemMessage(Component.literal("The plate is blank."), true);
            return false;
        }
        int exposure = Math.clamp(latent.exposureTicks() <= 0
                ? PhotoCaptureController.NOMINAL_EXPOSURE_TICKS : latent.exposureTicks(),
                1, PhotoCaptureController.MAX_EXPOSURE_TICKS);
        // 明暗は「溜めた光の量」で決まる（ExposureModel）。潜像は露光窓のフレーム平均なので
        // 撮影地点の明るさを既に持っており、掛けるのは露光時間ぶんのゲインだけ。
        // 潜像は線形のまま保存してあるので、ゲインを掛けるのはここ 1 回だけ。
        ExposureModel.Result result = ExposureModel.evaluate(latent.pixels(), exposure, latent.light());
        byte[] exposed = ExposureModel.apply(latent.pixels(), exposure);

        LOG.info("[ogp][expose] develop: exposureTicks={} required={} dose={} light={} gain={} band={} "
                        + "meanLuma={} clipped={}% crushed={}%",
                exposure, result.requiredTicks(), result.dose(), latent.light(), result.gain(),
                result.band(), result.meanLuma(), result.clippedPct(), result.crushedPct());

        ServerLevel level = player.level();
        byte[] packed = PhotoMapPalette.quantizeAll(exposed);
        MapItemSavedData fresh = MapItemSavedData.createFresh(
                player.getX(), player.getZ(), (byte) 0, false, false, level.dimension());
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                fresh.setColor(x, y, packed[x + y * 128]);
            }
        }
        // 色を書いた後に locked() を呼ぶ（先に呼ぶと空の複製になる）
        MapItemSavedData locked = fresh.locked();
        MapId id = level.getFreeMapId();
        level.setMapData(id, locked);

        ItemStack photo = new ItemStack(Items.FILLED_MAP);
        photo.set(DataComponents.MAP_ID, id);

        plate.shrink(1);
        if (!player.addItem(photo)) {
            player.drop(photo, false);
        }
        LOG.info("[ogp] developed photograph mapId={} steps={}", id, PhotoMapPalette.stepCount());
        return true;
    }
}

package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.component.OgpComponents;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import com.kuronami.oldglassphotograph.OgpObjects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Glass Plate の工程段階を item model の override predicate へ出す。
 *
 * <p>1.21.1 には {@code SelectItemModelProperty}（26.x の {@code minecraft:select}）が無いため、
 * vanilla の compass/clock が使う {@link ItemProperties#register}（float property ＋
 * models/item 側の {@code overrides}）で同じことをする。値は stage 単位:
 * <b>0 = 素の板 / 1 = 感光・露光済み / 2 = 現像済み</b>
 * （26.x の items/glass_plate.json でも露光済みは感光と同じ絵だった）。
 * component が付いていない（＝素のガラス板）場合は 0 を返し、親モデル自身の絵が出る。
 *
 * <p>predicate の一致は「下限以上」で、{@code ItemOverrides} は JSON の配列を
 * 逆順に積んで先頭から試す（コンストラクタが {@code size()-1} から減らす・
 * 1.20.1 の Fabric / Forge 47.4.13 の bytecode で確認済み）。つまり
 * <b>JSON の末尾が最初に照合される</b>ので、models/item/glass_plate.json の overrides は
 * sensitized(1) → developed(2) の昇順に並べる。降順にすると stage 2 の板が
 * 先に 1 の override に当たって感光済みの絵になる。
 */
public final class PlateStageProperty {

    /** 登録先 id。models/item/glass_plate.json の overrides predicate と同じ名前。 */
    public static ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath(OldGlassPhotograph.MODID, "plate_stage");
    }

    /** client 初期化から呼ぶ。vanilla は ItemModelShaper の bake 前に登録される。 */
    public static void register() {
        ItemProperties.register(OgpObjects.glassPlate(), id(), PlateStageProperty::stageValue);
    }

    private static float stageValue(ItemStack stack, @Nullable ClientLevel level,
                                    @Nullable LivingEntity entity, int seed) {
        PlateProcess process = stack.get(OgpComponents.plateProcess());
        if (process == null) {
            return 0.0F;
        }
        return switch (process.stage()) {
            case SENSITIZED, EXPOSED -> 1.0F;
            case DEVELOPED -> 2.0F;
        };
    }
}

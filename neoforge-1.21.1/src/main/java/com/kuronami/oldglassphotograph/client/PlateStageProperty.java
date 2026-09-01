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
 * models/item 側の {@code overrides}）で同じことをする。
 * （26.x の items/glass_plate.json でも露光済みは感光と同じ絵だった。）
 *
 * <p><b>段階を 1 本の数値（0/1/2）で出さない。</b>predicate の一致は「しきい値以上」なので、
 * 数値 1 本だと「2 の板が 1 の override にも当たる」状態になり、どちらが先に照合されるかで
 * 絵が変わる。1.20.1 では実機で昇順・降順の両方が外れた。
 *
 * <p>そこで段階ごとに独立した 0/1 の property を出す。どの板でも 1 になる property は
 * 高々 1 つなので、<b>照合の順序に関係なく一意に決まる</b>。overrides の並びも任意でよい。
 * component が付いていない（＝素のガラス板）場合はどちらも 0 で、親モデル自身の絵が出る。
 */
public final class PlateStageProperty {

    /** 登録先 id。models/item/glass_plate.json の overrides predicate と同じ名前。 */
    public static ResourceLocation developedId() {
        return ResourceLocation.fromNamespaceAndPath(OldGlassPhotograph.MODID, "plate_developed");
    }

    /** 感光・露光済みの板か。 */
    public static ResourceLocation sensitizedId() {
        return ResourceLocation.fromNamespaceAndPath(OldGlassPhotograph.MODID, "plate_sensitized");
    }

    /** client 初期化から呼ぶ。vanilla は ItemModelShaper の bake 前に登録される。 */
    public static void register() {
        ItemProperties.register(OgpObjects.glassPlate(), developedId(), PlateStageProperty::developed);
        ItemProperties.register(OgpObjects.glassPlate(), sensitizedId(), PlateStageProperty::sensitized);
    }

    private static float developed(ItemStack stack, @Nullable ClientLevel level,
                                   @Nullable LivingEntity entity, int seed) {
        return stage(stack) == PlateProcess.Stage.DEVELOPED ? 1.0F : 0.0F;
    }

    private static float sensitized(ItemStack stack, @Nullable ClientLevel level,
                                    @Nullable LivingEntity entity, int seed) {
        PlateProcess.Stage stage = stage(stack);
        return stage == PlateProcess.Stage.SENSITIZED || stage == PlateProcess.Stage.EXPOSED
                ? 1.0F : 0.0F;
    }

    private static PlateProcess.@Nullable Stage stage(ItemStack stack) {
        PlateProcess process = stack.get(OgpComponents.plateProcess());
        return process == null ? null : process.stage();
    }
}

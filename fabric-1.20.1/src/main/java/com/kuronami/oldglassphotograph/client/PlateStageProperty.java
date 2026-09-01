package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.OgpObjects;
import com.kuronami.oldglassphotograph.component.OgpNbt;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Glass Plate の工程段階を item model の override predicate へ出す。
 *
 * <p>26.x の {@code SelectItemModelProperty}（{@code minecraft:select}）が無い帯なので、
 * vanilla の compass/clock が使う {@link ItemProperties#register}（float property ＋
 * models/item 側の {@code overrides}）で同じことをする。
 *
 * <p><b>段階を 1 本の数値（0/1/2）で出さない。</b>predicate の一致は「しきい値以上」なので、
 * 数値 1 本だと「2 の板が 1 の override にも当たる」状態になり、どちらが先に照合されるかで
 * 絵が変わる。照合の向きは読み違えやすく、実際に 1.20.1 で並び順を 2 度ひっくり返した。
 *
 * <p>そこで段階ごとに独立した 0/1 の property を出す。どの板でも 1 になる property は
 * 高々 1 つなので、<b>照合の順序に関係なく一意に決まる</b>。overrides の並びも任意でよい。
 */
public final class PlateStageProperty {

    /** 登録先 id。models/item/glass_plate.json の overrides predicate と同じ名前。 */
    public static ResourceLocation developedId() {
        return new ResourceLocation(OldGlassPhotograph.MODID, "plate_developed");
    }

    /** 感光・露光済みの板か。 */
    public static ResourceLocation sensitizedId() {
        return new ResourceLocation(OldGlassPhotograph.MODID, "plate_sensitized");
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
        PlateProcess process = OgpNbt.process(stack);
        return process == null ? null : process.stage();
    }
}

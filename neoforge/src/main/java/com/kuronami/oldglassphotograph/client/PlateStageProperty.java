package com.kuronami.oldglassphotograph.client;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperty;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterSelectItemModelPropertyEvent;
import org.jspecify.annotations.Nullable;

/**
 * Glass Plate の {@code minecraft:select} 用カスタム property。
 *
 * <p>{@link PlateProcess} 全体を {@code minecraft:component} で照合しようとすると、
 * CODEC が {@code wet_until}（絶対 game tick）/ {@code seconds_left}（毎秒書き換わる）を
 * 含むため固定リテラルの {@code when} と一致しない
 * （{@code MODJAM_DECISIONS_OGP.md} §24）。ここでは {@link PlateProcess#stage()} だけを取り出す。
 *
 * <p>component が付いていない（＝素のガラス板）場合は {@code null} を返す。
 * {@code minecraft:select} は非一致の値を {@code fallback} モデルへ落とすので、
 * {@code items/glass_plate.json} 側の {@code fallback} が素の絵を担う。
 *
 * <p>vanilla {@code Charge}（{@code minecraft:charge_type}）と同じ形の
 * 無引数 record 実装（{@code decompile net/minecraft/client/renderer/item/properties/select/Charge.java}）。
 * このクラスは {@code client} パッケージにしか存在しない（{@code common}/{@code server} から参照されない）ので、
 * vanilla と違い {@code @OnlyIn} は付けない（26.2 では member-stripping が働かず
 * {@code OnlyInWarningsHandler} が ERROR ログを出すだけで、このリポの他クラスも未使用）。
 */
public record PlateStageProperty() implements SelectItemModelProperty<PlateProcess.Stage> {

    private static final Codec<PlateProcess.Stage> VALUE_CODEC = PlateProcess.Stage.CODEC;

    public static final SelectItemModelProperty.Type<PlateStageProperty, PlateProcess.Stage> TYPE =
            SelectItemModelProperty.Type.create(MapCodec.unit(new PlateStageProperty()), VALUE_CODEC);

    @Override
    public PlateProcess.@Nullable Stage get(
            ItemStack itemStack, @Nullable ClientLevel level, @Nullable LivingEntity owner, int seed, ItemDisplayContext displayContext) {
        PlateProcess process = itemStack.get(OgpDataComponents.PLATE_PROCESS.get());
        return process == null ? null : process.stage();
    }

    @Override
    public SelectItemModelProperty.Type<PlateStageProperty, PlateProcess.Stage> type() {
        return TYPE;
    }

    @Override
    public Codec<PlateProcess.Stage> valueCodec() {
        return VALUE_CODEC;
    }

    /** {@code RegisterSelectItemModelPropertyEvent}（mod bus）で {@code old_glass_photograph:plate_stage} を登録する。 */
    public static void register(RegisterSelectItemModelPropertyEvent event) {
        event.register(Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "plate_stage"), TYPE);
    }
}

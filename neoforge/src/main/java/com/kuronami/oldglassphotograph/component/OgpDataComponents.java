package com.kuronami.oldglassphotograph.component;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** OGP の data component 登録。 */
public final class OgpDataComponents {

    /**
     * 潜像。
     *
     * <p><b>stream codec を明示しない。</b> 26.2 の {@code DataComponentType.Builder#build()} は
     * streamCodec が null のとき {@code ByteBufCodecs.fromCodecWithRegistries(codec)} を使うので、
     * 既定のままで pixel も同期される（{@code MODJAM_DECISIONS_OGP.md} §5 の (c)）。
     *
     * <p>pixel を送らない stream codec を置くと、creative でプレートをスロット操作した瞬間に
     * {@code handleSetCreativeModeSlot} が client 側の空の潜像で server を上書きし、
     * 露光済みの板が黙って白紙に戻る。代償は container 同期のたびに 16KB が飛ぶことだが、
     * 潜像を持った板が存在するのは露光から現像までの短い区間だけ。
     */
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, OldGlassPhotograph.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LatentImage>> LATENT_IMAGE =
            COMPONENTS.register("latent_image",
                    () -> DataComponentType.<LatentImage>builder()
                            .persistent(LatentImage.CODEC)
                            .build());

    /** 板の工程状態と乾燥までの残り。tooltip と表示名がこれを読む。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PlateProcess>> PLATE_PROCESS =
            COMPONENTS.register("plate_process",
                    () -> DataComponentType.<PlateProcess>builder()
                            .persistent(PlateProcess.CODEC)
                            .networkSynchronized(PlateProcess.STREAM_CODEC)
                            .build());

    private OgpDataComponents() {
    }

    public static void init(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }
}

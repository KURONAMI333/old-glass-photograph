package com.kuronami.oldglassphotograph.component;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** OGP data-component registration. */
public final class OgpDataComponents {

    /**
     * Latent-image pixels are persistently encoded on the plate stack.
     *
     * <p>In 26.2, {@link DataComponentType.Builder#build()} generates a stream codec from the
     * persistent codec via {@code ByteBufCodecs.fromCodecWithRegistries(codec)} when one is not
     * explicitly supplied. Consequently these pixels retain their existing network synchronization
     * behavior. See {@code MODJAM_DECISIONS_OGP.md} section 5(c).
     *
     * <p>A stream codec that omitted pixels would let a Creative-mode slot update overwrite the
     * server's latent image with the client's empty copy. The existing codec avoids that loss at
     * the cost of sending 16 KiB during the short exposed-to-developed interval.
     */
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, OldGlassPhotograph.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LatentImage>> LATENT_IMAGE =
            COMPONENTS.register("latent_image",
                    () -> DataComponentType.<LatentImage>builder()
                            .persistent(LatentImage.CODEC)
                            .build());

    /** Plate process stage and wetness countdown, shown in the plate name and tooltip. */
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

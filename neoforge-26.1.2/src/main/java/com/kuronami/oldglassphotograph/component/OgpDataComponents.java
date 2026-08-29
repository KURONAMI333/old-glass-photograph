package com.kuronami.oldglassphotograph.component;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.ExtraCodecs;
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

    /**
     * Plate process stage and wetness countdown, shown in the plate name and tooltip.
     *
     * <p>{@code ignoreSwapAnimation} が要る。残り秒は 1 秒ごとに書き変わるので、
     * これが無いと {@code ItemInHandRenderer.tick} が毎秒「別のアイテムに持ち替えた」と見なして
     * 装備し直しのモーションを出す（26.2 {@code ItemInHandRenderer.shouldInstantlyReplaceVisibleItem}
     * が {@code ItemStack.matchesIgnoringComponents(.., DataComponentType::ignoreSwapAnimation)} を見る）。
     * vanilla は同じ症状を持つ {@code minecraft:damage} に同じ印を付けている。
     *
     * <p>この印は<b>持ち替えモーションの判定にだけ</b>効く。名前・tooltip・モデルは従来どおり
     * 毎秒更新されるので、残り秒の表示は消えない。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PlateProcess>> PLATE_PROCESS =
            COMPONENTS.register("plate_process",
                    () -> DataComponentType.<PlateProcess>builder()
                            .persistent(PlateProcess.CODEC)
                            .networkSynchronized(PlateProcess.STREAM_CODEC)
                            .ignoreSwapAnimation()
                            .build());

    /**
     * Darkroom Table の蓋を開けたまま工程を回した tick 数（かぶり量）。
     *
     * <p><b>{@link #PLATE_PROCESS} と同じ record に入れてはいけない。</b>
     * {@code PhotoCaptureController} は露光の完了時に {@code PlateProcess} を
     * <b>新しい record で丸ごと差し替える</b>ので、そこに持たせた値は塗布から現像へ渡らずに消える。
     * 独立した component なら差し替えの巻き添えにならず、塗布で入ったかぶりが現像まで残る。
     *
     * <p>板が乾いて素のガラスへ戻るとき（{@code GlassPlateItem.resolveDryOut}）は
     * この値も消す。消さないと 1 枚のガラスにかぶりが永久に溜まり続ける。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> PLATE_FOG =
            COMPONENTS.register("plate_fog",
                    () -> DataComponentType.<Integer>builder()
                            .persistent(ExtraCodecs.NON_NEGATIVE_INT)
                            .networkSynchronized(ByteBufCodecs.VAR_INT)
                            .build());

    /**
     * 完成した写真に載る撮影者と日付（{@code MODJAM_DECISIONS_OGP.md} §32-5）。
     *
     * <p><b>写真にしか付かない。</b>板の工程は {@link #PLATE_PROCESS} を丸ごと差し替えるので、
     * 途中の段に載せると現像で消える。{@code PhotoDeveloper} が写真を作る瞬間に 1 回だけ書く。
     *
     * <p>これが入る前に現像された写真には付いていない。読む側は必ず「無い」を通す
     * （じっくり見る面は撮影者の行を出さないだけで、写真は同じように出る）。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PhotoCredit>> PHOTO_CREDIT =
            COMPONENTS.register("photo_credit",
                    () -> DataComponentType.<PhotoCredit>builder()
                            .persistent(PhotoCredit.CODEC)
                            .networkSynchronized(PhotoCredit.STREAM_CODEC)
                            .build());

    private OgpDataComponents() {
    }

    public static void init(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }
}

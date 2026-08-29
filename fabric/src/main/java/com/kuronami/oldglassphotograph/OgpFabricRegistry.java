package com.kuronami.oldglassphotograph;

import com.kuronami.oldglassphotograph.block.DarkroomTableBlock;
import com.kuronami.oldglassphotograph.block.DarkroomTableBlockEntity;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlock;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlockEntity;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.component.OgpComponents;
import com.kuronami.oldglassphotograph.component.PhotoCredit;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.kuronami.oldglassphotograph.item.PhotographItem;
import com.kuronami.oldglassphotograph.item.WetPlateCameraBlockItem;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Fabric 側の登録一式。<b>client 参照ゼロ</b>（dedicated server がこのクラスをロードする）。
 *
 * <p>NeoForge の {@code DeferredRegister}（{@code OgpRegistry}）と同じ id・同じ既定値で、
 * vanilla {@code Registry.register} の即時登録で再現する。26.x は登録 id を
 * Properties へ set することが vanilla 要件なので、ここでは明示的に {@code setId} する
 * （NeoForge では DeferredRegister.Items/Blocks が内部で setId していた分）。
 * block item の翻訳キーを {@code block.*} へ寄せる {@code useBlockDescriptionPrefix} も同様に再現する。
 */
public final class OgpFabricRegistry {

    /** ローダー側のタブ内容イベントが対象タブを指すためのキー。OgpRegistry.TAB_KEY と同じ値。 */
    public static final ResourceKey<CreativeModeTab> TAB_KEY = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "main"));

    public static final WetPlateCameraBlock WET_PLATE_CAMERA = registerBlock("wet_plate_camera",
            WetPlateCameraBlock::new,
            () -> BlockBehaviour.Properties.of().strength(1.5F).sound(SoundType.WOOD).noOcclusion());

    public static final Item WET_PLATE_CAMERA_ITEM = registerItem("wet_plate_camera",
            properties -> new WetPlateCameraBlockItem(WET_PLATE_CAMERA, properties.useBlockDescriptionPrefix()));

    public static final DarkroomTableBlock DARKROOM_TABLE = registerBlock("darkroom_table",
            DarkroomTableBlock::new,
            () -> BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.WOOD).noOcclusion());

    public static final Item DARKROOM_TABLE_ITEM = registerItem("darkroom_table",
            properties -> new BlockItem(DARKROOM_TABLE, properties.useBlockDescriptionPrefix()));

    /**
     * <b>素の板は重なり、工程に入った板は 1 枚ずつになる。</b>NeoForge 版と同じ上限。
     */
    public static final GlassPlateItem GLASS_PLATE = registerItem("glass_plate",
            GlassPlateItem::new,
            () -> new Item.Properties().stacksTo(GlassPlateItem.BLANK_MAX_STACK));

    public static final PhotographItem PHOTOGRAPH = registerItem("photograph",
            PhotographItem::new,
            () -> new Item.Properties().stacksTo(1));

    public static final Item COLLODION_KIT = registerSimpleItem("collodion_kit");
    public static final Item DEVELOPER = registerSimpleItem("developer");
    public static final Item FIXER = registerSimpleItem("fixer");

    public static final CreativeModeTab TAB = register(Registries.CREATIVE_MODE_TAB, "main",
            // 26.2 vanilla は builder(Row, int) のみ。中身は CreativeModeTabEvents で流す。
            CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup." + OldGlassPhotograph.MODID))
                    .icon(() -> new ItemStack(WET_PLATE_CAMERA_ITEM))
                    .build());

    // 26.2 vanilla の BlockEntityType は (BlockEntitySupplier, Set<Block>) のみ。
    public static final BlockEntityType<WetPlateCameraBlockEntity> CAMERA_BLOCK_ENTITY = register(
            Registries.BLOCK_ENTITY_TYPE, "wet_plate_camera",
            new BlockEntityType<>(WetPlateCameraBlockEntity::new, Set.of(WET_PLATE_CAMERA)));

    public static final BlockEntityType<DarkroomTableBlockEntity> DARKROOM_TABLE_BLOCK_ENTITY = register(
            Registries.BLOCK_ENTITY_TYPE, "darkroom_table",
            new BlockEntityType<>(DarkroomTableBlockEntity::new, Set.of(DARKROOM_TABLE)));

    public static final DataComponentType<LatentImage> LATENT_IMAGE = register(
            Registries.DATA_COMPONENT_TYPE, "latent_image",
            DataComponentType.<LatentImage>builder()
                    .persistent(LatentImage.CODEC)
                    .build());

    public static final DataComponentType<PlateProcess> PLATE_PROCESS = register(
            Registries.DATA_COMPONENT_TYPE, "plate_process",
            DataComponentType.<PlateProcess>builder()
                    .persistent(PlateProcess.CODEC)
                    .networkSynchronized(PlateProcess.STREAM_CODEC)
                    .ignoreSwapAnimation()
                    .build());

    public static final DataComponentType<Integer> PLATE_FOG = register(
            Registries.DATA_COMPONENT_TYPE, "plate_fog",
            DataComponentType.<Integer>builder()
                    .persistent(ExtraCodecs.NON_NEGATIVE_INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    public static final DataComponentType<PhotoCredit> PHOTO_CREDIT = register(
            Registries.DATA_COMPONENT_TYPE, "photo_credit",
            DataComponentType.<PhotoCredit>builder()
                    .persistent(PhotoCredit.CODEC)
                    .networkSynchronized(PhotoCredit.STREAM_CODEC)
                    .build());

    private OgpFabricRegistry() {
    }

    /** タッチすることで static 初期化（＝即時登録）が走る。onInitialize 中はレジストリが開いている。 */
    public static void init() {
        OgpObjects.wire(() -> CAMERA_BLOCK_ENTITY, () -> DARKROOM_TABLE_BLOCK_ENTITY,
                () -> WET_PLATE_CAMERA, () -> DARKROOM_TABLE,
                () -> GLASS_PLATE, () -> PHOTOGRAPH,
                () -> COLLODION_KIT, () -> DEVELOPER, () -> FIXER);
        OgpComponents.wire(() -> LATENT_IMAGE, () -> PLATE_PROCESS, () -> PLATE_FOG, () -> PHOTO_CREDIT);
    }

    /** NeoForge の DeferredRegister.Blocks.registerBlock 相当（id を Properties へ set する）。 */
    private static <B extends Block> B registerBlock(String name,
                                                     Function<BlockBehaviour.Properties, B> factory,
                                                     Supplier<BlockBehaviour.Properties> properties) {
        Identifier id = Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, name);
        return Registry.register(BuiltInRegistries.BLOCK, id,
                factory.apply(properties.get().setId(ResourceKey.create(Registries.BLOCK, id))));
    }

    /** NeoForge の DeferredRegister.Items.registerItem(name, func, propsSupplier) 相当。 */
    private static <I extends Item> I registerItem(String name,
                                                   Function<Item.Properties, I> factory) {
        Identifier id = Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, name);
        return Registry.register(BuiltInRegistries.ITEM, id,
                factory.apply(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))));
    }

    private static <I extends Item> I registerItem(String name,
                                                   Function<Item.Properties, I> factory,
                                                   Supplier<Item.Properties> properties) {
        Identifier id = Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, name);
        return Registry.register(BuiltInRegistries.ITEM, id,
                factory.apply(properties.get().setId(ResourceKey.create(Registries.ITEM, id))));
    }

    private static Item registerSimpleItem(String name) {
        return registerItem(name, Item::new);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T register(ResourceKey registryKey, String name, T value) {
        Registry registry = BuiltInRegistries.REGISTRY.getValue(registryKey.identifier());
        if (registry == null) {
            throw new IllegalStateException("root registry が見つからない: " + registryKey.identifier());
        }
        return (T) Registry.register(registry, Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, name), value);
    }
}

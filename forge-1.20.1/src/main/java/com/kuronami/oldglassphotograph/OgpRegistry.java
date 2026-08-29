package com.kuronami.oldglassphotograph;

import com.kuronami.oldglassphotograph.block.DarkroomTableBlock;
import com.kuronami.oldglassphotograph.block.DarkroomTableBlockEntity;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlock;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlockEntity;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.kuronami.oldglassphotograph.item.PhotographItem;
import com.kuronami.oldglassphotograph.item.WetPlateCameraBlockItem;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Set;

/**
 * Forge 側の登録一式（DeferredRegister）。<b>client 参照ゼロ</b>（dedicated server がこのクラスを
 * ロードする）。1.20.1 の Forge は vanilla レジストリへの直接 {@code Registry.register} を
 * 許さないため、fabric セルの即時登録形を DeferredRegister へ写した（neoforge-1.21.1 セルと同じ骨格）。
 */
public final class OgpRegistry {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, OldGlassPhotograph.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, OldGlassPhotograph.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, OldGlassPhotograph.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, OldGlassPhotograph.MODID);

    /** ローダー側のタブ内容イベントが対象タブを指すためのキー。 */
    public static final ResourceKey<CreativeModeTab> TAB_KEY = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, id("main"));

    /**
     * 湿板カメラ。高さ 2 ブロック。{@code noOcclusion} の理由は neoforge-1.21.1 セルの同フィールド参照
     * （隣接面 cull の実機検証済み事象）。
     */
    public static final RegistryObject<WetPlateCameraBlock> WET_PLATE_CAMERA = BLOCKS.register("wet_plate_camera",
            () -> new WetPlateCameraBlock(BlockBehaviour.Properties.of()
                    .strength(1.5F).sound(SoundType.WOOD).noOcclusion()));

    /** 携帯暗箱。<b>板を中へ入れて蓋を閉じると</b>準備と現像が進む。GUI は持たない。 */
    public static final RegistryObject<DarkroomTableBlock> DARKROOM_TABLE = BLOCKS.register("darkroom_table",
            () -> new DarkroomTableBlock(BlockBehaviour.Properties.of()
                    .strength(2.0F).sound(SoundType.WOOD).noOcclusion()));

    /** 湿板カメラの BlockItem。設置時の向きが撮影方向になる。 */
    public static final RegistryObject<BlockItem> WET_PLATE_CAMERA_ITEM = ITEMS.register("wet_plate_camera",
            () -> new WetPlateCameraBlockItem(WET_PLATE_CAMERA.get(), new Item.Properties()));

    public static final RegistryObject<BlockItem> DARKROOM_TABLE_ITEM = ITEMS.register("darkroom_table",
            () -> new BlockItem(DARKROOM_TABLE.get(), new Item.Properties()));

    /**
     * <b>素の板は重なり、工程に入った板は 1 枚ずつになる。</b>
     * 工程に入る瞬間に {@link com.kuronami.oldglassphotograph.component.OgpNbt#markSingle} が
     * 個体タグを書き、NBT の異なるスタックが重ならない仕組みで per-stack 上限と同じ結果にする。
     */
    public static final RegistryObject<GlassPlateItem> GLASS_PLATE = ITEMS.register("glass_plate",
            () -> new GlassPlateItem(new Item.Properties().stacksTo(GlassPlateItem.BLANK_MAX_STACK)));

    /** Finished wet-plate photograph; MapItem inheritance preserves vanilla map-data persistence and sync. */
    public static final RegistryObject<PhotographItem> PHOTOGRAPH = ITEMS.register("photograph",
            () -> new PhotographItem(new Item.Properties().stacksTo(1)));

    /** 板の準備（洗浄 + コロジオン + 銀浴）を 1 操作にまとめた薬品。 */
    public static final RegistryObject<Item> COLLODION_KIT = ITEMS.register("collodion_kit",
            () -> new Item(new Item.Properties()));

    /** 現像液。 */
    public static final RegistryObject<Item> DEVELOPER = ITEMS.register("developer",
            () -> new Item(new Item.Properties()));

    /** 定着液。 */
    public static final RegistryObject<Item> FIXER = ITEMS.register("fixer",
            () -> new Item(new Item.Properties()));

    /** この MOD 専用タブ。アイコンはカメラ（MOD の顔）。 */
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup." + OldGlassPhotograph.MODID))
                    .icon(() -> new ItemStack(WET_PLATE_CAMERA.get()))
                    .build());

    /** 1.20.1 の BlockEntityType は (supplier, validBlocks, dataFixerType) の 3 引数。 */
    public static final RegistryObject<BlockEntityType<WetPlateCameraBlockEntity>> CAMERA_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("wet_plate_camera",
                    () -> new BlockEntityType<>(WetPlateCameraBlockEntity::new,
                            Set.of(WET_PLATE_CAMERA.get()), null));

    /** 箱の中の板と、走っている工程を持つ。 */
    public static final RegistryObject<BlockEntityType<DarkroomTableBlockEntity>> DARKROOM_TABLE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("darkroom_table",
                    () -> new BlockEntityType<>(DarkroomTableBlockEntity::new,
                            Set.of(DARKROOM_TABLE.get()), null));

    private OgpRegistry() {
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
        modBus.addListener(OgpRegistry::buildCreativeTab);
    }

    /** common setup（レジストリ確定後）から呼ぶ。大釜で洗う経路の登録。 */
    public static void registerCauldronInteractions() {
        CauldronInteraction.WATER.put(GLASS_PLATE.get(), GlassPlateItem::washInCauldron);
    }

    /**
     * 独自タブの中身を工程順で流す: 板 → 薬品（増感 → 現像 → 定着）→ カメラ → 暗箱。
     * 写真はタブに出さない（像を持たない写真は白紙の板でしかない＝26.x と同じ判断）。
     */
    private static void buildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == null || !event.getTabKey().equals(TAB_KEY)) {
            return;
        }
        event.accept(GLASS_PLATE.get());
        event.accept(COLLODION_KIT.get());
        event.accept(DEVELOPER.get());
        event.accept(FIXER.get());
        event.accept(WET_PLATE_CAMERA_ITEM.get());
        event.accept(DARKROOM_TABLE_ITEM.get());
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(OldGlassPhotograph.MODID, path);
    }
}

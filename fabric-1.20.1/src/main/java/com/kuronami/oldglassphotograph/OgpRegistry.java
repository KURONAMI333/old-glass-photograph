package com.kuronami.oldglassphotograph;

import com.kuronami.oldglassphotograph.block.DarkroomTableBlock;
import com.kuronami.oldglassphotograph.block.DarkroomTableBlockEntity;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlock;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlockEntity;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.kuronami.oldglassphotograph.item.PhotographItem;
import com.kuronami.oldglassphotograph.item.WetPlateCameraBlockItem;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.Set;

/**
 * vanilla {@code Registry.register} 縺ｫ繧医ｋ逋ｻ骭ｲ荳蠑擾ｼ・abric・峨・b>client 蜿ら・繧ｼ繝ｭ</b>
 * ・・edicated server 縺後％縺ｮ繧ｯ繝ｩ繧ｹ繧偵Ο繝ｼ繝峨☆繧具ｼ峨・ *
 * <p>NeoForge 縺ｮ DeferredRegister・・.21.1 繧ｻ繝ｫ・峨↓蟇ｾ縺励※縲√％縺ｮ蟶ｯ縺ｧ縺ｯ
 * 縲鶏@code onInitialize} 縺ｮ荳ｭ縺ｧ蜊ｳ譎・{@code Registry.register}縲阪′ Fabric 縺ｮ豁｣縺励＞蠖｢縲・ * 髱咏噪繝輔ぅ繝ｼ繝ｫ繝峨・螳｣險鬆・ｼ晉匳骭ｲ鬆・↑縺ｮ縺ｧ縲√ヶ繝ｭ繝・け 竊・BlockItem 竊・BE 蝙・竊・繧ｿ繝悶・鬆・↓荳ｦ縺ｹ繧九・ */
public final class OgpRegistry {

    /** 繝ｭ繝ｼ繝繝ｼ蛛ｴ縺ｮ繧ｿ繝門・螳ｹ繧､繝吶Φ繝医′蟇ｾ雎｡繧ｿ繝悶ｒ謖・☆縺溘ａ縺ｮ繧ｭ繝ｼ縲・*/
    public static final ResourceKey<CreativeModeTab> TAB_KEY = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, id("main"));

    /**
     * 貉ｿ譚ｿ繧ｫ繝｡繝ｩ縲るｫ倥＆ 2 繝悶Ο繝・け・・@code MODJAM_DECISIONS_OGP.md} ﾂｧ18・峨・     *
     * <p>{@code noOcclusion} 縺瑚ｦ√ｋ: 繝｢繝・Ν縺ｯ閼夂ｫ九→陋・・縺ｧ縲’ull cube 繧貞沂繧√※縺・↑縺・・     * 譌｢螳壹・ {@code canOcclude = true} 縺ｮ縺ｾ縺ｾ縺縺ｨ髫｣謗･繝悶Ο繝・け縺ｮ髱｢縺・cull 縺輔ｌ繧・     * ・・6.2 繧ｻ繝ｫ縺ｧ螳滓ｩ滓､懆ｨｼ貂医∩縺ｮ譌｢遏･莠玖ｱ｡縲ゅさ繝｡繝ｳ繝亥・譁・・ neoforge-1.21.1 繧ｻ繝ｫ蜿ら・・峨・     */
    public static final WetPlateCameraBlock WET_PLATE_CAMERA = register(
            BuiltInRegistries.BLOCK, "wet_plate_camera",
            new WetPlateCameraBlock(BlockBehaviour.Properties.of()
                    .strength(1.5F).sound(SoundType.WOOD).noOcclusion()));

    /**
     * 謳ｺ蟶ｯ證礼ｮｱ縲・b>譚ｿ繧剃ｸｭ縺ｸ蜈･繧後※闢九ｒ髢峨§繧九→</b>貅門ｙ縺ｨ迴ｾ蜒上′騾ｲ繧
     * ・・@code MODJAM_DECISIONS_OGP.md} ﾂｧ30・峨る・蜈峨・邂ｱ縺昴・繧ゅ・縺梧戟縺､縺ｮ縺ｧ蜻ｨ蝗ｲ縺ｮ譏弱ｋ縺輔・蜉ｹ縺九↑縺・・     * GUI 縺ｯ謖√◆縺ｪ縺・・     */
    public static final DarkroomTableBlock DARKROOM_TABLE = register(
            BuiltInRegistries.BLOCK, "darkroom_table",
            new DarkroomTableBlock(BlockBehaviour.Properties.of()
                    .strength(2.0F).sound(SoundType.WOOD).noOcclusion()));

    /**
     * 貉ｿ譚ｿ繧ｫ繝｡繝ｩ縺ｮ BlockItem縲りｨｭ鄂ｮ譎ゅ・蜷代″縺梧聴蠖ｱ譁ｹ蜷代↓縺ｪ繧具ｼ・@code WetPlateCameraBlockItem}・峨・     * 蜃ｺ逋ｺ轤ｹ縺ｫ縺励◆ neoforge-1.21.1 繧ｻ繝ｫ縺ｯ縺薙・逋ｻ骭ｲ繧呈ｬ縺・※縺・ｋ縺後・6.2 common /
     * neoforge-1.21.11 縺ｧ縺ｯ逋ｻ骭ｲ縺後≠繧九・縺ｧ縲√％縺｡繧峨・縺昴■繧峨↓蜷医ｏ縺帙ｋ・育筏縺鈴√ｊ縺ｫ險倬鹸・峨・     */
    public static final BlockItem WET_PLATE_CAMERA_ITEM = register(
            BuiltInRegistries.ITEM, "wet_plate_camera",
            new WetPlateCameraBlockItem(WET_PLATE_CAMERA, new Item.Properties()));

    public static final BlockItem DARKROOM_TABLE_ITEM = register(
            BuiltInRegistries.ITEM, "darkroom_table",
            new BlockItem(DARKROOM_TABLE, new Item.Properties()));

    /**
     * <b>邏縺ｮ譚ｿ縺ｯ驥阪↑繧翫∝ｷ･遞九↓蜈･縺｣縺滓攸縺ｯ 1 譫壹★縺､縺ｫ縺ｪ繧九・/b>
     * 26.x 縺ｮ per-stack {@code MAX_STACK_SIZE} component 縺ｯ 1.20.1 縺ｫ辟｡縺・◆繧√・     * 蟾･遞九↓蜈･繧狗椪髢薙↓ {@link com.kuronami.oldglassphotograph.component.OgpNbt#markSingle}
     * 縺悟倶ｽ薙ち繧ｰ繧呈嶌縺阪¨BT 縺檎焚縺ｪ繧九せ繧ｿ繝・け縺ｯ驥阪↑繧峨↑縺・ｻ慕ｵ・∩縺ｧ蜷後§邨先棡縺ｫ縺吶ｋ縲・     */
    public static final GlassPlateItem GLASS_PLATE = register(
            BuiltInRegistries.ITEM, "glass_plate",
            new GlassPlateItem(new Item.Properties().stacksTo(GlassPlateItem.BLANK_MAX_STACK)));

    /** Finished wet-plate photograph; MapItem inheritance preserves vanilla map-data persistence and sync. */
    public static final PhotographItem PHOTOGRAPH = register(
            BuiltInRegistries.ITEM, "photograph",
            new PhotographItem(new Item.Properties().stacksTo(1)));

    /** 譚ｿ縺ｮ貅門ｙ・域ｴ玲ｵ・+ 繧ｳ繝ｭ繧ｸ繧ｪ繝ｳ + 驫豬ｴ・峨ｒ 1 謫堺ｽ懊↓縺ｾ縺ｨ繧√◆阮ｬ蜩√・*/
    public static final Item COLLODION_KIT = register(
            BuiltInRegistries.ITEM, "collodion_kit", new Item(new Item.Properties()));

    /** 迴ｾ蜒乗ｶｲ縲・*/
    public static final Item DEVELOPER = register(
            BuiltInRegistries.ITEM, "developer", new Item(new Item.Properties()));

    /** 螳夂捩豸ｲ縲・*/
    public static final Item FIXER = register(
            BuiltInRegistries.ITEM, "fixer", new Item(new Item.Properties()));

    /**
     * 縺薙・ MOD 蟆ら畑繧ｿ繝悶ゅい繧､繧ｳ繝ｳ縺ｯ繧ｫ繝｡繝ｩ・・OD 縺ｮ鬘費ｼ峨・     * 荳ｭ霄ｫ縺ｯ entry 蛛ｴ縺ｮ {@code ItemGroupEvents.modifyEntriesEvent} 縺悟ｷ･遞矩・〒豬√☆縲・     */
    public static final CreativeModeTab TAB = register(
            BuiltInRegistries.CREATIVE_MODE_TAB, "main",
            CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup." + OldGlassPhotograph.MODID))
                    .icon(() -> new ItemStack(WET_PLATE_CAMERA))
                    .build());

    /**
     * 1.20.1 縺ｮ BlockEntityType 縺ｯ (supplier, validBlocks, dataFixerType) 縺ｮ 3 蠑墓焚縲・     * datafixer 縺ｯ mod 蛛ｴ縺ｧ縺ｯ null・・anilla 縺ｮ mod 蟇ｾ蠢・BE 縺ｨ蜷後§・峨・     */
    public static final BlockEntityType<WetPlateCameraBlockEntity> CAMERA_BLOCK_ENTITY = register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, "wet_plate_camera",
            new BlockEntityType<>(WetPlateCameraBlockEntity::new, Set.of(WET_PLATE_CAMERA), null));

    /** 邂ｱ縺ｮ荳ｭ縺ｮ譚ｿ縺ｨ縲∬ｵｰ縺｣縺ｦ縺・ｋ蟾･遞九ｒ謖√▽縲・*/
    public static final BlockEntityType<DarkroomTableBlockEntity> DARKROOM_TABLE_BLOCK_ENTITY = register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, "darkroom_table",
            new BlockEntityType<>(DarkroomTableBlockEntity::new, Set.of(DARKROOM_TABLE), null));

    private OgpRegistry() {
    }

    /**
     * 豌ｴ蜈･繧雁､ｧ驥懊〒 Glass Plate 繧呈ｴ励∴繧句ｽ｢縺ｫ縺吶ｋ縲ゅ％縺ｮ蟶ｯ縺ｫ縺ｯ NeoForge 縺ｮ逋ｻ骭ｲ繧､繝吶Φ繝医′辟｡縺上・     * vanilla 縺ｮ water dispatcher 縺ｯ蜊倡ｴ斐↑ mutable {@code Map}
     * ・・@code CauldronInteraction.WATER}繝ｻ1.20.1 jar javap 螳滓ｸｬ・峨↑縺ｮ縺ｧ逶ｴ謗･ put 縺吶ｋ縲・     * vanilla 譛ｬ菴薙・ {@code bootStrap()} 縺ｨ蜷後§繝代ち繝ｼ繝ｳ縺ｧ縲］eoforge-1.21.1 繧ｻ繝ｫ縺ｮ
     * {@code WATER.map().put(...)} 縺ｨ逋ｻ骭ｲ蜈医・蜷後§縲・     */
    public static void registerCauldronInteractions() {
        CauldronInteraction.WATER.put(GLASS_PLATE, GlassPlateItem::washInCauldron);
    }

    private static <T, R extends T> R register(net.minecraft.core.Registry<T> registry, String name, R value) {
        net.minecraft.core.Registry.register(registry, id(name), value);
        return value;
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(OldGlassPhotograph.MODID, path);
    }
}

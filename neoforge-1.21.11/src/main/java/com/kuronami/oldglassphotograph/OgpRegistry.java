package com.kuronami.oldglassphotograph;

import com.kuronami.oldglassphotograph.block.DarkroomTableBlock;
import com.kuronami.oldglassphotograph.block.DarkroomTableBlockEntity;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlock;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlockEntity;
import com.kuronami.oldglassphotograph.capture.PhotoCaptureController;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.kuronami.oldglassphotograph.item.PhotographItem;
import com.kuronami.oldglassphotograph.item.WetPlateCameraBlockItem;
import net.minecraft.world.item.BlockItem;
import com.kuronami.oldglassphotograph.network.PhotoCaptureAbortPayload;
import com.kuronami.oldglassphotograph.network.ShutterOpenPayload;
import com.kuronami.oldglassphotograph.network.ShutterRequestPayload;
import com.kuronami.oldglassphotograph.network.ViewfinderClosePayload;
import com.kuronami.oldglassphotograph.network.ViewfinderOpenPayload;
import com.kuronami.oldglassphotograph.network.PhotoMapPixelsPayload;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * NeoForge 側の登録一式。<b>client 参照ゼロ</b>（dedicated server がこのクラスをロードする）。
 *
 * <p>クラスの正本上の置き場は common だが、この塊は NeoForge 単独で検証する方針なので
 * まだ neoforge モジュールに置いている（申し送り事項）。
 */
public final class OgpRegistry {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(OldGlassPhotograph.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(OldGlassPhotograph.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, OldGlassPhotograph.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, OldGlassPhotograph.MODID);

    /** ローダー側のタブ内容イベントが対象タブを指すためのキー。 */
    public static final ResourceKey<CreativeModeTab> TAB_KEY = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "main"));

    /**
     * 湿板カメラ。高さ 2 ブロック（{@code MODJAM_DECISIONS_OGP.md} §18）。
     *
     * <p>{@code noOcclusion} が要る: モデルは脚立と蛇腹で、full cube を埋めていない。
     * 既定の {@code canOcclude = true} のままだと {@code BlockStateBase.initCache} が
     * occlusion shape を full cube として焼き（26.2 {@code BlockBehaviour} L498）、
     * 隣接ブロックの面が cull される。実害: 下半分が載っている地面の上面が描かれず、
     * 地下の洞窟が透けて見えた（2026-08-22 実機検証・{@code run-client/screenshots/2026-08-22_18.26.14.png}）。
     *
     * <p>露光には影響しない。{@code ExposureModel.sampleLight} が読むのは
     * {@code cameraPos.relative(facing)}＝レンズの前の 1 マスで、カメラ自身の遮光性とは無関係。
     */
    public static final DeferredBlock<WetPlateCameraBlock> WET_PLATE_CAMERA = BLOCKS.registerBlock(
            "wet_plate_camera",
            WetPlateCameraBlock::new,
            () -> BlockBehaviour.Properties.of().strength(1.5F).sound(SoundType.WOOD).noOcclusion());

    /**
     * {@code useBlockDescriptionPrefix} が要る: 26.2 の {@link net.minecraft.world.item.BlockItem} は
     * {@code getDescriptionId} を上書きしない。翻訳キーは {@code Item.Properties} が持っていて、
     * 既定は {@code ITEM_DESCRIPTION_ID}＝{@code item.<ns>.<path>} になる。
     * これを付けないと lang の {@code block.old_glass_photograph.wet_plate_camera} を引かず、
     * 内部名がそのまま表示される（vanilla のブロックアイテムと NeoForge の
     * {@code registerSimpleBlockItem} は必ずこれを付けている）。
     */
    public static final DeferredItem<WetPlateCameraBlockItem> WET_PLATE_CAMERA_ITEM = ITEMS.registerItem(
            "wet_plate_camera",
            properties -> new WetPlateCameraBlockItem(
                    WET_PLATE_CAMERA.get(), properties.useBlockDescriptionPrefix()));

    /**
     * 携帯暗箱。<b>板を中へ入れて蓋を閉じると</b>準備と現像が進む
     * （{@code MODJAM_DECISIONS_OGP.md} §30）。遮光は箱そのものが持つので周囲の明るさは効かない。
     * GUI は持たない。
     *
     * <p>{@code noOcclusion} が要る: モデルは高さ 13 の脚立で、隣の面を塞がない。
     */
    public static final DeferredBlock<DarkroomTableBlock> DARKROOM_TABLE = BLOCKS.registerBlock(
            "darkroom_table",
            DarkroomTableBlock::new,
            () -> BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.WOOD).noOcclusion());

    /** {@code useBlockDescriptionPrefix} の理由は {@link #WET_PLATE_CAMERA_ITEM} と同じ。 */
    public static final DeferredItem<BlockItem> DARKROOM_TABLE_ITEM = ITEMS.registerItem(
            "darkroom_table",
            properties -> new BlockItem(DARKROOM_TABLE.get(), properties.useBlockDescriptionPrefix()));

    /**
     * <b>素の板は重なり、工程に入った板は 1 枚ずつになる。</b>
     * ここで決まるのは素の板の上限だけで、工程に入った板は
     * {@code GlassPlateItem} がスタックごとに {@code MAX_STACK_SIZE} を 1 へ落とす
     * （束ねると潜像と期限を共有してしまうため）。
     */
    public static final DeferredItem<GlassPlateItem> GLASS_PLATE = ITEMS.registerItem(
            "glass_plate",
            GlassPlateItem::new,
            () -> new Item.Properties().stacksTo(GlassPlateItem.BLANK_MAX_STACK));

    /** Finished wet-plate photograph; MapItem inheritance preserves vanilla map-data persistence and sync. */
    public static final DeferredItem<PhotographItem> PHOTOGRAPH = ITEMS.registerItem(
            "photograph",
            PhotographItem::new,
            () -> new Item.Properties().stacksTo(1));

    /** 板の準備（洗浄 + コロジオン + 銀浴）を 1 操作にまとめた薬品。 */
    public static final DeferredItem<Item> COLLODION_KIT = ITEMS.registerSimpleItem("collodion_kit");

    /** 現像液。 */
    public static final DeferredItem<Item> DEVELOPER = ITEMS.registerSimpleItem("developer");

    /** 定着液。 */
    public static final DeferredItem<Item> FIXER = ITEMS.registerSimpleItem("fixer");

    /**
     * この MOD 専用タブ。アイコンはカメラ（MOD の顔）。
     * 中身は {@link #buildCreativeTab} が並び順を工程順で流す。
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + OldGlassPhotograph.MODID))
                    .icon(() -> new ItemStack(WET_PLATE_CAMERA_ITEM.get()))
                    .build());

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WetPlateCameraBlockEntity>>
            CAMERA_BLOCK_ENTITY = BLOCK_ENTITIES.register("wet_plate_camera",
            () -> new BlockEntityType<>(WetPlateCameraBlockEntity::new, WET_PLATE_CAMERA.get()));

    /** 箱の中の板と、走っている工程を持つ。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DarkroomTableBlockEntity>>
            DARKROOM_TABLE_BLOCK_ENTITY = BLOCK_ENTITIES.register("darkroom_table",
            () -> new BlockEntityType<>(DarkroomTableBlockEntity::new, DARKROOM_TABLE.get()));

    private OgpRegistry() {
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
        OgpDataComponents.init(modBus);
        modBus.addListener(OgpRegistry::registerPayloads);
        modBus.addListener(OgpRegistry::buildCreativeTab);
        registerCauldronInteractions();
    }

    /**
     * 水入り大釜で Glass Plate を洗える形にする。1.21.11 の NeoForge には
     * {@code RegisterCauldronInteractionEvent} が無い（21.11.42 universal jar で実名確認・
     * {@code Cauldron} を含む NeoForge クラスは流体系のみ）ので、vanilla の登録面へ
     * 直接入る。{@code CauldronInteraction.WATER} は {@code InteractionMap} record で、
     * {@code map()} accessor が public かつ中身が mutable（{@code Object2ObjectOpenHashMap}）。
     * vanilla 本体の {@code bootStrap()} が {@code WATER.map().put(...)} の同じ形で
     * 既定相互作用を登録しているので、mod 側も同じ呼びで足す
     * （{@code MC: net/minecraft/core/cauldron/CauldronInteraction.java}）。
     * 呼び出し側の {@code AbstractCauldronBlock} は dispatcher の map を読んでいるので、
     * put したエントリは右クリック経路で有効になる。26.x セルのイベント方式と
     * 登録先は同じ water dispatcher。
     */
    private static void registerCauldronInteractions() {
        CauldronInteraction.WATER.map().put(GLASS_PLATE.get(), GlassPlateItem::washInCauldron);
    }

    /**
     * 独自タブの中身を工程順で流す: 板 → 薬品（増感 → 現像 → 定着） → カメラ → 暗箱 → 写真。
     * バニラタブへは配らない（二重表示防止。旧: FUNCTIONAL_BLOCKS / TOOLS_AND_UTILITIES）。
     */
    private static void buildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(TAB_KEY)) {
            event.accept(GLASS_PLATE.get());
            event.accept(COLLODION_KIT.get());
            event.accept(DEVELOPER.get());
            event.accept(FIXER.get());
            event.accept(WET_PLATE_CAMERA_ITEM.get());
            event.accept(DARKROOM_TABLE_ITEM.get());
            // 写真はタブに出さない。像を持たない写真は白紙の板でしかなく、
            // JEI の一覧にも中身の無いアイテムとして並ぶ（出所: 2026-08-23 実機検証）。
            // 入手経路は定着だけで、クリエイティブから配る意味が無い。
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        // S2C はハンドラ無しで登録し、実体は client の RegisterClientPayloadHandlersEvent で付ける。
        registrar.playToClient(ViewfinderOpenPayload.TYPE, ViewfinderOpenPayload.CODEC);
        registrar.playToClient(ShutterOpenPayload.TYPE, ShutterOpenPayload.CODEC);
        registrar.playToClient(ViewfinderClosePayload.TYPE, ViewfinderClosePayload.CODEC);
        registrar.playToServer(ShutterRequestPayload.TYPE, ShutterRequestPayload.CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        PhotoCaptureController.openShutter(player, payload);
                    }
                });
        registrar.playToServer(PhotoMapPixelsPayload.TYPE, PhotoMapPixelsPayload.CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        PhotoCaptureController.receivePixels(player, payload);
                    }
                });
        registrar.playToServer(PhotoCaptureAbortPayload.TYPE, PhotoCaptureAbortPayload.CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        PhotoCaptureController.abortCapture(player, payload);
                    }
                });
    }
}

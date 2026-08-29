package com.kuronami.oldglassphotograph;

import com.kuronami.oldglassphotograph.block.DarkroomTableBlock;
import com.kuronami.oldglassphotograph.block.DarkroomTableBlockEntity;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlock;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlockEntity;
import com.kuronami.oldglassphotograph.item.GlassPlateItem;
import com.kuronami.oldglassphotograph.item.PhotographItem;
import java.util.function.Supplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * 登録オブジェクトのローダー非依存ホルダ。
 *
 * <p>各ローダーの entry 初期化時に {@link #wire} へ Supplier を渡す。遅延解決なので、
 * NeoForge のように登録がイベント経由で後から走るローダーでも、
 * 実際に値を読む（＝ゲームプレイで参照する）時点には必ず登録が完了している。
 */
public final class OgpObjects {

    private static Supplier<BlockEntityType<WetPlateCameraBlockEntity>> cameraBlockEntity = missing("camera_block_entity");
    private static Supplier<BlockEntityType<DarkroomTableBlockEntity>> darkroomTableBlockEntity = missing("darkroom_table_block_entity");
    private static Supplier<WetPlateCameraBlock> wetPlateCamera = missing("wet_plate_camera");
    private static Supplier<DarkroomTableBlock> darkroomTable = missing("darkroom_table");
    private static Supplier<GlassPlateItem> glassPlate = missing("glass_plate");
    private static Supplier<PhotographItem> photograph = missing("photograph");
    private static Supplier<Item> collodionKit = missing("collodion_kit");
    private static Supplier<Item> developer = missing("developer");
    private static Supplier<Item> fixer = missing("fixer");

    private OgpObjects() {
    }

    public static void wire(Supplier<BlockEntityType<WetPlateCameraBlockEntity>> cameraBlockEntity,
                            Supplier<BlockEntityType<DarkroomTableBlockEntity>> darkroomTableBlockEntity,
                            Supplier<WetPlateCameraBlock> wetPlateCamera,
                            Supplier<DarkroomTableBlock> darkroomTable,
                            Supplier<GlassPlateItem> glassPlate,
                            Supplier<PhotographItem> photograph,
                            Supplier<Item> collodionKit,
                            Supplier<Item> developer,
                            Supplier<Item> fixer) {
        OgpObjects.cameraBlockEntity = cameraBlockEntity;
        OgpObjects.darkroomTableBlockEntity = darkroomTableBlockEntity;
        OgpObjects.wetPlateCamera = wetPlateCamera;
        OgpObjects.darkroomTable = darkroomTable;
        OgpObjects.glassPlate = glassPlate;
        OgpObjects.photograph = photograph;
        OgpObjects.collodionKit = collodionKit;
        OgpObjects.developer = developer;
        OgpObjects.fixer = fixer;
    }

    public static BlockEntityType<WetPlateCameraBlockEntity> cameraBlockEntity() {
        return cameraBlockEntity.get();
    }

    public static BlockEntityType<DarkroomTableBlockEntity> darkroomTableBlockEntity() {
        return darkroomTableBlockEntity.get();
    }

    public static WetPlateCameraBlock wetPlateCamera() {
        return wetPlateCamera.get();
    }

    public static DarkroomTableBlock darkroomTable() {
        return darkroomTable.get();
    }

    public static GlassPlateItem glassPlate() {
        return glassPlate.get();
    }

    public static PhotographItem photograph() {
        return photograph.get();
    }

    public static Item collodionKit() {
        return collodionKit.get();
    }

    public static Item developer() {
        return developer.get();
    }

    public static Item fixer() {
        return fixer.get();
    }

    private static <T> Supplier<T> missing(String name) {
        return () -> {
            throw new IllegalStateException("OGP object not wired: " + name);
        };
    }
}

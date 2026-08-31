package com.kuronami.oldglassphotograph.component;

import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;

/**
 * data component のローダー非依存ホルダ。遅延解決の規約は {@link com.kuronami.oldglassphotograph.OgpObjects} と同じ。
 */
public final class OgpComponents {

    private static Supplier<DataComponentType<LatentImage>> latentImage = missing("latent_image");
    private static Supplier<DataComponentType<PlateProcess>> plateProcess = missing("plate_process");
    private static Supplier<DataComponentType<Integer>> plateFog = missing("plate_fog");
    private static Supplier<DataComponentType<PhotoCredit>> photoCredit = missing("photo_credit");
    private static Supplier<DataComponentType<PhotoImage>> photoImage = missing("photo_image");

    private OgpComponents() {
    }

    public static void wire(Supplier<DataComponentType<LatentImage>> latentImage,
                            Supplier<DataComponentType<PlateProcess>> plateProcess,
                            Supplier<DataComponentType<Integer>> plateFog,
                            Supplier<DataComponentType<PhotoCredit>> photoCredit,
                            Supplier<DataComponentType<PhotoImage>> photoImage) {
        OgpComponents.latentImage = latentImage;
        OgpComponents.plateProcess = plateProcess;
        OgpComponents.plateFog = plateFog;
        OgpComponents.photoCredit = photoCredit;
        OgpComponents.photoImage = photoImage;
    }

    public static DataComponentType<LatentImage> latentImage() {
        return latentImage.get();
    }

    public static DataComponentType<PlateProcess> plateProcess() {
        return plateProcess.get();
    }

    public static DataComponentType<Integer> plateFog() {
        return plateFog.get();
    }

    public static DataComponentType<PhotoCredit> photoCredit() {
        return photoCredit.get();
    }

    public static DataComponentType<PhotoImage> photoImage() {
        return photoImage.get();
    }

    private static <T> Supplier<T> missing(String name) {
        return () -> {
            throw new IllegalStateException("OGP component not wired: " + name);
        };
    }
}

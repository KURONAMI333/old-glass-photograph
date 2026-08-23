package com.kuronami.oldglassphotograph.gametest;

import java.util.List;
import java.util.function.Consumer;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;

import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * 26.x でのテスト登録。{@link RegisterGameTestsEvent#registerTest} にインスタンスを渡す方式
 * （{@code PLAYBOOK_GAMETEST.md} / mod-066 の {@code mc-26.2} セルと同型）。
 *
 * <p>全件が同じ空の 5x5x5 構造物（{@code old_glass_photograph:empty5x5x5}）を使う。
 * テストを足したらここにも足す。件数が変わったらこの一覧が正本。
 */
public final class OgpGameTestRegistration {

    private static final int MAX_TICKS = 100;
    private static final int SETUP_TICKS = 0;

    private static final Identifier STRUCTURE =
            Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "empty5x5x5");

    private OgpGameTestRegistration() {
    }

    public static void register(RegisterGameTestsEvent event) {
        // vanilla の default 環境と同じ中身 (AllOf(空)) を自分の名前で登録し、その Holder を使う。
        final Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "default"),
                new TestEnvironmentDefinition.AllOf(List.of()));

        // commit 7f03517: 水入り大釜での洗浄リセット
        add(event, environment, "wash_sensitized_plate_resets_to_blank",
                OgpCauldronGameTests::washSensitizedPlateResetsToBlank);
        add(event, environment, "wash_exposed_plate_resets_to_blank",
                OgpCauldronGameTests::washExposedPlateResetsToBlank);
        add(event, environment, "wash_developed_plate_resets_to_blank",
                OgpCauldronGameTests::washDevelopedPlateResetsToBlank);
        add(event, environment, "wash_at_cauldron_level_one_empties_cauldron",
                OgpCauldronGameTests::washAtCauldronLevelOneEmptiesCauldron);

        // commit 3fc8524: 写真の刻印に実世界の日時
        add(event, environment, "developed_photo_has_real_world_captured_at_timestamp",
                OgpPhotoCreditGameTests::developedPhotoHasRealWorldCapturedAtTimestamp);

        // commit 525498e: カメラの脚の当たり判定に隙間が無いこと
        add(event, environment, "lower_trestle_has_no_centre_gap_facing_north",
                OgpCameraShapeGameTests::lowerTrestleHasNoCentreGapFacingNorth);
        add(event, environment, "lower_trestle_has_no_centre_gap_facing_south",
                OgpCameraShapeGameTests::lowerTrestleHasNoCentreGapFacingSouth);
        add(event, environment, "lower_trestle_has_no_centre_gap_facing_east",
                OgpCameraShapeGameTests::lowerTrestleHasNoCentreGapFacingEast);
        add(event, environment, "lower_trestle_has_no_centre_gap_facing_west",
                OgpCameraShapeGameTests::lowerTrestleHasNoCentreGapFacingWest);
    }

    private static void add(RegisterGameTestsEvent event,
                             Holder<TestEnvironmentDefinition<?>> environment,
                             String name,
                             Consumer<GameTestHelper> body) {
        final TestData<Holder<TestEnvironmentDefinition<?>>> data =
                new TestData<>(environment, STRUCTURE, MAX_TICKS, SETUP_TICKS, true);
        event.registerTest(Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, name),
                new OgpTestInstance(name, data, body));
    }
}

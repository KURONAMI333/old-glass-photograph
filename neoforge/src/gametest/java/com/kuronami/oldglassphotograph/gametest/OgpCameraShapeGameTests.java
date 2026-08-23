package com.kuronami.oldglassphotograph.gametest;

import com.kuronami.oldglassphotograph.OgpRegistry;
import com.kuronami.oldglassphotograph.block.WetPlateCameraBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * commit 525498e の検証: カメラ下半分（トレッスル）の当たり判定に、脚と脚の間を抜ける隙間が
 * 無いこと。見た目（逆Vの4本脚）はモデルのまま、判定だけ中身の詰まった台形にした変更
 * （{@code WetPlateCameraBlock} の {@code LOWER_BOXES_NORTH}）を、形状の内包判定で確かめる。
 *
 * <p>修正前は各高さバンドで脚ごとに別の小箱を置いていたため、footprint の中心
 * （x=8/16, z=8/16、脚と脚のちょうど間）にレイが素通りする空洞があった
 * （kura 指摘 2026-08-23「三角形みたいに、中を潰してくれ」）。footprint の中心を
 * 床から天板の直下まで（0〜16 pixel）細かく sample し、途中で判定が抜ける高さが
 * 無いことを確認する。
 *
 * <p>{@link WetPlateCameraBlock#getShape} は {@code protected} なので直接呼ばず、
 * public な {@link BlockState#getShape(net.minecraft.world.level.BlockGetter, BlockPos)} 経由で取る。
 */
public final class OgpCameraShapeGameTests {

    private static final BlockPos POS = new BlockPos(1, 1, 1);

    /** footprint の中心（16 分割 pixel 単位の 8/16 = ローカル座標 0.5）。脚と脚のちょうど間。 */
    private static final double CENTER_LOCAL = 8.0 / 16.0;

    /** 判定の格子点の半径。ゼロ幅の点だと浮動小数の境界で誤検出しうるので、ごく小さな箱にする。 */
    private static final double EPS = 1.0 / 256.0;

    private OgpCameraShapeGameTests() {
    }

    private static boolean contains(VoxelShape shape, double x, double y, double z) {
        VoxelShape point = Shapes.create(new AABB(x - EPS, y - EPS, z - EPS, x + EPS, y + EPS, z + EPS));
        return Shapes.joinIsNotEmpty(shape, point, BooleanOp.AND);
    }

    private static void checkFacing(GameTestHelper helper, Direction facing) {
        BlockState state = OgpRegistry.WET_PLATE_CAMERA.get().defaultBlockState()
                .setValue(WetPlateCameraBlock.FACING, facing)
                .setValue(WetPlateCameraBlock.HALF, DoubleBlockHalf.LOWER);
        helper.setBlock(POS, state);

        BlockPos absPos = helper.absolutePos(POS);
        VoxelShape shape = helper.getBlockState(POS).getShape(helper.getLevel(), absPos);

        // 床(0)から天板の直下(16 pixel = ローカル1.0)まで、0.5 pixel刻みで footprint 中心を確認する。
        // トレッスルの4バンド(0-13 pixel)は台形の箱、13-16 pixel は中央の柱がそれぞれ中心を覆う。
        for (int step = 1; step <= 31; step++) {
            double yLocal = step * 0.5 / 16.0;
            if (!contains(shape, CENTER_LOCAL, yLocal, CENTER_LOCAL)) {
                helper.fail(Component.literal("facing=" + facing + ": gap at footprint centre, y="
                        + (step * 0.5) + "/16 (local " + yLocal + ") — a ray straight through the middle "
                        + "of the legs would pass through uninterrupted"), POS);
                return;
            }
        }
    }

    public static void lowerTrestleHasNoCentreGapFacingNorth(GameTestHelper helper) {
        checkFacing(helper, Direction.NORTH);
        helper.succeed();
    }

    public static void lowerTrestleHasNoCentreGapFacingSouth(GameTestHelper helper) {
        checkFacing(helper, Direction.SOUTH);
        helper.succeed();
    }

    public static void lowerTrestleHasNoCentreGapFacingEast(GameTestHelper helper) {
        checkFacing(helper, Direction.EAST);
        helper.succeed();
    }

    public static void lowerTrestleHasNoCentreGapFacingWest(GameTestHelper helper) {
        checkFacing(helper, Direction.WEST);
        helper.succeed();
    }
}

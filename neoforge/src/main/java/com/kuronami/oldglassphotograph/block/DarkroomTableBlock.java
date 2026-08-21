package com.kuronami.oldglassphotograph.block;

import com.kuronami.oldglassphotograph.OgpRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Darkroom Table。<b>場所の条件であって画面ではない</b>ので GUI を持たない。
 *
 * <p>湿板は塗布・銀浴・現像を暗所でやる必要があり、野外の写真家は暗室ワゴンや携帯テントを
 * 引いて歩いた（{@code MODJAM_AO_camera.md} §3-3 / Smithsonian・Library of Congress）。
 * その制約を「この台の上が暗いときだけ工程が進む」の 1 つに畳んでいる。
 *
 * <p>gate するのは<b>板の準備（洗浄・コロジオン・銀浴）と現像</b>の 2 つ
 * （{@code MODJAM_DECISIONS_OGP.md} §10）。定着は史実でも暗室を出てから行えるので gate しない。
 */
public class DarkroomTableBlock extends Block {

    /**
     * 工程が進む最大の明るさ。台に光が届く面（{@link #lightReaching}）の最大値と比べる。
     *
     * <p>7 = 松明が 7 マス以上離れている状態。真っ暗（0）を要求すると player が自分の作業台を
     * 見られなくなるので、当時の safelight に相当する薄暗さを許す位置に置いた。
     */
    public static final int MAX_LIGHT = 7;

    /** {@code finishUsingItem} の再検査で台を探す範囲（player の届く距離）。 */
    private static final int SEARCH_RADIUS = 4;

    private static final int SEARCH_HEIGHT = 2;

    public DarkroomTableBlock(Properties properties) {
        super(properties);
    }

    /**
     * 台に届いている明るさ。<b>真上だけを見てはいけない。</b>
     *
     * <p>ブロックの内側は常に光量 0 なので、真上だけを読むと
     * <b>真昼の屋外でも台の上に丸石を 1 個置けば「暗室」になる</b>。
     * 光の通る面（上・東西南北のうち不透過でないマス）を全部見て、その最大を採る。
     * 全面が塞がっている台はそもそも右クリックできないので、その時は 0 を返す。
     */
    public static int lightReaching(LevelReader level, BlockPos pos) {
        int max = 0;
        for (Direction dir : OPEN_FACES) {
            BlockPos side = pos.relative(dir);
            if (level.getBlockState(side).isSolidRender()) {
                continue;
            }
            max = Math.max(max, level.getMaxLocalRawBrightness(side));
        }
        return max;
    }

    private static final Direction[] OPEN_FACES = {
            Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    public static boolean isDarkroomTable(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).is(OgpRegistry.DARKROOM_TABLE.get());
    }

    public static boolean isUsable(LevelReader level, BlockPos pos) {
        return lightReaching(level, pos) <= MAX_LIGHT;
    }

    /**
     * player の周りで使える（＝暗い）Darkroom Table を探す。
     *
     * <p>長押しの完了時（{@code finishUsingItem}）は clicked pos を持っていないので、
     * ここで<b>もう一度</b>条件を見る。押し始めてから歩き去った・誰かが明かりを置いた場合に
     * gate が素通りしないようにするための再検査。
     */
    public static @Nullable BlockPos findUsable(Level level, Player player) {
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -SEARCH_HEIGHT; dy <= SEARCH_HEIGHT; dy++) {
            for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (isDarkroomTable(level, cursor) && isUsable(level, cursor)) {
                        return cursor.immutable();
                    }
                }
            }
        }
        return null;
    }

    /**
     * player の周りにある Darkroom Table のうち、最も暗いものに届いている明るさ。
     * 「近くに台はあるが明るすぎる」を言い分けるために使う。台が無ければ -1。
     */
    public static int bestLightNearby(Level level, Player player) {
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int best = -1;
        for (int dy = -SEARCH_HEIGHT; dy <= SEARCH_HEIGHT; dy++) {
            for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!isDarkroomTable(level, cursor)) {
                        continue;
                    }
                    int light = lightReaching(level, cursor);
                    if (best < 0 || light < best) {
                        best = light;
                    }
                }
            }
        }
        return best;
    }
}

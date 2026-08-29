package com.kuronami.oldglassphotograph.item;

import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * 手に持って進めている工程の進み具合を、<b>アイテム欄のバーを描くためだけに</b>置いておく場所。
 *
 * <p>{@code Item#isBarVisible} は {@link ItemStack} しか受け取らないので、
 * 「その板をいま誰が何 tick 使っているか」をそこから知る術が無い。data component に書けば
 * 型の上では素直だが、板は潜像に 16KB の byte[] を載せているので、
 * <b>1 tick ごとに書き換えると 6 秒の定着で 1MB 近い同期が飛ぶ</b>。それはしない。
 *
 * <p>そこで client が毎 tick ここへ「いま使っている stack と進み具合」を置き、
 * {@link GlassPlateItem} は描く時にだけ読む。書くのは
 * {@code OgpClient#onClientTick} 1 箇所だけで、使っていなければ毎 tick 消える。
 * server では誰も書かないので {@link #of} は常に -1 を返す（バーは出ない）。
 *
 * <p>照合は <b>参照の同一性</b>で行う。値の等価で見ると、同じ段の板を複数持っている時に
 * 全部のバーが光る。
 */
public final class PlateUseProgress {

    private static @Nullable ItemStack stack;
    private static float progress;

    private PlateUseProgress() {
    }

    /**
     * @param used     いま使っている stack（インベントリに入っている実体そのもの）
     * @param fraction 0.0（始めたばかり）〜 1.0（満ちた）
     */
    public static void set(ItemStack used, float fraction) {
        stack = used;
        progress = fraction;
    }

    public static void clear() {
        stack = null;
        progress = 0.0F;
    }

    /** @return 進み具合 0.0〜1.0。この stack を使っていなければ -1。 */
    public static float of(ItemStack candidate) {
        return stack == candidate ? progress : -1.0F;
    }
}

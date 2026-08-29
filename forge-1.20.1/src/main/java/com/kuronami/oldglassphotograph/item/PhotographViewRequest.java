package com.kuronami.oldglassphotograph.item;

import net.minecraft.world.InteractionHand;
import org.jetbrains.annotations.Nullable;

/**
 * 写真をじっくり見る面を開く合図を、client 側の実装へ渡すだけの place holder。
 *
 * <p>見る面は<b>描画層 1 枚で完結していて server は何も知らない</b>（歩ける画面なので
 * {@code Screen} を作らず、開閉も持ち物の照合も client の中で閉じる）。
 * ところが開く合図は {@code PhotographItem#use} から出る必要があり、そこは両側で読まれる。
 *
 * <p>そこで {@link PlateUseProgress} と同じ形にする。client の入口が起動時に
 * {@link #setOpener} で自分を差し、server では誰も差さないので {@link #toggle} は必ず false を返す。
 * <b>{@link PhotographItem} から client のクラスを名指ししない</b>ので、
 * dedicated server がその参照を解決しに行く経路が存在しない。
 */
public final class PhotographViewRequest {

    /** client 側の実装。 */
    @FunctionalInterface
    public interface Opener {

        /** @return 実際に開いた（または閉じた）なら true。開けない状況なら false */
        boolean toggle(InteractionHand hand);
    }

    private static @Nullable Opener opener;

    private PhotographViewRequest() {
    }

    public static void setOpener(Opener client) {
        opener = client;
    }

    public static boolean toggle(InteractionHand hand) {
        Opener current = opener;
        return current != null && current.toggle(hand);
    }
}

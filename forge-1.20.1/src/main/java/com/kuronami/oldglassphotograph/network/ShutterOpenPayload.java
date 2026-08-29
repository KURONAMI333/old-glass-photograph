package com.kuronami.oldglassphotograph.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * server -> client。シャッターが開いた。ここから光が溜まりはじめる。
 *
 * <p>窓は {@code window} tick で自動的に閉じる（目標に達する＝§14）。
 * player がその前にもう一度クリックすればそこで閉じる＝露光不足で、これは意図した選択。
 *
 * @param window   露光窓の長さ tick。この明るさで目標へ届くまでの時間（上限で頭打ち）
 * @param interval 撮影の間隔 tick。窓 / interval が平均に使う枚数になる
 */
public record ShutterOpenPayload(int token, int window, int interval) {

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(token);
        buf.writeVarInt(window);
        buf.writeVarInt(interval);
    }

    public static ShutterOpenPayload read(FriendlyByteBuf buf) {
        return new ShutterOpenPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }
}

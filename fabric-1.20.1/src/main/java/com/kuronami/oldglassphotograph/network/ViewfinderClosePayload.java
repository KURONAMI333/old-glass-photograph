package com.kuronami.oldglassphotograph.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * server -> client。ファインダーから出す。
 *
 * <p>シャッターを開けられない理由が出た時に使う。<b>視点を戻してから理由を言う</b>形にしてあるのは、
 * 覗いている間は HUD が隠れていて actionbar が見えないため。
 */
public record ViewfinderClosePayload() {

    public static final ViewfinderClosePayload INSTANCE = new ViewfinderClosePayload();

    public void write(FriendlyByteBuf buf) {
        // 中身は無い。
    }

    public static ViewfinderClosePayload read(FriendlyByteBuf buf) {
        return INSTANCE;
    }
}

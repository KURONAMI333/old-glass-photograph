package com.kuronami.oldglassphotograph.network;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * server -> client。ファインダーから出す。
 *
 * <p>シャッターを開けられない理由が出た時に使う。<b>視点を戻してから理由を言う</b>形にしてあるのは、
 * 覗いている間は HUD が隠れていて actionbar が見えないため。
 */
public record ViewfinderClosePayload() implements CustomPacketPayload {

    public static final ViewfinderClosePayload INSTANCE = new ViewfinderClosePayload();

    public static final CustomPacketPayload.Type<ViewfinderClosePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(OldGlassPhotograph.MODID, "viewfinder_close"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ViewfinderClosePayload> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

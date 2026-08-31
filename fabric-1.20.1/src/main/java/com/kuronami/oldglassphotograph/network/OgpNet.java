package com.kuronami.oldglassphotograph.network;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * raw ByteBuf networking 縺ｮ騾∽ｿ｡蜿｣縲・.20.1 縺ｫ縺ｯ {@code CustomPacketPayload} /
 * {@code PayloadRegistrar} 縺悟ｭ伜惠縺励↑縺・ｼ・OADERS.md 豁｣譛ｬ繝ｻmod-048 蜷ｦ螳壹さ繝｡繝ｳ繝亥ｮ滓ｸｬ・峨◆繧√・ * 繝√Ε繝ｳ繝阪Ν縺斐→縺ｮ {@link ResourceLocation} + {@link FriendlyByteBuf} 縺ｧ邨・・縲・ *
 * <p>繝舌ャ繝輔ぃ縺ｮ逕滓・縺ｯ縺薙％縺ｧ陦後＞縲√Ο繝ｼ繝繝ｼ蝗ｺ譛峨・騾∽ｿ｡縺ｯ {@link Sink} 縺ｸ蟾ｮ縺励◆螳溯｣・↓蟋斐・繧・ * ・・ntry 蛻晄悄蛹匁凾縺ｫ wire縲ょｷｮ縺吝燕縺ｮ騾∽ｿ｡縺ｯ謠｡繧頑ｽｰ縺呻ｼ昴ご繝ｼ繝繝励Ξ繧､縺ｧ縺ｯ蛻ｰ驕斐＠縺ｪ縺・ｵ瑚ｷｯ・峨・ * 蜿嶺ｿ｡蛛ｴ縺ｮ registerGlobalReceiver 縺ｯ繝ｭ繝ｼ繝繝ｼ螻､・・ntry 繧ｯ繝ｩ繧ｹ・峨′謖√▽縲・ */
public final class OgpNet {

    /** server -> client縲ゅき繝｡繝ｩ繧定ｦ励￥縲・*/
    public static final ResourceLocation CHANNEL_VIEWFINDER_OPEN =
            new ResourceLocation(OldGlassPhotograph.MODID, "viewfinder_open");

    /** server -> client縲ゅす繝｣繝・ち繝ｼ縺碁幕縺・◆縲・*/
    public static final ResourceLocation CHANNEL_SHUTTER_OPEN =
            new ResourceLocation(OldGlassPhotograph.MODID, "shutter_open");

    /** server -> client縲ゅヵ繧｡繧､繝ｳ繝繝ｼ縺九ｉ蜃ｺ縺吶・*/
    public static final ResourceLocation CHANNEL_VIEWFINDER_CLOSE =
            new ResourceLocation(OldGlassPhotograph.MODID, "viewfinder_close");

    /** client -> server縲ゅす繝｣繝・ち繝ｼ繧帝幕縺代◆縺・・*/
    public static final ResourceLocation CHANNEL_SHUTTER_REQUEST =
            new ResourceLocation(OldGlassPhotograph.MODID, "shutter_request");

    /** client -> server縲る愆蜈峨・蟷ｳ蝮・ワ繧定ｿ斐☆縲・*/
    public static final ResourceLocation CHANNEL_PHOTO_MAP_PIXELS =
            new ResourceLocation(OldGlassPhotograph.MODID, "photo_map_pixels");

    /** client -> server縲る愆蜈峨′蜀咏悄縺ｫ縺ｪ繧峨★縺ｫ邨ゅｏ縺｣縺溘・*/
    public static final ResourceLocation CHANNEL_CAPTURE_ABORT =
            new ResourceLocation(OldGlassPhotograph.MODID, "photo_capture_abort");

    private OgpNet() {
    }

    /** 繝ｭ繝ｼ繝繝ｼ蝗ｺ譛峨・騾∽ｿ｡縲・abric: ServerPlayNetworking / ClientPlayNetworking縲・*/
    public interface Sink {
        void sendToPlayer(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf);

        void sendToServer(ResourceLocation channel, FriendlyByteBuf buf);
    }

    /** server -&gt; player の送信口。SERVER_STOPPING で外す。 */
    private static volatile @Nullable Sink serverSink;

    /** client -&gt; server の送信口。 */
    private static volatile @Nullable Sink clientSink;

    /**
     * 向きごとに別々に持つ。1 本にすると、単一プレイでは client entry の初期化が
     * server entry の後に走るぶん server -&gt; player の口が上書きされて消える
     * （26.x の Fabric セルも setSendToPlayer / setSendToServer で分けている）。
     */
    public static void wireServer(@Nullable Sink impl) {
        serverSink = impl;
    }

    public static void wireClient(@Nullable Sink impl) {
        clientSink = impl;
    }

    // ------------------------------------------------------------ server -> client

    public static void sendToPlayer(ServerPlayer player, ViewfinderOpenPayload message) {
        dispatch(player, CHANNEL_VIEWFINDER_OPEN, message::write);
    }

    public static void sendToPlayer(ServerPlayer player, ShutterOpenPayload message) {
        dispatch(player, CHANNEL_SHUTTER_OPEN, message::write);
    }

    public static void sendToPlayer(ServerPlayer player, ViewfinderClosePayload message) {
        dispatch(player, CHANNEL_VIEWFINDER_CLOSE, message::write);
    }

    // ------------------------------------------------------------ client -> server

    public static void sendToServer(ShutterRequestPayload message) {
        dispatch(null, CHANNEL_SHUTTER_REQUEST, message::write);
    }

    public static void sendToServer(PhotoMapPixelsPayload message) {
        dispatch(null, CHANNEL_PHOTO_MAP_PIXELS, message::write);
    }

    public static void sendToServer(PhotoCaptureAbortPayload message) {
        dispatch(null, CHANNEL_CAPTURE_ABORT, message::write);
    }

    /**
     * 繝舌ャ繝輔ぃ繧剃ｽ懊▲縺ｦ sink 縺ｸ貂｡縺吶Ｔink 縺檎┌縺・俣縺ｮ騾∽ｿ｡縺ｯ謠｡繧頑ｽｰ縺吶・     * ・・6.x / 1.21.1 縺ｮ {@code PacketDistributor::sendToPlayer} 蟾ｮ縺玲崛縺井ｽ咲ｽｮ縺ｨ蜷後§縲ゑｼ・     */
    private static void dispatch(@Nullable ServerPlayer player, ResourceLocation channel,
                                 Consumer<FriendlyByteBuf> writer) {
        Sink current = player != null ? serverSink : clientSink;
        if (current == null) {
            return;
        }
        // 解放しない。パケットがこのバッファを持ったまま後で書き出すので、
        // 送信直後に release すると中身が消える（Fabric の送信は buf を渡し切る形）。
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        writer.accept(buf);
        if (player != null) {
            current.sendToPlayer(player, channel, buf);
        } else {
            current.sendToServer(channel, buf);
        }
    }
}

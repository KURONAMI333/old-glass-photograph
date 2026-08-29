package com.kuronami.oldglassphotograph.network;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * server -> client。シャッターが開いた。ここから光が溜まりはじめる。
 *
 * <p>窓は {@code window} tick で自動的に閉じる（目標に達する＝§14）。
 * player がその前にもう一度クリックすればそこで閉じる＝露光不足で、これは意図した選択。
 *
 * @param window   露光窓の長さ tick。この明るさで目標へ届くまでの時間（上限で頭打ち）
 * @param interval 撮影の間隔 tick。窓 / interval が平均に使う枚数になる
 */
public record ShutterOpenPayload(int token, int window, int interval) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShutterOpenPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(OldGlassPhotograph.MODID, "shutter_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShutterOpenPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        ByteBufCodecs.VAR_INT.encode(buf, p.token());
                        ByteBufCodecs.VAR_INT.encode(buf, p.window());
                        ByteBufCodecs.VAR_INT.encode(buf, p.interval());
                    },
                    buf -> new ShutterOpenPayload(
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

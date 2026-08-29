package com.kuronami.oldglassphotograph.network;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * payload 送信のローダー非依存フック。NeoForge の PacketDistributor /
 * Fabric の ServerPlayNetworking・ClientPlayNetworking の差を吸収する。
 *
 * <p>各ローダーの entry 初期化時に実装を渡す。server 側の送信は server entry で、
 * client からの送信は client entry で wire する（dedicated server では client 経路は使われない）。
 * 実装を渡す前の送信は握り潰す（ゲームプレイでは到達しない経路）。
 */
public final class OgpNet {

    private static BiConsumer<ServerPlayer, CustomPacketPayload> sendToPlayer = (player, payload) -> {
    };
    private static Consumer<CustomPacketPayload> sendToServer = payload -> {
    };

    private OgpNet() {
    }

    /** server → client（その player 専用）。NeoForge: PacketDistributor::sendToPlayer / Fabric: ServerPlayNetworking::send */
    public static void setSendToPlayer(BiConsumer<ServerPlayer, CustomPacketPayload> impl) {
        sendToPlayer = impl;
    }

    /** client → server。NeoForge: ClientPacketDistributor::sendToServer / Fabric: ClientPlayNetworking::send */
    public static void setSendToServer(Consumer<CustomPacketPayload> impl) {
        sendToServer = impl;
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        sendToPlayer.accept(player, payload);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        sendToServer.accept(payload);
    }
}

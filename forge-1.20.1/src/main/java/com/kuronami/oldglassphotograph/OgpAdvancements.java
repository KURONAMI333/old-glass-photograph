package com.kuronami.oldglassphotograph;

import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 遊びの節目の進捗を、起きた場所から直接与える。
 *
 * <p><b>CriterionTrigger は自作しない。</b> 与える側の進捗は criterion に vanilla の
 * {@code minecraft:impossible} を持たせてあり、それ単体では絶対に発火しない。
 * ここから {@code PlayerAdvancements#award} を呼んだ時だけ立つ
 * （vanilla の {@code recipes/root.json} と同じ trigger の使い方）。
 *
 * <p>この形にした理由は、対象の節目が<b>データパックの述語では判定できない</b>ため。
 * 露光の充足度・かぶりの tick・撮影地点の明るさはどれも完成の瞬間にしか揃わない量で、
 * 判定できるもの（素材の所持・板を箱やカメラへ入れた・写真を手に入れた）は
 * すべて JSON 側の vanilla トリガで組んであり、ここへは来ない。
 *
 * <p>criterion 名は進捗の path と同じにしてある（{@code advancement/<path>.json} の criteria キー）。
 */
public final class OgpAdvancements {

    /** 写真を仕上げた時、露光が足りていなかった。 */
    public static final String UNDEREXPOSED = "underexposed";
    /** 写真を仕上げた時、暗い光の中で露光を満たしきっていた。 */
    public static final String LONG_EXPOSURE = "long_exposure";
    /** 写真を仕上げた時、暗室台の蓋が開いていてかぶっていた。 */
    public static final String LIGHT_GOT_IN = "light_got_in";
    /** 仕上がった写真をじっくり見た。 */
    public static final String A_CLOSER_LOOK = "a_closer_look";
    /** 使う前にコロジオンが乾き、板が素のガラスへ戻った。 */
    public static final String DRY_PLATE = "dry_plate";

    private OgpAdvancements() {
    }

    /**
     * 進捗を1つ与える。既に持っていれば何も起きない（{@code award} が false を返すだけ）。
     *
     * <p>データパックで進捗を消された server でも落ちないよう、引けなかったら黙って何もしない。
     *
     * @param path {@code data/old_glass_photograph/advancements/} 配下のファイル名（拡張子なし）
     */
    public static void award(ServerPlayer player, String path) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        // 1.20.1 の進捗は AdvancementHolder（1.20.2+）ではなく Advancement を直接引く。
        Advancement holder = server.getAdvancements().getAdvancement(
                new ResourceLocation(OldGlassPhotograph.MODID, path));
        if (holder != null) {
            player.getAdvancements().award(holder, path);
        }
    }
}

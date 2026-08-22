package com.kuronami.oldglassphotograph.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 誰が撮って、いつ撮ったか。<b>この component が付いていない写真も普通に存在する</b>
 * （これが入る前に現像された写真）。読む側は必ず「無い」を通す。
 *
 * <p>Dr.STONE の写真回で写真が意味を持つのは、撮った人が誰か分かっているからで、
 * 撮影者が匿名なら 99 話の写真日記は成立しない
 * （{@code _research/REF_DRSTONE_DAGUERREOTYPE.md} §4 / §5 優先1）。
 *
 * <h2>なぜ {@code getGameTime()} から日を作るか</h2>
 *
 * 26.2 は時刻を {@code WorldClock} / {@code Timeline} へ組み替えており、
 * {@code /time query} が返すのは「その時計の累計 tick」か {@code gametime} で、
 * <b>日を返す問い合わせは無い</b>（{@code MC: net/minecraft/server/commands/TimeCommand.java:130-140}）。
 *
 * <p>そのうえ 1 日の時刻は {@code /time set} で前へも後ろへも動く。時刻側から日を作ると、
 * <b>後から撮った写真の方が古い日付になる</b>ことがある。{@code getGameTime()} は
 * 世界が動いた tick の総数で戻らないので、写真を撮った順と日付の順が必ず一致する。
 * 見せているのは「その世界が始まってから何日目に撮ったか」であって、暦上の日付ではない。
 *
 * @param author 撮影者の名前
 * @param day    世界が始まってからの日（1 日目 = 1）
 */
public record PhotoCredit(String author, long day) {

    /** 名前の上限。書き込み側は player 名なので届かないが、壊れた保存データを弾く。 */
    public static final int MAX_AUTHOR_LENGTH = 64;

    public static final Codec<PhotoCredit> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.string(0, MAX_AUTHOR_LENGTH).fieldOf("author").forGetter(PhotoCredit::author),
            Codec.LONG.optionalFieldOf("day", 1L).forGetter(PhotoCredit::day)
    ).apply(instance, PhotoCredit::new));

    public static final StreamCodec<ByteBuf, PhotoCredit> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_AUTHOR_LENGTH), PhotoCredit::author,
            ByteBufCodecs.VAR_LONG, PhotoCredit::day,
            PhotoCredit::new);

    /** game time から「何日目」を作る。1 日 = 24000 tick。 */
    public static long dayOf(long gameTime) {
        return Math.max(0L, gameTime) / 24000L + 1L;
    }
}

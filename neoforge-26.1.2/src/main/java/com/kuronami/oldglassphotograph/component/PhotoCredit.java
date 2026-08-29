package com.kuronami.oldglassphotograph.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
 * <h2>なぜ実世界の日時も持たせるか</h2>
 *
 * {@code day}（世界が始まってから何日目か）はゲーム内の物差しで、現実の撮影タイミングを示さない。
 * kura の判定でゲーム内時刻でなく実世界の日時を足すことになった。
 *
 * <h2>タイムゾーンの扱い</h2>
 *
 * {@link #captureTimestamp()} は {@link java.time.ZoneId#systemDefault()}（＝この
 * {@code develop()} を実行している機械のローカル時刻）で文字列化する。<b>専用サーバーでは
 * サーバー機のローカル時刻になり、プレイヤー自身の時計とは一致しない</b>。書き込みは
 * server 側でしか起きない（{@code develop()} が server 権威で1回だけ書く）ので、この
 * 非一致は再現性の代償として承知の上の仕様であり、バグではない。クライアント側で
 * 書き直す形にすると、任意のタイムスタンプを詐称できてしまい server 権威が崩れる。
 *
 * @param author     撮影者の名前
 * @param day        世界が始まってからの日（1 日目 = 1）
 * @param capturedAt 現像した瞬間の実世界の日時（{@link #captureTimestamp()} の書式）。
 *                   これより前に現像された写真は持たないので空文字列で、読む側は
 *                   空文字列を「無い」として扱う
 */
public record PhotoCredit(String author, long day, String capturedAt) {

    /** 名前の上限。書き込み側は player 名なので届かないが、壊れた保存データを弾く。 */
    public static final int MAX_AUTHOR_LENGTH = 64;

    /** 日時文字列の上限。{@link #TIMESTAMP_FORMAT} の出力は 16 文字だが余裕を持たせる。 */
    public static final int MAX_CAPTURED_AT_LENGTH = 32;

    /** {@code captureTimestamp()} が使う書式。例: {@code 2026-08-23 14:32}。 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static final Codec<PhotoCredit> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.string(0, MAX_AUTHOR_LENGTH).fieldOf("author").forGetter(PhotoCredit::author),
            Codec.LONG.optionalFieldOf("day", 1L).forGetter(PhotoCredit::day),
            Codec.string(0, MAX_CAPTURED_AT_LENGTH).optionalFieldOf("captured_at", "").forGetter(PhotoCredit::capturedAt)
    ).apply(instance, PhotoCredit::new));

    public static final StreamCodec<ByteBuf, PhotoCredit> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_AUTHOR_LENGTH), PhotoCredit::author,
            ByteBufCodecs.VAR_LONG, PhotoCredit::day,
            ByteBufCodecs.stringUtf8(MAX_CAPTURED_AT_LENGTH), PhotoCredit::capturedAt,
            PhotoCredit::new);

    /** game time から「何日目」を作る。1 日 = 24000 tick。 */
    public static long dayOf(long gameTime) {
        return Math.max(0L, gameTime) / 24000L + 1L;
    }

    /**
     * 現像している機械のローカル時刻を {@link #TIMESTAMP_FORMAT} で文字列化する。
     * タイムゾーンの扱いはクラス javadoc を参照。
     */
    public static String captureTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMAT);
    }
}

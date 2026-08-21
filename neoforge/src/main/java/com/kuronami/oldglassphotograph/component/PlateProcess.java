package com.kuronami.oldglassphotograph.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * Glass Plate の工程状態。<b>component が付いていない = 素のきれいなガラス板</b>。
 *
 * <p>工程は 3 段（準備 / 露光 / 現像 2 操作）へ短縮してある。7 段階の
 * Wash / Coat / Sensitize は {@link Stage#SENSITIZED} 1 つへ畳んだ
 * （{@code MODJAM_DECISIONS_OGP.md} §2「7段階から短縮する」）。
 *
 * @param wetUntil    乾くまでの絶対 game time tick。0 以下 = 期限なし（現像後）
 * @param secondsLeft 表示用の残り秒。server の {@code inventoryTick} が 1 秒ごとに書き直す。
 *                    client は game time を tooltip 生成時に取れないので、値そのものを同期する
 */
public record PlateProcess(Stage stage, long wetUntil, int secondsLeft) {

    public static final Codec<PlateProcess> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Stage.CODEC.fieldOf("stage").forGetter(PlateProcess::stage),
            Codec.LONG.optionalFieldOf("wet_until", 0L).forGetter(PlateProcess::wetUntil),
            Codec.INT.optionalFieldOf("seconds_left", 0).forGetter(PlateProcess::secondsLeft)
    ).apply(instance, PlateProcess::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlateProcess> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeByte(value.stage().ordinal());
                buf.writeLong(value.wetUntil());
                ByteBufCodecs.VAR_INT.encode(buf, value.secondsLeft());
            },
            buf -> new PlateProcess(
                    Stage.values()[buf.readByte()],
                    buf.readLong(),
                    ByteBufCodecs.VAR_INT.decode(buf)));

    /** 期限を持つ（＝湿っている）段か。 */
    public boolean isWet() {
        return wetUntil > 0L;
    }

    /** その game time の時点で乾いているか。 */
    public boolean isDriedAt(long gameTime) {
        return isWet() && gameTime >= wetUntil;
    }

    public PlateProcess withSecondsLeft(int seconds) {
        return new PlateProcess(stage, wetUntil, seconds);
    }

    public enum Stage implements StringRepresentable {
        /** コロジオンを塗って銀浴を通した状態。ここから 60 秒で乾く。 */
        SENSITIZED("sensitized"),
        /** カメラで露光した。潜像を持つ。まだ湿っていて期限が進む。 */
        EXPOSED("exposed"),
        /** 現像液を通した。像は確定し、期限は止まる。あとは定着だけ。 */
        DEVELOPED("developed");

        public static final Codec<Stage> CODEC = StringRepresentable.fromEnum(Stage::values);

        private final String name;

        Stage(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}

package com.kuronami.oldglassphotograph.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

/**
 * Glass Plate の工程状態。<b>component が付いていない = 素のきれいなガラス板</b>
 * （この帯では {@link OgpNbt} のタグが無いことが同じ意味）。
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

    /** NBT へ書く。{@code OgpNbt} からだけ呼ばれる。 */
    public CompoundTag save(CompoundTag tag) {
        tag.putString("stage", stage.getSerializedName());
        tag.putLong("wet_until", wetUntil);
        tag.putInt("seconds_left", secondsLeft);
        return tag;
    }

    /** NBT から読む。知らない stage 名は null（工程なし扱い）。{@code OgpNbt} からだけ呼ばれる。 */
    public static @Nullable PlateProcess load(CompoundTag tag) {
        Stage stage = Stage.byName(tag.getString("stage"));
        if (stage == null) {
            return null;
        }
        return new PlateProcess(
                stage,
                tag.contains("wet_until", Tag.TAG_ANY_NUMERIC) ? tag.getLong("wet_until") : 0L,
                tag.contains("seconds_left", Tag.TAG_ANY_NUMERIC) ? tag.getInt("seconds_left") : 0);
    }

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

        private final String name;

        Stage(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        public static @Nullable Stage byName(String name) {
            for (Stage value : values()) {
                if (value.getSerializedName().equals(name)) {
                    return value;
                }
            }
            return null;
        }
    }
}

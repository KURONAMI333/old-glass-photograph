package com.kuronami.oldglassphotograph.capture;

import net.minecraft.network.chat.Component;

/**
 * ファインダーを覗いている間に出す 1 行。<b>数値を出さない</b>
 * （{@code MODJAM_DECISIONS_OGP.md} §15。生の光量を player に読ませない）。
 *
 * <p>光の読みと、撮れない理由を 1 つの列挙で持つ。server が判定して ordinal だけを送り、
 * client は受けた ordinal から翻訳キーを引いて描く。文面は lang ファイルが持つ。
 *
 * <p><b>撮れない理由には必ず「次に何をすればいいか」を付ける</b>（実機検証の
 * 「撮影の仕方がよくわかんない」への導線。GUI は作らないので、この 1 行が唯一の教え口）。
 */
public enum ViewfinderReading {

    /** 屋外の昼。目標まですぐ溜まる。 */
    BRIGHT("viewfinder.old_glass_photograph.bright", true),
    /** 夕方・木陰・松明のそば。少し長くかかる。 */
    SOFT("viewfinder.old_glass_photograph.soft", true),
    /** 屋内の隅・夜明け前。かなり長くかかる。 */
    DIM("viewfinder.old_glass_photograph.dim", true),
    /** 上限まで開けても目標に届かない。撮れるが露光不足になる。 */
    TOO_DARK("viewfinder.old_glass_photograph.too_dark", true),

    NO_PLATE("viewfinder.old_glass_photograph.no_plate", false),
    ALREADY_EXPOSED("viewfinder.old_glass_photograph.already_exposed", false),
    NOT_SENSITIZED("viewfinder.old_glass_photograph.not_sensitized", false),
    DRIED("viewfinder.old_glass_photograph.dried", false),
    /** 露光を終えるまでに板が乾く。シャッターを開けない（板は消費しないので安全側）。 */
    WOULD_DRY("viewfinder.old_glass_photograph.would_dry", false);

    /** 撮れる状態の 1 行を包んで、2 回目の click を教える。 */
    private static final String SHOOT_HINT_KEY = "viewfinder.old_glass_photograph.shoot_hint";

    private final String key;
    private final boolean canShoot;

    ViewfinderReading(String key, boolean canShoot) {
        this.key = key;
        this.canShoot = canShoot;
    }

    /** シャッターを開けられる状態か。 */
    public boolean canShoot() {
        return canShoot;
    }

    /** 覗いている間に描く 1 行。撮れる状態なら操作も添える（これが無いと 2 回目の click に気づけない）。 */
    public Component line() {
        return canShoot
                ? Component.translatable(SHOOT_HINT_KEY, Component.translatable(key))
                : Component.translatable(key);
    }

    /** 撮れなかった時に actionbar へ出す 1 行（撮れない理由には次の一手が含まれている）。 */
    public Component reason() {
        return Component.translatable(key);
    }

    public static ViewfinderReading fromOrdinal(int ordinal) {
        ViewfinderReading[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : NO_PLATE;
    }
}

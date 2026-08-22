package com.kuronami.oldglassphotograph.capture;

/**
 * ファインダーを覗いている間に出す 1 行。<b>数値を出さない</b>
 * （{@code MODJAM_DECISIONS_OGP.md} §15。生の光量を player に読ませない）。
 *
 * <p>光の読みと、撮れない理由を 1 つの列挙で持つ。server が判定して ordinal だけを送り、
 * 文面は client が持つ（MOD は英語のみ＝§2 なので、文面を server 側で組む理由が無い）。
 */
public enum ViewfinderReading {

    /** 屋外の昼。目標まですぐ溜まる。 */
    BRIGHT("Bright light. The plate takes quickly.", true),
    /** 夕方・木陰・松明のそば。少し長くかかる。 */
    SOFT("Soft light. The exposure will run longer.", true),
    /** 屋内の隅・夜明け前。かなり長くかかる。 */
    DIM("Dim light. The exposure will run a long time.", true),
    /** 上限まで開けても目標に届かない。撮れるが露光不足になる。 */
    TOO_DARK("Too dark. The plate will not take a full image here.", true),

    NO_PLATE("No plate loaded.", false),
    ALREADY_EXPOSED("This plate already holds a latent image. Take it out and develop it.", false),
    NOT_SENSITIZED("This plate is not sensitized. Coat it with a Collodion Kit first.", false),
    DRIED("The collodion dried out. The plate is clean again.", false),
    /** 露光を終えるまでに板が乾く。シャッターを開けない（板は消費しないので安全側）。 */
    WOULD_DRY("The plate would dry out before the exposure finishes.", false);

    private final String text;
    private final boolean canShoot;

    ViewfinderReading(String text, boolean canShoot) {
        this.text = text;
        this.canShoot = canShoot;
    }

    /** シャッターを開けられる状態か。 */
    public boolean canShoot() {
        return canShoot;
    }

    /** 覗いている間に描く 1 行。撮れる状態なら操作も添える（これが無いと 2 回目の click に気づけない）。 */
    public String line() {
        return canShoot ? text + " Click again to open the shutter." : text;
    }

    /** 撮れなかった時に actionbar へ出す 1 行（操作の案内は付けない）。 */
    public String reason() {
        return text;
    }

    public static ViewfinderReading fromOrdinal(int ordinal) {
        ViewfinderReading[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : NO_PLATE;
    }
}

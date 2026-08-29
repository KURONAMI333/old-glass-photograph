package com.kuronami.oldglassphotograph.client.view;

import com.kuronami.oldglassphotograph.capture.PhotographViewGeometry;
import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.kuronami.oldglassphotograph.client.render.PlateTextures;
import com.kuronami.oldglassphotograph.client.render.PhotographHandRenderer;
import com.kuronami.oldglassphotograph.component.OgpNbt;
import com.kuronami.oldglassphotograph.component.PhotoCredit;
import com.kuronami.oldglassphotograph.item.PhotographItem;
import com.kuronami.oldglassphotograph.item.PhotographViewRequest;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 完成した写真をじっくり見る面（§32-5・承認済みの B 案）。
 *
 * <p>担保は構造で取る。この面は {@code Screen} を 1 つも作らず、
 * {@code Minecraft#setScreen} を 1 度も呼ばず、{@code player.input} にも一切触れない。
 * 描くのはローダーが足した HUD レイヤ（Fabric: HudRenderCallback）1 枚だけで、
 * これは Screen と違って入力経路に何も割り込まない。
 */
public final class PhotographViewer {

    /**
     * 幕の上端。<b>vanilla のインベントリが世界の上に敷いている幕と同じ値</b>
     * （{@code Screen#renderTransparentBackground} の fillGradient）。
     * 色は RGB (16,16,16)、alpha は上 192 / 下 208 の縦グラデ。
     */
    private static final int VEIL_TOP = 0xC0101010;

    /** 幕の下端。{@link #VEIL_TOP} と対。 */
    private static final int VEIL_BOTTOM = 0xD0101010;

    // --- ケースの木口。実測値は smg_mahogany_wetplate.jpg で、暗幕の下へ落とす係数 0.74。 ---
    /** 面取りの明部 (171,101,39) x0.74。 */
    private static final int WOOD_LIT = 0xFF7F4B1D;
    /** 枠の地 (136,81,42) x0.74。 */
    private static final int WOOD_MID = 0xFF653C1F;
    /** 内側へ落ちる面 (129,56,3) x0.74。 */
    private static final int WOOD_DEEP = 0xFF5F2902;
    /** 面取りの陰部 (88,39,0) x0.74。 */
    private static final int WOOD_SHADOW = 0xFF411D00;
    /** 木と硝子の間の黒い決り (41,41,41) x0.42。 */
    private static final int REBATE = 0xFF111111;

    /** ケースを開ける音（額縁の音は使わない＝2026-08-23 実機指摘）。 */
    private static final SoundEvent OPEN_SOUND = SoundEvents.BOOK_PAGE_TURN;

    /** ケースを閉じる音。 */
    private static final SoundEvent CLOSE_SOUND = SoundEvents.BOOK_PUT;

    /** 撮影者と日付の色。木口の明部から彩度を抜いた暖かい灰。 */
    private static final int CREDIT_COLOR = 0xFFBFAE97;

    /** 枠の下端と撮影者の行の間（GUI px）。 */
    private static final int CREDIT_GAP = 6;

    /** 行送り（GUI px）。vanilla の font と同じ。 */
    private static final int LINE_HEIGHT = 10;

    private static boolean open;
    private static InteractionHand viewedHand = InteractionHand.MAIN_HAND;

    /** 使用キーが離されるまで次の開閉を受け付けない。 */
    private static boolean useLatched;

    /** 開いた時に既に押されていたスニークは出口として数えない。 */
    private static boolean sneakLatched;

    private PhotographViewer() {
    }

    /** ローダー側の初期化が呼ぶ結線。opener を {@link PhotographViewRequest} へ渡す。 */
    public static void init() {
        PhotographViewRequest.setOpener(PhotographViewer::toggle);
    }

    /**
     * 右クリックで開く／閉じる。{@link PhotographViewRequest} から呼ばれる。
     *
     * @return 開閉したか（false なら vanilla の使用処理へ流す）
     */
    public static boolean toggle(InteractionHand hand) {
        if (useLatched) {
            return true;
        }
        useLatched = true;
        if (open) {
            close();
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || PhotoCaptureClient.isEngaged()) {
            return false;
        }
        open = true;
        viewedHand = hand;
        sneakLatched = mc.options.keyShift.isDown();
        play(OPEN_SOUND, 0.6F, 0.8F);
        return true;
    }

    /** どの経路から来ても、閉じるのはここ 1 箇所。 */
    public static void close() {
        if (!open) {
            return;
        }
        open = false;
        play(CLOSE_SOUND, 0.6F, 0.9F);
    }

    /** 面が開いているか。ローダー側の画面開始フックと HUD レイヤが読む。 */
    public static boolean isOpen() {
        return open;
    }

    /**
     * 画面が開こうとした（Esc・E・T・…）。どれでもこの面は閉じる。
     *
     * <p><b>止めるのは Esc のポーズ画面だけ。</b>ポーズ画面でも<b>ウィンドウが非アクティブなら
     * 止めない</b>（それは Esc ではなく {@code pauseIfInactive} の出力）。
     *
     * @return 画面を開くのを止めるべきか（ローダー側で setScreen を握り潰す）
     */
    public static boolean onScreenOpening(@Nullable Screen newScreen) {
        if (!open) {
            return false;
        }
        close();
        return newScreen instanceof PauseScreen && Minecraft.getInstance().isWindowActive();
    }

    /** 每 tick。ローダーの client tick 終端から呼ぶ。 */
    public static void endClientTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!mc.options.keyUse.isDown()) {
            useLatched = false;
        } else if (player != null && player.isUsingItem()) {
            useLatched = true;
        }
        if (!open) {
            return;
        }
        if (mc.level == null || player == null || !player.isAlive()) {
            open = false;
            return;
        }
        if (PhotoCaptureClient.isEngaged()) {
            close();
            return;
        }
        if (!(player.getItemInHand(viewedHand).getItem() instanceof PhotographItem)) {
            close();
            return;
        }
        boolean sneak = mc.options.keyShift.isDown();
        if (!sneak) {
            sneakLatched = false;
        } else if (!sneakLatched) {
            close();
        }
    }

    // ------------------------------------------------------------------ 描画

    public static void render(GuiGraphics graphics, float tickDelta) {
        if (!open) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || PhotoCaptureClient.isEngaged()) {
            return;
        }
        ItemStack stack = player.getItemInHand(viewedHand);
        if (!(stack.getItem() instanceof PhotographItem)) {
            return;
        }
        RenderTarget target = mc.getMainRenderTarget();
        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        if (target.width <= 0 || target.height <= 0 || guiWidth <= 0 || guiHeight <= 0) {
            return;
        }

        PhotoCredit credit = OgpNbt.credit(stack);
        PhotographViewGeometry.Layout layout = PhotographViewGeometry.layout(
                target.width, target.height, mc.getWindow().getGuiScale(), credit != null);

        float toGuiX = guiWidth / (float) target.width;
        float toGuiY = guiHeight / (float) target.height;

        // 1. 幕。半透明なので裏の景色が透ける。
        graphics.fillGradient(0, 0, guiWidth, guiHeight, VEIL_TOP, VEIL_BOTTOM);

        // ここから 1 単位 = 実画面の 1 px。写真のドットが実 px の整数倍で並ぶ。
        graphics.pose().pushPose();
        graphics.pose().scale(toGuiX, toGuiY, 1.0F);
        drawCase(graphics, layout, stack, level);
        graphics.pose().popPose();

        if (credit != null) {
            drawCredit(graphics, mc.font, guiWidth,
                    Math.round(layout.bottom() * toGuiY) + CREDIT_GAP, credit);
        }
    }

    /** ケースの枠と写真。座標は実 px（呼ぶ側が pose を掛けてある）。 */
    private static void drawCase(GuiGraphics graphics, PhotographViewGeometry.Layout layout,
                                 ItemStack stack, ClientLevel level) {
        int bevel = layout.bevel();

        // 木口。上と左が明るく、下と右が落ちる。
        graphics.fill(layout.x(), layout.y(), layout.right(), layout.bottom(), WOOD_LIT);
        graphics.fill(layout.x(), layout.bottom() - bevel, layout.right(), layout.bottom(), WOOD_SHADOW);
        graphics.fill(layout.right() - bevel, layout.y(), layout.right(), layout.bottom(), WOOD_SHADOW);
        inset(graphics, layout, bevel, WOOD_MID);
        inset(graphics, layout, bevel * 3, WOOD_DEEP);
        // 黒い決り。硝子の縁が座る段。
        graphics.fill(layout.rebateX(), layout.rebateY(),
                layout.rebateX() + layout.rebateSize(), layout.rebateY() + layout.rebateSize(), REBATE);

        // 写真そのもの。map の動的テクスチャは NEAREST で貼られるので整数倍なら滲まない。
        drawPhoto(stack, level, graphics, layout);
    }

    private static void drawPhoto(ItemStack stack, ClientLevel level, GuiGraphics graphics,
                                  PhotographViewGeometry.Layout layout) {
        Integer id = OgpNbt.mapId(stack);
        var texture = PlateTextures.BLANK;
        if (id != null) {
            texture = PlateTextures.texture(id, level.getMapData(PhotographHandRenderer.mapKey(id)));
        }
        graphics.blit(texture,
                layout.photoX(), layout.photoY(),
                layout.photoSize(), layout.photoSize(),
                0.0F, 0.0F, 128, 128, 128, 128);
    }

    /** 外枠から {@code depth} px 内側の正方形を塗る。 */
    private static void inset(GuiGraphics graphics, PhotographViewGeometry.Layout layout,
                              int depth, int color) {
        graphics.fill(layout.x() + depth, layout.y() + depth,
                layout.right() - depth, layout.bottom() - depth, color);
    }

    /** 撮影者と日付、実世界の日時。枠の下の暗幕へ最大 3 行。座標は GUI px。 */
    private static void drawCredit(GuiGraphics graphics, Font font, int guiWidth,
                                   int top, PhotoCredit credit) {
        int centerX = guiWidth / 2;
        graphics.drawCenteredString(font,
                Component.translatable("view.old_glass_photograph.taken_by", credit.author()),
                centerX, top, CREDIT_COLOR);
        graphics.drawCenteredString(font,
                Component.translatable("view.old_glass_photograph.day", credit.day()),
                centerX, top + LINE_HEIGHT, CREDIT_COLOR);
        if (!credit.capturedAt().isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("view.old_glass_photograph.captured_at", credit.capturedAt()),
                    centerX, top + LINE_HEIGHT * 2, CREDIT_COLOR);
        }
    }

    private static void play(SoundEvent sound, float volume, float pitch) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.playSound(sound, volume, pitch);
        }
    }
}

package com.kuronami.oldglassphotograph.client.view;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.capture.PhotographViewGeometry;
import com.kuronami.oldglassphotograph.client.capture.PhotoCaptureClient;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.component.PhotoCredit;
import com.kuronami.oldglassphotograph.item.PhotographItem;
import com.kuronami.oldglassphotograph.item.PhotographViewRequest;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 完成した写真をじっくり見る面（{@code MODJAM_DECISIONS_OGP.md} §32-5・承認済みの B 案）。
 *
 * <h2>歩けることをどう担保しているか</h2>
 *
 * 要件は「インベントリとかと同じで、移動しながらでも見れる」。vanilla のインベントリを
 * 開いている間は歩けないので、<b>後者を取る</b>のが §32-5 の裁定。
 *
 * <p>担保はフラグではなく<b>構造</b>で取る。この面は {@code Screen} を 1 つも作らず、
 * {@code Minecraft#setScreen} を 1 度も呼ばず、{@code player.input} にも一切触れない。
 * 描くのは {@code RegisterGuiLayersEvent} で足した層 1 枚だけで、これは
 * {@code Screen} と違って入力経路に何も割り込まない（ファインダーが覗きながら向きを
 * 変えられているのと同じ作り＝§34）。したがって移動の扱いは素のプレイと同一である。
 *
 * <h2>出口</h2>
 *
 * <ul>
 *   <li>もう一度の右クリック（{@link PhotographViewRequest} 経由）</li>
 *   <li>スニーク（押し直しでだけ効く。開いた時に押されていた分は数えない）</li>
 *   <li>Esc・E・T など画面を開こうとした時（{@link ScreenEvent.Opening}）。
 *       <b>Esc のポーズ画面だけは止めて</b>、代わりにこの面を閉じる</li>
 *   <li>その手が写真でなくなった時（持ち替え・落とした・消えた）</li>
 *   <li>level / player が消えた時、ファインダーに入った時</li>
 * </ul>
 *
 * <p>幕は<b>半透明</b>で、裏の景色が見える（要件は「インベントリと全く一緒」）。値は vanilla の
 * {@code Screen#extractTransparentBackground} をそのまま借りている（{@link #VEIL_TOP} 参照）。
 *
 * <p>HUD は隠さない。この層は HUD より後に描かれるので、隠さなければ「戻し忘れ」で HUD が
 * 消えたままになる経路がそもそも存在しない。半透明にした結果、写真の外側では HUD が幕越しに
 * 沈んで見える（vanilla のインベントリは {@code Screen} なので HUD 自体が出ない）。
 * 十字も体力もケースの下に隠れるので、写真そのものには重ならない。
 */
public final class PhotographViewer {

    /**
     * 幕の上端。<b>vanilla のインベントリが世界の上に敷いている幕と同じ値</b>で、
     * {@code Screen#extractTransparentBackground} が
     * {@code fillGradient(0, 0, w, h, -1072689136, -804253680)} を呼んでいる
     * （{@code MC: net/minecraft/client/gui/screens/Screen.java:417-419}）。
     * {@code AbstractContainerScreen#isInGameUi()} が {@code true} を返すので
     * （{@code MC: .../inventory/AbstractContainerScreen.java:539-542}）、
     * 持ち物画面が通るのはこの経路である。
     *
     * <p>色は RGB (16,16,16)、alpha は上 192 / 下 208 の縦グラデ。
     * {@code fillGradient} は縦にしか引けないので、vanilla と同じ向きにそのまま乗る。
     */
    private static final int VEIL_TOP = 0xC0101010;

    /** 幕の下端。{@link #VEIL_TOP} と対。 */
    private static final int VEIL_BOTTOM = 0xD0101010;

    // --- ケースの木口。実測値は smg_mahogany_wetplate.jpg（ファインダーの枠と同じ写真・同じ語彙）で、
    //     暗幕の下へ落とす係数 0.74 も generate_viewfinder.py と揃えてある。
    //     値をここに複製しているのは意図的で、generate_viewfinder.py は触らない（§37）。
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

    /**
     * ケースを開ける音。
     *
     * <p><b>額縁の音を使わない。</b>この面は写真を手に持って右クリックで開くので、
     * {@code entity.item_frame.add_item} を鳴らすと「額縁に写真をはめた」と読まれる
     * （2026-08-23 実機指摘）。同じ右クリックで実際に起こりうる操作なので、
     * 音が似ているだけで誤解が成立してしまう。
     *
     * <p>紙の擦れる音を既定より低いピッチで鳴らす。ケースは革張りの二つ折りで、
     * 開ける時に鳴るのは蝶番と張り布の音である。既存の 10 種
     * （布・望遠鏡・木のボタン・レバー・石のボタン・罠戸・醸造・水掻き・瓶・ガラス）
     * のどれとも音源が重ならない。
     */
    private static final SoundEvent OPEN_SOUND = SoundEvents.BOOK_PAGE_TURN;

    /** ケースを閉じる音。{@link #OPEN_SOUND} と対で、置く側なので軽い当たりの音にする。 */
    private static final SoundEvent CLOSE_SOUND = SoundEvents.BOOK_PUT;

    /** 撮影者と日付の色。木口の明部から彩度を抜いた暖かい灰。 */
    private static final int CREDIT_COLOR = 0xFFBFAE97;

    /** 枠の下端と撮影者の行の間（GUI px）。{@code CREDIT_BLOCK_GUI} の内訳と対。 */
    private static final int CREDIT_GAP = 6;

    /** 行送り（GUI px）。vanilla の font と同じ。 */
    private static final int LINE_HEIGHT = 10;

    /** 像がまだ届いていない時に貼る地。{@code PhotographSpecialRenderer.BLANK} と同じ絵。 */
    private static final Identifier BLANK =
            Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "textures/item/photograph.png");

    private static boolean open;
    private static InteractionHand viewedHand = InteractionHand.MAIN_HAND;

    /**
     * 使用キーが離されるまで次の開閉を受け付けない。
     *
     * <p>{@code Minecraft#handleKeybinds} は使用キーを押しっぱなしにしていると
     * {@code rightClickDelay} が 0 になるたびに {@code startUseItem} を呼び直す
     * （{@code MC: net/minecraft/client/Minecraft.java:2024-2025}）。掛けないと 4 tick ごとに
     * 開いて閉じてを繰り返す。
     *
     * <p><b>アイテムを使っている間も立てる</b>（{@link #onClientTick}）。定着は右クリックを
     * 押し続ける操作で、押したまま板が写真に変わるので、同じ 1 回の押下がそのまま
     * {@code PhotographItem#use} に届いて拡大が開いてしまう。工程が終わった tick には
     * 既にここが立っているので、キーを離すまで開かない。
     */
    private static boolean useLatched;

    /** 開いた時に既に押されていたスニークは出口として数えない。 */
    private static boolean sneakLatched;

    private PhotographViewer() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(PhotographViewer::registerGuiLayers);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, PhotographViewer::onClientTick);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class, PhotographViewer::onScreenOpening);
        PhotographViewRequest.setOpener(PhotographViewer::toggle);
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "photograph_view"),
                PhotographViewer::render);
    }

    // ------------------------------------------------------------------ 開閉

    /**
     * 右クリックで開く／閉じる。{@link PhotographViewRequest} から呼ばれる。
     *
     * @return 開閉したか（false なら vanilla の使用処理へ流す）
     */
    private static boolean toggle(InteractionHand hand) {
        if (useLatched) {
            // 押しっぱなしの繰り返し。1 回の押下で 1 回だけ効かせる。
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
    private static void close() {
        if (!open) {
            return;
        }
        open = false;
        play(CLOSE_SOUND, 0.6F, 0.9F);
    }

    /**
     * 画面が開こうとした（Esc・E・T・…）。どれでもこの面は閉じる。
     *
     * <p><b>止めるのは Esc のポーズ画面だけ。</b>vanilla の画面を 1 枚開いている時に Esc を押すのと
     * 同じ挙動にする（1 回目で手前の面が消え、もう 1 回押せばポーズ画面が出る）。
     *
     * <p>それ以外は止めない。<b>止めてよい画面かどうかを一般に判定する手段が無い</b>ためで、
     * 死亡画面・次元移動の受信画面・切断画面まで止めると、写真を見ていた瞬間にそれが起きた player が
     * 画面を失う。閉じるだけなら副作用が無いので、そちらへ倒す。
     *
     * <p>ポーズ画面でも<b>ウィンドウが非アクティブなら止めない</b>。それは Esc ではなく
     * {@code Minecraft#pauseIfInactive} が出したもので（{@code MC: net/minecraft/client/Minecraft.java:1391-1394}
     * は {@code !window.isFocused()} が条件）、止めるとシングルプレイで裏に回した時に世界が動き続ける。
     */
    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!open) {
            return;
        }
        close();
        if (event.getNewScreen() instanceof PauseScreen && Minecraft.getInstance().isWindowActive()) {
            event.setCanceled(true);
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!mc.options.keyUse.isDown()) {
            useLatched = false;
        } else if (player != null && player.isUsingItem()) {
            // 押しっぱなしで何かを使っている最中。その押下では拡大を開かせない。
            useLatched = true;
        }
        if (!open) {
            return;
        }
        if (mc.level == null || player == null || !player.isAlive()) {
            // 出口の最後の 1 本。死亡・切断・次元移動で開いたままにならない。
            open = false;
            return;
        }
        if (PhotoCaptureClient.isEngaged()) {
            // ファインダーに入った。2 つの面が重なって描かれないように、こちらが退く。
            close();
            return;
        }
        if (!(player.getItemInHand(viewedHand).getItem() instanceof PhotographItem)) {
            // 持ち替え・落とした・使い切った。見る対象が無いのに面だけ残さない。
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

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
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
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        if (target.width <= 0 || target.height <= 0 || guiWidth <= 0 || guiHeight <= 0) {
            return;
        }

        PhotoCredit credit = stack.get(OgpDataComponents.PHOTO_CREDIT.get());
        PhotographViewGeometry.Layout layout = PhotographViewGeometry.layout(
                target.width, target.height, mc.getWindow().getGuiScale(), credit != null);

        // 実 px -> GUI 座標の比。名目の guiScale でなく実測の商を使う
        // （画面幅が guiScale で割り切れない時に像の端が実 px の境界からずれる）。
        float toGuiX = guiWidth / (float) target.width;
        float toGuiY = guiHeight / (float) target.height;

        graphics.nextStratum();

        // 1. 幕。ケースを幕の上に置いて覗いている絵にする。半透明なので裏の景色が透ける。
        graphics.fillGradient(0, 0, guiWidth, guiHeight, VEIL_TOP, VEIL_BOTTOM);

        graphics.pose().pushMatrix();
        // ここから 1 単位 = 実画面の 1 px。写真のドットが実 px の整数倍で並ぶ。
        graphics.pose().scale(toGuiX, toGuiY);
        drawCase(graphics, layout, stack, level);
        graphics.pose().popMatrix();

        if (credit != null) {
            drawCredit(graphics, mc.font, guiWidth,
                    Math.round(layout.bottom() * toGuiY) + CREDIT_GAP, credit);
        }
    }

    /**
     * ケースの枠と写真。座標は実 px（呼ぶ側が pose を掛けてある）。
     *
     * <p>外から内へ塗り重ねる。木口は 面取り 1 / 地 2 / 内側の落ち 1 の 4 単位で、
     * その内側に黒い決りが 1 単位。実物のケース入りアンブロタイプは黒い裏当てのガラス板を
     * 木の枠に落とし込み、硝子の縁は決りの段で隠れる（{@code MODJAM_DECISIONS_OGP.md} §29）。
     */
    private static void drawCase(GuiGraphicsExtractor graphics, PhotographViewGeometry.Layout layout,
                                 ItemStack stack, ClientLevel level) {
        int bevel = layout.bevel();

        // 木口。上と左が明るく、下と右が落ちる（光は暗幕の隙間から斜め上前から入る）。
        graphics.fill(layout.x(), layout.y(), layout.right(), layout.bottom(), WOOD_LIT);
        graphics.fill(layout.x(), layout.bottom() - bevel, layout.right(), layout.bottom(), WOOD_SHADOW);
        graphics.fill(layout.right() - bevel, layout.y(), layout.right(), layout.bottom(), WOOD_SHADOW);
        inset(graphics, layout, bevel, WOOD_MID);
        inset(graphics, layout, bevel * 3, WOOD_DEEP);
        // 黒い決り。硝子の縁が座る段。
        graphics.fill(layout.rebateX(), layout.rebateY(),
                layout.rebateX() + layout.rebateSize(), layout.rebateY() + layout.rebateSize(), REBATE);

        // 写真そのもの。map の動的テクスチャは NEAREST で貼られる
        // （MC: net/minecraft/client/renderer/texture/DynamicTexture.java:41）ので、
        // 整数倍ならドットは滲まない。
        Identifier texture = BLANK;
        MapId id = stack.get(DataComponents.MAP_ID);
        if (id != null) {
            MapItemSavedData data = level.getMapData(id);
            if (data != null) {
                texture = Minecraft.getInstance().getMapTextureManager().prepareMapTexture(id, data);
            }
        }
        graphics.blit(texture,
                layout.photoX(), layout.photoY(),
                layout.photoX() + layout.photoSize(), layout.photoY() + layout.photoSize(),
                0.0F, 1.0F, 0.0F, 1.0F);
    }

    /** 外枠から {@code depth} px 内側の正方形を塗る。 */
    private static void inset(GuiGraphicsExtractor graphics, PhotographViewGeometry.Layout layout,
                              int depth, int color) {
        graphics.fill(layout.x() + depth, layout.y() + depth,
                layout.right() - depth, layout.bottom() - depth, color);
    }

    /**
     * 撮影者と日付、実世界の日時。枠の下の暗幕へ最大 3 行。座標は GUI px。
     *
     * <p>{@code capturedAt} はこれより前に現像された写真だと空文字列
     * （{@link PhotoCredit#capturedAt()} の後方互換）なので、その時は 3 行目を出さない。
     */
    private static void drawCredit(GuiGraphicsExtractor graphics, Font font, int guiWidth,
                                   int top, PhotoCredit credit) {
        int centerX = guiWidth / 2;
        graphics.centeredText(font,
                Component.translatable("view.old_glass_photograph.taken_by", credit.author()),
                centerX, top, CREDIT_COLOR);
        graphics.centeredText(font,
                Component.translatable("view.old_glass_photograph.day", credit.day()),
                centerX, top + LINE_HEIGHT, CREDIT_COLOR);
        if (!credit.capturedAt().isEmpty()) {
            graphics.centeredText(font,
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

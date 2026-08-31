package com.kuronami.oldglassphotograph.item;

import com.kuronami.oldglassphotograph.OgpAdvancements;
import com.kuronami.oldglassphotograph.OgpObjects;
import com.kuronami.oldglassphotograph.block.DarkroomTableBlock;
import com.kuronami.oldglassphotograph.capture.PhotoDeveloper;
import com.kuronami.oldglassphotograph.component.OgpNbt;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Glass Plate。工程状態と潜像を ItemStack の NBT（{@link OgpNbt}）に持つ。
 *
 * <p><b>素の板は {@link #BLANK_MAX_STACK} 枚まで重なり、工程に入った板は 1 枚ずつになる。</b>
 * 26.x の per-stack {@code MAX_STACK_SIZE} component はこの帯に無いので、工程に入る瞬間
 * （{@link #markSingle}）に個体タグを書き、NBT の異なるスタックが重ならない仕組みで同じ結果にする。
 *
 * <p>カスタム GUI は作らない。<b>暗所を要する塗布と現像は {@link DarkroomTableBlock} の中で回り</b>、
 * 箱を開けて板を入れ、蓋を閉じると工程が始まる（{@code MODJAM_DECISIONS_OGP.md} §30・B-2）。
 * 暗いのは箱の中なので、周りの明るさは工程に一切効かない。
 *
 * <p>手に持ったまま長押しするのは<b>定着だけ</b>。史実でも定着は暗室を出てから行える。
 *
 * <p><b>3 種類の薬液はどれも、使う瞬間に手に持っている。</b>塗布と現像は板が箱の中なので
 * 薬品を手に持って蓋を閉じ、定着は板を手に持って振るので薬品はもう一方の手になる
 * （{@code MODJAM_DECISIONS_OGP.md} §32-1 と 2026-08-23 指示）。
 * 薬品が無い・段が違う場合は<b>何も消費せず</b>理由だけを出す。
 */
public class GlassPlateItem extends Item {

    /**
     * 素のガラス板が重なる枚数。
     *
     * <p>この MOD はゲームバランスに影響しない完全娯楽 MOD で、板は写真 1 枚あたり 1 枚消える
     * 消耗品（{@code MODJAM_DECISIONS_OGP.md} §35）。素材（ガラス）と同じ 64 枚にする。
     */
    public static final int BLANK_MAX_STACK = 64;

    /** 銀浴から乾くまで。実機検証済み = 60 秒。 */
    public static final int WET_TICKS = 1200;

    /** 板の準備（洗浄 + コロジオン + 銀浴をまとめた 1 操作）。 */
    public static final int PREPARE_TICKS = 80;

    /** 現像液。 */
    public static final int DEVELOP_TICKS = 80;

    /** 定着。 */
    public static final int FIX_TICKS = 120;

    /**
     * 定着中に液の音を鳴らす間隔（tick）。{@code UseAnim.BRUSH} の 1 振りが 9 tick なので、
     * vanilla の {@code BrushItem} と同じ 10 tick 周期・位相 5（振りの途中）に合わせる。
     */
    private static final int SHAKE_SOUND_INTERVAL = 10;

    private static final int SHAKE_SOUND_PHASE = 5;

    /** 定着の液音。手元で板を揺すっているだけなので、遠くまで届かせない。 */
    private static final float SHAKE_SOUND_VOLUME = 0.35F;

    /** アイテム欄のバーの色。Fixer のテクスチャの液部 (146,166,163) をそのまま使う。 */
    private static final int FIX_BAR_COLOR = 0x92A6A3;

    public GlassPlateItem(Properties properties) {
        super(properties);
    }

    public static boolean isExposed(ItemStack stack) {
        return OgpNbt.isExposed(stack);
    }

    public static @Nullable PlateProcess process(ItemStack stack) {
        return OgpNbt.process(stack);
    }

    /** カメラに入れてよい板か（濡れた感光板で、まだ潜像を持っていない）。 */
    public static boolean isReadyToLoad(ItemStack stack) {
        PlateProcess p = process(stack);
        return p != null && p.stage() == PlateProcess.Stage.SENSITIZED && !isExposed(stack);
    }

    /** 工程に入った板として印す（重ねられなくする）。{@code OgpNbt#markSingle} 参照。 */
    private static void markSingle(ItemStack stack) {
        OgpNbt.markSingle(stack);
    }

    /**
     * 板から工程の NBT を全部外して素のガラス板へ戻す。<b>板そのものは失われない</b>
     * （受理済みの非破壊原則）。呼ぶ側が「戻していいか」を先に判定してから呼ぶ
     * （乾いた時は {@link #resolveDryOut}、水入り大釜で洗った時は工程を問わず
     * {@link #washInCauldron}）。
     */
    private static void resetToBlank(ItemStack stack) {
        OgpNbt.resetToBlank(stack);
    }

    /**
     * 乾いていたら板を素のガラス板へ戻す。
     *
     * <p>乾いた板を別アイテムにして「洗い直し」の操作を足すと工程が 1 段増える。短縮の趣旨に
     * 反するので、乾いた時点で素のガラス板へ戻す形にした（失うのは塗った薬品 1 個と、
     * 露光済みなら 1 回の撮影機会だけ）。素のガラスへ戻る以上、溜まったかぶりも一緒に落ちる。
     *
     * @return 乾いていて戻した場合 true
     */
    public static boolean resolveDryOut(ItemStack stack, long gameTime) {
        PlateProcess p = process(stack);
        if (p == null || !p.isDriedAt(gameTime)) {
            return false;
        }
        resetToBlank(stack);
        return true;
    }

    /**
     * 水入り大釜（{@code minecraft:water_cauldron}）で洗って、工程のどの段階でも
     * 一回で素のガラス板へ戻す。{@code OgpRegistry.registerCauldronInteractions} が
     * vanilla の water dispatcher（{@code CauldronInteraction.WATER}）にこのメソッドを登録する。
     *
     * <p>呼び出し元は {@code MC: net/minecraft/world/level/block/AbstractCauldronBlock.java}
     * の相互作用 — dispatcher は {@code Item} 単位の索引なので、GlassPlateItem のどの
     * 工程段階のスタックでもここへ来る。
     *
     * <p>水位は他の洗浄操作と同じく 1 減らす（{@code LayeredCauldronBlock.lowerFillLevel}）。
     * 効果音は無い（vanilla の旗/染色の洗浄も鳴らさない）。<b>現像済みは乾燥期限を持たない</b>ので、
     * 定着液を消費せずガラスだけ回収する経路がこれしか無い。
     */
    public static InteractionResult washInCauldron(BlockState cauldronState, Level level, BlockPos pos,
                                                    Player player, InteractionHand hand, ItemStack stack) {
        if (process(stack) == null) {
            // バニラの旗/染色アイテムと同じ「対象外」。既定の大釜相互作用へ委ねる。
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            resetToBlank(stack);
            LayeredCauldronBlock.lowerFillLevel(cauldronState, level, pos);
            say(player, Component.translatable("message.old_glass_photograph.plate.washed"));
        }
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ 操作

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        return switch (begin(level, player, hand)) {
            case SUCCESS -> InteractionResultHolder.success(stack);
            case CONSUME -> InteractionResultHolder.consume(stack);
            case FAIL -> InteractionResultHolder.fail(stack);
            default -> InteractionResultHolder.pass(stack);
        };
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        // Darkroom Table を押した場合はブロック側が先に受け取っている。ここへ来るのは
        // 箱の仕事でない板（露光待ち・定着待ち・乾いた板）だけなので、空中と同じ扱いでよい。
        return begin(context.getLevel(), player, context.getHand());
    }

    private InteractionResult begin(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Step step = nextStep(stack, level.getGameTime());
        if (step == Step.DRIED) {
            if (!level.isClientSide()) {
                resolveDryOut(stack, level.getGameTime());
                say(player, Component.translatable("message.old_glass_photograph.plate.dried"));
            }
            return InteractionResult.SUCCESS;
        }
        if (step == null) {
            if (!level.isClientSide()) {
                say(player, Component.translatable("message.old_glass_photograph.plate.load_into_camera"));
            }
            return InteractionResult.FAIL;
        }
        if (step.inDarkroomBox()) {
            // 塗布と現像は暗箱の中でしか進まない。手の中では何も起きない。
            if (!level.isClientSide()) {
                say(player, Component.translatable("message.old_glass_photograph.plate.use_darkroom",
                        step.chemicalName()));
            }
            return InteractionResult.FAIL;
        }
        // 板を振る手は塞がっているので、薬品はもう一方の手にある。
        // どちらの手に板を持っても成立する（vanilla は主手から順に試すので、
        // 薬品が主手なら PASS で落ちて<b>板を持っている手でここへ来る</b>）。
        if (otherHandWith(player, hand, step) == null) {
            if (!level.isClientSide()) {
                say(player, Component.translatable("message.old_glass_photograph.plate.need_chemical",
                        step.chemicalName()));
            }
            return InteractionResult.FAIL;
        }
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BRUSH;
    }

    /**
     * 使う長さ。1.20.1 の {@code getUseDuration(ItemStack)} には entity / level が渡らないため、
     * 乾燥判定はここでは行わない（常に期限ありとして段の長さを返す）。
     * <b>実際に使い始めてよいかは {@link #begin} が実 game time で判定済み</b>であり、
     * 振っている最中に乾いた場合は {@link #finishUsingItem} が段を再検査して無視する。
     */
    @Override
    public int getUseDuration(ItemStack stack) {
        Step step = nextStep(stack, Long.MAX_VALUE);
        return step == null || step.inDarkroomBox() ? 0 : step.durationTicks();
    }

    /**
     * 定着のあいだ、板を液の中で揺すっている音を繰り返す。
     *
     * <p>{@code entity.generic.swim} は vanilla が水を掻く時に鳴らしている短い音で、
     * <b>ここで実際に起きていること（液の中で板を前後に振る）と同じ動き</b>から出る音になる。
     * 音は実際に鳴らして選べないので、選んだ理由は「動きが同じ」の 1 点。
     *
     * <p>vanilla の {@code BrushItem} と同じく、鳴らす側は「使っている本人を除いた放送」にする。
     * client でも同じ tick に同じ判定が通るので、本人は自分の client が鳴らす 1 回だけを聞く。
     * ピッチは経過 tick から出す（乱数だと本人と他人で音が食い違う）。
     */
    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remaining) {
        if (remaining < 0 || nextStep(stack, level.getGameTime()) != Step.FIX) {
            return;
        }
        int elapsed = FIX_TICKS - remaining + 1;
        if (elapsed % SHAKE_SOUND_INTERVAL != SHAKE_SOUND_PHASE) {
            return;
        }
        float pitch = 0.85F + (elapsed / SHAKE_SOUND_INTERVAL % 3) * 0.08F;
        // 板を振れるのは player だけ。「使っている本人を除いた放送」の意味論は変わらない。
        if (user instanceof Player listener) {
            level.playSound(listener, user.getX(), user.getY(), user.getZ(),
                    SoundEvents.GENERIC_SWIM, SoundSource.PLAYERS, SHAKE_SOUND_VOLUME, pitch);
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (level.isClientSide() || !(entity instanceof ServerPlayer player)) {
            return stack;
        }
        // 手の中で終わるのは定着だけ（塗布と現像は Darkroom Table の中で終わる）。
        Step step = nextStep(stack, level.getGameTime());
        if (step != Step.FIX) {
            return stack;
        }
        // 振っている板はこの stack そのもの。<b>参照で</b>どちらの手かを決める
        // （同じ段の板を両手に持っていても取り違えない）。
        InteractionHand plateHand = player.getOffhandItem() == stack
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        InteractionHand chemicalHand = otherHandWith(player, plateHand, step);
        if (chemicalHand == null) {
            // 6 秒のあいだに持ち替えられた。板は無事のまま何も起きない。
            say(player, Component.translatable("message.old_glass_photograph.plate.need_chemical",
                    step.chemicalName()));
            return stack;
        }
        player.getItemInHand(chemicalHand).shrink(1);
        if (PhotoDeveloper.develop(player, stack)) {
            // 揺する音が無音で切れると途中で止めたのか終わったのか分からない。終わりに 1 つだけ置く。
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BOTTLE_EMPTY, SoundSource.PLAYERS, 0.6F, 1.15F);
            say(player, Component.translatable("message.old_glass_photograph.plate.fixed"));
        }
        return stack;
    }

    /**
     * Darkroom Table の中で終わった工程の結果を板へ書く。
     * 呼ぶのは {@code DarkroomTableBlockEntity} だけで、薬品の消費は投入時に済んでいる。
     */
    public static void applyDarkroomResult(ItemStack plate, Step step, long gameTime) {
        markSingle(plate);
        switch (step) {
            case PREPARE -> OgpNbt.setProcess(plate, new PlateProcess(
                    PlateProcess.Stage.SENSITIZED, gameTime + WET_TICKS, WET_TICKS / 20));
            case DEVELOP -> OgpNbt.setProcess(plate,
                    new PlateProcess(PlateProcess.Stage.DEVELOPED, 0L, 0));
            default -> {
            }
        }
    }

    // ------------------------------------------------------- 定着の進み具合（アイテム欄のバー）

    /**
     * 定着のあいだだけ、道具の耐久バーと同じ場所に進み具合を出す。
     *
     * <p>「どれくらい振ればいいのか分からない」への答えなので、<b>耐久バーの逆</b>で
     * 空から満ちる。満ちた瞬間に写真になるので、満ちる = 終わり がそのまま読める。
     *
     * <p>出すのは定着だけ。塗布と現像は Darkroom Table の中で回っていて手に持っていないし、
     * 湿板の残り 60 秒は既にアイテム名に秒で出ている（同じことを 2 箇所に出さない）。
     */
    @Override
    public boolean isBarVisible(ItemStack stack) {
        return PlateUseProgress.of(stack) >= 0.0F;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Mth.clamp(Math.round(PlateUseProgress.of(stack) * 13.0F), 0, 13);
    }

    /**
     * 一色で固定する。vanilla の耐久バーは緑→赤へ振れるが、あれは「減っている」の合図なので、
     * 溜まる側に使うと壊れかけに見える。Fixer の液の色をそのまま置く。
     */
    @Override
    public int getBarColor(ItemStack stack) {
        return FIX_BAR_COLOR;
    }

    // ------------------------------------------------------------------ 乾燥

    /**
     * 1 秒ごとに残り秒を書き直し、乾いたら素のガラス板へ戻す。
     *
     * <p>client には NBT の同期（インベントリ更新パケットごとの全体転送）で届く。
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity owner, int slot, boolean selected) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        PlateProcess p = process(stack);
        if (p == null || !p.isWet()) {
            return;
        }
        long gameTime = serverLevel.getGameTime();
        if (p.isDriedAt(gameTime)) {
            resolveDryOut(stack, gameTime);
            if (owner instanceof ServerPlayer player) {
                say(player, Component.translatable("message.old_glass_photograph.plate.dried"));
                OgpAdvancements.award(player, OgpAdvancements.DRY_PLATE);
            }
            return;
        }
        int seconds = (int) Math.max(0, (p.wetUntil() - gameTime + 19) / 20);
        if (seconds != p.secondsLeft()) {
            OgpNbt.setProcess(stack, p.withSecondsLeft(seconds));
        }
    }

    // ------------------------------------------------------------------ 表示

    @Override
    public Component getName(ItemStack stack) {
        PlateProcess p = process(stack);
        if (p == null) {
            return Component.translatable("item.old_glass_photograph.glass_plate");
        }
        return switch (p.stage()) {
            case SENSITIZED -> Component.translatable(
                    "item.old_glass_photograph.glass_plate.wet", p.secondsLeft());
            case EXPOSED -> Component.translatable(
                    "item.old_glass_photograph.glass_plate.exposed", p.secondsLeft());
            case DEVELOPED -> Component.translatable("item.old_glass_photograph.glass_plate.developed");
        };
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        PlateProcess p = process(stack);
        if (p == null) {
            tooltip.add(line("tooltip.old_glass_photograph.plate.blank"));
            fogged(stack, tooltip);
            return;
        }
        switch (p.stage()) {
            case SENSITIZED -> {
                tooltip.add(line("tooltip.old_glass_photograph.plate.wet"));
                tooltip.add(wetness(p));
            }
            case EXPOSED -> {
                tooltip.add(line("tooltip.old_glass_photograph.plate.exposed"));
                tooltip.add(wetness(p));
            }
            case DEVELOPED -> tooltip.add(line("tooltip.old_glass_photograph.plate.developed"));
        }
        fogged(stack, tooltip);
    }

    /** かぶりが乗っている板だけ 1 行足す。0 の板には何も出さない。 */
    private static void fogged(ItemStack stack, List<Component> tooltip) {
        if (OgpNbt.fog(stack) > 0) {
            tooltip.add(Component.translatable("tooltip.old_glass_photograph.plate.fogged")
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    private static Component wetness(PlateProcess p) {
        int seconds = p.secondsLeft();
        ChatFormatting color = seconds <= 10 ? ChatFormatting.RED
                : seconds <= 25 ? ChatFormatting.GOLD : ChatFormatting.AQUA;
        return Component.translatable("tooltip.old_glass_photograph.plate.wet_for", seconds).withStyle(color);
    }

    private static Component line(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.GRAY);
    }

    // ------------------------------------------------------------------ 工程

    /** いま板に対してできること。null = 薬品ではどうにもならない段（露光待ち）。 */
    public static @Nullable Step nextStep(ItemStack stack, long gameTime) {
        PlateProcess p = process(stack);
        if (p == null) {
            return Step.PREPARE;
        }
        if (p.isDriedAt(gameTime)) {
            return Step.DRIED;
        }
        return switch (p.stage()) {
            case SENSITIZED -> null;
            case EXPOSED -> Step.DEVELOP;
            case DEVELOPED -> Step.FIX;
        };
    }

    /**
     * 板を持っている手と<b>反対の手</b>に、その工程の薬品があるか。定着（{@link Step#FIX}）で使う。
     *
     * <p>板を持つ手はどちらでもよい（2026-08-23 指示）。持ち方の作法を覚えさせる意味は無く、
     * 守りたい規律は「薬品を手に持っていること」の 1 点だけ。
     *
     * @return 薬品を持っている手。持っていなければ null
     */
    private static @Nullable InteractionHand otherHandWith(Player player, InteractionHand plateHand, Step step) {
        Item chemical = step.chemical();
        if (chemical == null) {
            return null;
        }
        InteractionHand other = plateHand == InteractionHand.MAIN_HAND
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        return player.getItemInHand(other).is(chemical) ? other : null;
    }

    /**
     * 薬品を<b>手に持っている</b>か（{@code MODJAM_DECISIONS_OGP.md} §32-1）。
     *
     * <p>Darkroom Table の蓋を閉じる時だけこちらを使う。持ち物のどこかから勝手に引くと、
     * 何が減ったのかが player に見えない。板は箱の中なので両手とも空いており、
     * 主手・逆手のどちらで持っていても認める（どちらかでしか通らないほうが分かりにくい）。
     */
    public static boolean holdsChemical(Player player, Step step) {
        return chemicalHand(player, step) != null;
    }

    /** 手に持っている薬品を 1 個減らす。持っていなければ何もせず false。 */
    public static boolean consumeHeldChemical(Player player, Step step) {
        InteractionHand hand = chemicalHand(player, step);
        if (hand == null) {
            return false;
        }
        player.getItemInHand(hand).shrink(1);
        return true;
    }

    private static @Nullable InteractionHand chemicalHand(Player player, Step step) {
        Item chemical = step.chemical();
        if (chemical == null) {
            return null;
        }
        if (player.getMainHandItem().is(chemical)) {
            return InteractionHand.MAIN_HAND;
        }
        if (player.getOffhandItem().is(chemical)) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    private static void say(Player player, Component text) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(text, true);
        }
    }

    /**
     * 板に対してできること。{@code inDarkroomBox} は史実の暗室工程に対応する
     * （洗浄・コロジオン・銀浴と現像は暗所。定着は暗室を出てから行える）。
     */
    public enum Step {
        PREPARE(GlassPlateItem.PREPARE_TICKS, "chemical.old_glass_photograph.collodion_kit", true,
                "message.old_glass_photograph.darkroom.start_prepare"),
        DEVELOP(GlassPlateItem.DEVELOP_TICKS, "chemical.old_glass_photograph.developer", true,
                "message.old_glass_photograph.darkroom.start_develop"),
        FIX(GlassPlateItem.FIX_TICKS, "chemical.old_glass_photograph.fixer", false, ""),
        /** 乾いた板。薬品は要らず、触れば素のガラス板へ戻る。 */
        DRIED(0, "", false, "");

        private final int durationTicks;
        private final String chemicalKey;
        private final boolean inDarkroomBox;
        private final String startMessageKey;

        Step(int durationTicks, String chemicalKey, boolean inDarkroomBox, String startMessageKey) {
            this.durationTicks = durationTicks;
            this.chemicalKey = chemicalKey;
            this.inDarkroomBox = inDarkroomBox;
            this.startMessageKey = startMessageKey;
        }

        public int durationTicks() {
            return durationTicks;
        }

        /** 文中でこの工程の薬品を指す名前。薬品を使わない段は空。 */
        public Component chemicalName() {
            return chemicalKey.isEmpty() ? Component.empty() : Component.translatable(chemicalKey);
        }

        /** Darkroom Table の中でしか進まない工程か。 */
        public boolean inDarkroomBox() {
            return inDarkroomBox;
        }

        /** 箱へ入れた瞬間に出す一言。箱の中で回らない工程は null。 */
        public @Nullable Component startMessage() {
            return startMessageKey.isEmpty() ? null : Component.translatable(startMessageKey);
        }

        public @Nullable Item chemical() {
            return switch (this) {
                case PREPARE -> OgpObjects.collodionKit();
                case DEVELOP -> OgpObjects.developer();
                case FIX -> OgpObjects.fixer();
                case DRIED -> null;
            };
        }

        /** 保存した名前から戻す。知らない名前・空文字は null（工程なし）。 */
        public static @Nullable Step byName(String name) {
            for (Step step : values()) {
                if (step.name().equals(name)) {
                    return step;
                }
            }
            return null;
        }
    }

    /**
     * 残り秒は 1 秒ごとに書き変わるので、これが無いと毎秒「別のアイテムに持ち替えた」と
     * 見なされて装備し直しのモーションが出る。26.x はコンポーネント側の
     * {@code ignoreSwapAnimation} で同じことをしている（この帯にその機構は無い）。
     *
     * <p>名前と tooltip は従来どおり毎秒更新されるので、残り秒の表示は消えない。
     */
    @Override
    public boolean allowNbtUpdateAnimation(Player player, InteractionHand hand,
                                           ItemStack oldStack, ItemStack newStack) {
        return false;
    }
}

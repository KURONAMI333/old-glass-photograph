package com.kuronami.oldglassphotograph.item;

import com.kuronami.oldglassphotograph.OgpRegistry;
import com.kuronami.oldglassphotograph.block.DarkroomTableBlock;
import com.kuronami.oldglassphotograph.capture.PhotoDeveloper;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Glass Plate。1 枚が 1 スタック（{@code stacksTo(1)}）で、工程状態と潜像を data component に持つ。
 *
 * <p>カスタム GUI は作らない。<b>暗所を要する塗布と現像は {@link DarkroomTableBlock} の中で回り</b>、
 * 箱を開けて板を入れ、蓋を閉じると工程が始まる（{@code MODJAM_DECISIONS_OGP.md} §30・B-2）。
 * 暗いのは箱の中なので、周りの明るさは工程に一切効かない。
 *
 * <p>手に持ったまま長押しするのは<b>定着だけ</b>。史実でも定着は暗室を出てから行える。
 *
 * <ol>
 *   <li>素のガラス板 + Collodion Kit を<b>Darkroom Table へ</b> → 4 秒 → 濡れた感光板（60 秒で乾く）</li>
 *   <li>カメラへ装填して露光（{@code WetPlateCameraBlock}）</li>
 *   <li>露光済み + Developer を<b>Darkroom Table へ</b> → 4 秒 → 像が確定し期限が止まる</li>
 *   <li>現像済み + Fixer を手に持って長押し → 6 秒 → 写真</li>
 * </ol>
 *
 * <p>薬品が無い・段が違う場合は<b>何も消費せず</b>理由だけを出す。
 */
public class GlassPlateItem extends Item {

    /** 銀浴から乾くまで。kura 受理済み = 60 秒。 */
    public static final int WET_TICKS = 1200;

    /** 板の準備（洗浄 + コロジオン + 銀浴をまとめた 1 操作）。 */
    public static final int PREPARE_TICKS = 80;

    /** 現像液。 */
    public static final int DEVELOP_TICKS = 80;

    /** 定着。 */
    public static final int FIX_TICKS = 120;

    public GlassPlateItem(Properties properties) {
        super(properties);
    }

    public static boolean isExposed(ItemStack stack) {
        return stack.has(OgpDataComponents.LATENT_IMAGE.get());
    }

    public static @Nullable PlateProcess process(ItemStack stack) {
        return stack.get(OgpDataComponents.PLATE_PROCESS.get());
    }

    /** カメラに入れてよい板か（濡れた感光板で、まだ潜像を持っていない）。 */
    public static boolean isReadyToLoad(ItemStack stack) {
        PlateProcess p = process(stack);
        return p != null && p.stage() == PlateProcess.Stage.SENSITIZED && !isExposed(stack);
    }

    /**
     * 乾いていたら板を素のガラス板へ戻す。<b>板そのものは失われない</b>（受理済みの非破壊原則）。
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
        stack.remove(OgpDataComponents.PLATE_PROCESS.get());
        stack.remove(OgpDataComponents.LATENT_IMAGE.get());
        stack.remove(OgpDataComponents.PLATE_FOG.get());
        return true;
    }

    // ------------------------------------------------------------------ 操作

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return begin(level, player, hand);
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
        if (!hasChemical(player, step)) {
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
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.BRUSH;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        Step step = nextStep(stack, user.level().getGameTime());
        return step == null || step == Step.DRIED || step.inDarkroomBox() ? 0 : step.durationTicks();
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
        if (!consumeChemical(player, step)) {
            return stack;
        }
        if (PhotoDeveloper.develop(player, stack)) {
            say(player, Component.translatable("message.old_glass_photograph.plate.fixed"));
        }
        return stack;
    }

    /**
     * Darkroom Table の中で終わった工程の結果を板へ書く。
     * 呼ぶのは {@code DarkroomTableBlockEntity} だけで、薬品の消費は投入時に済んでいる。
     */
    public static void applyDarkroomResult(ItemStack plate, Step step, long gameTime) {
        switch (step) {
            case PREPARE -> plate.set(OgpDataComponents.PLATE_PROCESS.get(), new PlateProcess(
                    PlateProcess.Stage.SENSITIZED, gameTime + WET_TICKS, WET_TICKS / 20));
            case DEVELOP -> plate.set(OgpDataComponents.PLATE_PROCESS.get(),
                    new PlateProcess(PlateProcess.Stage.DEVELOPED, 0L, 0));
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ 乾燥

    /**
     * 1 秒ごとに残り秒を書き直し、乾いたら素のガラス板へ戻す。
     *
     * <p>26.2 の {@code inventoryTick} は {@link ServerLevel} を受けるので server 専用。
     * client には component の同期で届く。
     */
    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, @Nullable EquipmentSlot slot) {
        PlateProcess p = process(stack);
        if (p == null || !p.isWet()) {
            return;
        }
        long gameTime = level.getGameTime();
        if (p.isDriedAt(gameTime)) {
            resolveDryOut(stack, gameTime);
            if (owner instanceof ServerPlayer player) {
                say(player, Component.translatable("message.old_glass_photograph.plate.dried"));
            }
            return;
        }
        int seconds = (int) Math.max(0, (p.wetUntil() - gameTime + 19) / 20);
        if (seconds != p.secondsLeft()) {
            stack.set(OgpDataComponents.PLATE_PROCESS.get(), p.withSecondsLeft(seconds));
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
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> adder, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, adder, flag);
        PlateProcess p = process(stack);
        if (p == null) {
            adder.accept(line("tooltip.old_glass_photograph.plate.blank"));
            fogged(stack, adder);
            return;
        }
        switch (p.stage()) {
            case SENSITIZED -> {
                adder.accept(line("tooltip.old_glass_photograph.plate.wet"));
                adder.accept(wetness(p));
            }
            case EXPOSED -> {
                adder.accept(line("tooltip.old_glass_photograph.plate.exposed"));
                adder.accept(wetness(p));
            }
            case DEVELOPED -> adder.accept(line("tooltip.old_glass_photograph.plate.developed"));
        }
        fogged(stack, adder);
    }

    /** かぶりが乗っている板だけ 1 行足す。0 の板には何も出さない。 */
    private static void fogged(ItemStack stack, Consumer<Component> adder) {
        if (stack.getOrDefault(OgpDataComponents.PLATE_FOG.get(), 0) > 0) {
            adder.accept(Component.translatable("tooltip.old_glass_photograph.plate.fogged")
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

    public static boolean hasChemical(Player player, Step step) {
        Item chemical = step.chemical();
        return chemical != null && player.getInventory().contains(s -> s.is(chemical));
    }

    public static boolean consumeChemical(Player player, Step step) {
        Item chemical = step.chemical();
        if (chemical == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.is(chemical)) {
                slot.shrink(1);
                return true;
            }
        }
        return false;
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
                case PREPARE -> OgpRegistry.COLLODION_KIT.get();
                case DEVELOP -> OgpRegistry.DEVELOPER.get();
                case FIX -> OgpRegistry.FIXER.get();
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
}

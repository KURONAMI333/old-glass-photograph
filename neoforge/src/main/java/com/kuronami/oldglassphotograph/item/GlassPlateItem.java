package com.kuronami.oldglassphotograph.item;

import com.kuronami.oldglassphotograph.OgpRegistry;
import com.kuronami.oldglassphotograph.block.DarkroomTableBlock;
import com.kuronami.oldglassphotograph.capture.PhotoDeveloper;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.component.PlateProcess;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
 * <p><b>操作は「板を手に持って右クリック長押し」の 1 つだけ</b>。何が起きるかは板の状態が決める。
 * カスタム GUI は作らず、工程の進行は板そのものへ畳んである。
 * <b>暗所を要する工程は Darkroom Table の上でしか進まない</b>（{@link DarkroomTableBlock}）。
 *
 * <ol>
 *   <li>素のガラス板 + Collodion Kit を<b>暗い Darkroom Table で</b> → 4 秒 → 濡れた感光板（60 秒で乾く）</li>
 *   <li>カメラへ装填して露光（{@code WetPlateCameraBlock}）</li>
 *   <li>露光済み + Developer を<b>暗い Darkroom Table で</b> → 4 秒 → 像が確定し期限が止まる</li>
 *   <li>現像済み + Fixer → 6 秒 → 写真（locked filled map）。定着は暗室の外でよい</li>
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
     * 露光済みなら 1 回の撮影機会だけ）。
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
        return true;
    }

    // ------------------------------------------------------------------ 操作

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return begin(level, player, hand, null);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        // 地面や壁を見たまま右クリックしても同じ工程が進むようにする（空中限定にしない）。
        // 暗室が要る工程だけ、押した先が Darkroom Table かどうかを見る。
        return begin(context.getLevel(), player, context.getHand(), context.getClickedPos());
    }

    /**
     * @param clickedPos 右クリックした先のブロック。空中なら null。
     *                   暗室が要る工程はここが Darkroom Table でなければ始まらない
     */
    private InteractionResult begin(Level level, Player player, InteractionHand hand,
                                    @Nullable BlockPos clickedPos) {
        ItemStack stack = player.getItemInHand(hand);
        Step step = nextStep(stack, level.getGameTime());
        if (step == Step.DRIED) {
            if (!level.isClientSide()) {
                resolveDryOut(stack, level.getGameTime());
                say(player, "The collodion dried out. The plate is clean again.");
            }
            return InteractionResult.SUCCESS;
        }
        if (step == null) {
            if (!level.isClientSide()) {
                say(player, "Load this plate into a Wet Plate Camera and expose it.");
            }
            return InteractionResult.FAIL;
        }
        if (step.needsDarkroom) {
            String refusal = darkroomRefusal(level, clickedPos, step);
            if (refusal != null) {
                if (!level.isClientSide()) {
                    say(player, refusal);
                }
                return InteractionResult.FAIL;
            }
        }
        if (!hasChemical(player, step)) {
            if (!level.isClientSide()) {
                say(player, "You need " + step.chemicalName() + ".");
            }
            return InteractionResult.FAIL;
        }
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    /**
     * 暗室の条件を満たさない理由。満たしていれば null。
     *
     * <p><b>黙って何も起きない状態にしない</b>（{@code MODJAM_DECISIONS_OGP.md} §10）。
     * 台に触っていないのか、台はあるが明るすぎるのかを言い分ける。
     */
    private static @Nullable String darkroomRefusal(Level level, @Nullable BlockPos clickedPos, Step step) {
        if (clickedPos == null || !DarkroomTableBlock.isDarkroomTable(level, clickedPos)) {
            return step.darkroomVerb + " the plate on a Darkroom Table.";
        }
        int light = DarkroomTableBlock.lightReaching(level, clickedPos);
        if (light > DarkroomTableBlock.MAX_LIGHT) {
            return "Too much light here (light " + light + "). The darkroom needs "
                    + DarkroomTableBlock.MAX_LIGHT + " or less - block the light out.";
        }
        return null;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.BRUSH;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        Step step = nextStep(stack, user.level().getGameTime());
        return step == null || step == Step.DRIED ? 0 : step.durationTicks;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (level.isClientSide() || !(entity instanceof ServerPlayer player)) {
            return stack;
        }
        Step step = nextStep(stack, level.getGameTime());
        if (step == null || step == Step.DRIED) {
            return stack;
        }
        // 押し始めてから歩き去った・明かりが置かれた場合に gate が素通りしないよう、
        // 完了時にもう一度見る（ここは clicked pos を持たないので周囲を探す）。
        if (step.needsDarkroom && DarkroomTableBlock.findUsable(level, player) == null) {
            int nearby = DarkroomTableBlock.bestLightNearby(level, player);
            say(player, nearby < 0
                    ? "You stepped away from the Darkroom Table. Nothing happened."
                    : "Light got in (light " + nearby + "). The plate is untouched.");
            return stack;
        }
        if (!consumeChemical(player, step)) {
            return stack;
        }
        switch (step) {
            case PREPARE -> {
                stack.set(OgpDataComponents.PLATE_PROCESS.get(), new PlateProcess(
                        PlateProcess.Stage.SENSITIZED, level.getGameTime() + WET_TICKS, WET_TICKS / 20));
                say(player, "Coated and sensitized. " + (WET_TICKS / 20) + "s before it dries.");
            }
            case DEVELOP -> {
                stack.set(OgpDataComponents.PLATE_PROCESS.get(),
                        new PlateProcess(PlateProcess.Stage.DEVELOPED, 0L, 0));
                say(player, "Developed. The image is fixed in time - now fix it in the plate.");
            }
            case FIX -> {
                if (PhotoDeveloper.develop(player, stack)) {
                    say(player, "Fixed. The photograph is finished.");
                }
            }
            default -> {
            }
        }
        return stack;
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
                say(player, "The collodion dried out. The plate is clean again.");
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
            return Component.literal("Glass Plate");
        }
        return switch (p.stage()) {
            case SENSITIZED -> Component.literal("Wet Plate (" + p.secondsLeft() + "s)");
            case EXPOSED -> Component.literal("Exposed Plate (" + p.secondsLeft() + "s)");
            case DEVELOPED -> Component.literal("Developed Plate");
        };
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> adder, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, adder, flag);
        PlateProcess p = process(stack);
        if (p == null) {
            adder.accept(line("Hold right-click on a dark Darkroom Table with a Collodion Kit."));
            return;
        }
        switch (p.stage()) {
            case SENSITIZED -> {
                adder.accept(line("Wet. Load it into a Wet Plate Camera."));
                adder.accept(wetness(p));
            }
            case EXPOSED -> {
                adder.accept(line("Latent image. Hold right-click on a dark Darkroom Table with Developer."));
                adder.accept(wetness(p));
            }
            case DEVELOPED -> adder.accept(line("Hold right-click with Fixer to finish the photograph."));
        }
    }

    private static Component wetness(PlateProcess p) {
        int seconds = p.secondsLeft();
        ChatFormatting color = seconds <= 10 ? ChatFormatting.RED
                : seconds <= 25 ? ChatFormatting.GOLD : ChatFormatting.AQUA;
        return Component.literal("Wet for " + seconds + "s").withStyle(color);
    }

    private static Component line(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    // ------------------------------------------------------------------ 工程

    /** いま板に対してできること。null = 薬品ではどうにもならない段（露光待ち）。 */
    private static @Nullable Step nextStep(ItemStack stack, long gameTime) {
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

    private static boolean hasChemical(Player player, Step step) {
        Item chemical = step.chemical();
        return chemical != null && player.getInventory().contains(s -> s.is(chemical));
    }

    private static boolean consumeChemical(Player player, Step step) {
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

    private static void say(Player player, String text) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.literal(text), true);
        }
    }

    /**
     * 板に対してできること。{@code needsDarkroom} は史実の暗室工程に対応する
     * （洗浄・コロジオン・銀浴と現像は暗所。定着は暗室を出てから行える）。
     */
    private enum Step {
        PREPARE(GlassPlateItem.PREPARE_TICKS, "a Collodion Kit", true, "Coat"),
        DEVELOP(GlassPlateItem.DEVELOP_TICKS, "Developer", true, "Develop"),
        FIX(GlassPlateItem.FIX_TICKS, "Fixer", false, ""),
        /** 乾いた板。薬品は要らず、触れば素のガラス板へ戻る。 */
        DRIED(0, "", false, "");

        private final int durationTicks;
        private final String chemicalName;
        private final boolean needsDarkroom;
        private final String darkroomVerb;

        Step(int durationTicks, String chemicalName, boolean needsDarkroom, String darkroomVerb) {
            this.durationTicks = durationTicks;
            this.chemicalName = chemicalName;
            this.needsDarkroom = needsDarkroom;
            this.darkroomVerb = darkroomVerb;
        }

        String chemicalName() {
            return chemicalName;
        }

        @Nullable Item chemical() {
            return switch (this) {
                case PREPARE -> OgpRegistry.COLLODION_KIT.get();
                case DEVELOP -> OgpRegistry.DEVELOPER.get();
                case FIX -> OgpRegistry.FIXER.get();
                case DRIED -> null;
            };
        }
    }
}

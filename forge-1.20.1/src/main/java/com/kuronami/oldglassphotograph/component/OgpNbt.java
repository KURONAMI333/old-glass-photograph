package com.kuronami.oldglassphotograph.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 1.20.1 帯の「data component の代わり」。OGP が ItemStack へ載せる状態を
 * <b>vanilla filled_map の NBT 規約に合わせた自前タグ</b>で持つ。
 *
 * <p>なぜこの形か（26.x / 1.21.1 の {@code DataComponentType} に対する置き換えの根拠）:
 * <ul>
 *   <li>1.20.5 未満には DataComponents API 自体が無い（調査 §4 実測・LOADERS.md 正本
 *       「1.20.1 は NBT-based」）。NBT はインベントリ同期ごと client へ全体が届くので、
 *       残り秒や潜像の同期の意味論は component 時代と同じ。</li>
 *   <li><b>写真の map id は vanilla のキー名 {@code map}（int）で書く</b>。
 *       {@code MapItem.getMapId(stack)} と {@code ItemFrame.getFramedMapId()} が
 *       このタグを直接読む（1.20.1 jar bytecode 実測）ため、額縁の像描画・
 *       手持ちの画素同期（{@code MapItem.inventoryTick} → {@code tickCarriedBy}）が
 *       mod コードなしで成立する。26.x セルが {@code DataComponents.MAP_ID} で
 *       得ていたのと同じ接続である。</li>
 *   <li>板は {@link #PROCESS} / {@link #LATENT} / {@link #FOG} の 3 タグ。
 *       「工程に入った板を重ねさせない」は {@link #UID}（乱数 long）で行う:
 *       NBT が異なるスタックは重ならない（{@code isSameItemSameTags}）ので、
 *       26.x の per-stack {@code MAX_STACK_SIZE} と同じ結果になる。</li>
 * </ul>
 */
public final class OgpNbt {

    /** 写真の像。{@link PhotoImage} を丸ごと持つ。 */
    public static final String PHOTO = "OgpPhoto";

    /**
     * 0.1.2 までの写真の像（map id）。vanilla filled_map と同じキー名・同じ型。
     *
     * <p>0.1.3 からの写真は {@link #PHOTO} に像そのものを持つ。既存のワールドに残っている
     * 写真を壊さないため、読む側だけこのキーも見る（新しく書くことはない）。
     */
    public static final String MAP_ID = "map";

    /** 板の工程状態。 */
    public static final String PROCESS = "OgpProcess";

    /** 板の潜像（16KB の pixel を含む）。 */
    public static final String LATENT = "OgpLatent";

    /** 暗室台の蓋開放で溜まったかぶり（tick）。 */
    public static final String FOG = "OgpFog";

    /** 工程に入った板の個体識別子。スタックの重なりを防ぐだけの値。 */
    public static final String UID = "OgpUid";

    /** 写真の撮影者・日付。 */
    public static final String CREDIT = "OgpCredit";

    private OgpNbt() {
    }

    // ------------------------------------------------------------------ 板

    public static void setProcess(ItemStack stack, @Nullable PlateProcess process) {
        if (process == null) {
            removeTag(stack, PROCESS);
        } else {
            stack.getOrCreateTag().put(PROCESS, process.save(new CompoundTag()));
        }
    }

    public static @Nullable PlateProcess process(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(PROCESS, 10)) {
            return null;
        }
        return PlateProcess.load(tag.getCompound(PROCESS));
    }

    public static void setLatent(ItemStack stack, @Nullable LatentImage latent) {
        if (latent == null) {
            removeTag(stack, LATENT);
        } else {
            stack.getOrCreateTag().put(LATENT, latent.save(new CompoundTag()));
        }
    }

    public static @Nullable LatentImage latent(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(LATENT, 10)) {
            return null;
        }
        return LatentImage.load(tag.getCompound(LATENT));
    }

    public static boolean isExposed(ItemStack stack) {
        return latent(stack) != null;
    }

    public static int fog(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(FOG, net.minecraft.nbt.Tag.TAG_ANY_NUMERIC)
                ? tag.getInt(FOG) : 0;
    }

    public static void setFog(ItemStack stack, int fog) {
        if (fog <= 0) {
            removeTag(stack, FOG);
        } else {
            stack.getOrCreateTag().putInt(FOG, fog);
        }
    }

    private static final java.util.Random UID_RANDOM = new java.util.Random();

    /**
     * 工程に入った板に個体識別子を与える。NBT が変わるので以後どの板とも重ならない
     * （26.x の per-stack {@code MAX_STACK_SIZE=1} 相当。NBT が異なるスタックは
     * {@code isSameItemSameTags} で重ならない＝1.20.1 流の定石）。
     */
    public static void markSingle(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.contains(UID, net.minecraft.nbt.Tag.TAG_ANY_NUMERIC)) {
            tag.putLong(UID, UID_RANDOM.nextLong());
        }
    }

    /** 工程に入った板か（＝個体タグを持つか）。素の板へ戻す時に使う。 */
    public static boolean hasAnyPlateState(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return false;
        }
        return tag.contains(PROCESS, 10) || tag.contains(LATENT, 10)
                || tag.contains(FOG, net.minecraft.nbt.Tag.TAG_ANY_NUMERIC);
    }

    /** 板を素のガラス板へ戻す。OGP 由来のタグを全部外し、重ねられる状態にもどす。 */
    public static void resetToBlank(ItemStack stack) {
        removeTag(stack, PROCESS);
        removeTag(stack, LATENT);
        removeTag(stack, FOG);
        removeTag(stack, UID);
    }

    // ------------------------------------------------------------------ 写真

    /** 像（map id）。無ければ null。vanilla {@code MapItem.getMapId} と同じキーを読む。 */
    public static @Nullable Integer mapId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(MAP_ID, net.minecraft.nbt.Tag.TAG_ANY_NUMERIC)) {
            return null;
        }
        return tag.getInt(MAP_ID);
    }

    /** 像。無ければ null（0.1.2 までの写真か、まだ現像していない板）。 */
    public static @Nullable PhotoImage photo(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(PHOTO, 10)) {
            return null;
        }
        return PhotoImage.load(tag.getCompound(PHOTO));
    }

    public static void setPhoto(ItemStack stack, PhotoImage image) {
        stack.getOrCreateTag().put(PHOTO, image.save(new CompoundTag()));
    }

    public static void setCredit(ItemStack stack, @Nullable PhotoCredit credit) {
        if (credit == null) {
            removeTag(stack, CREDIT);
        } else {
            stack.getOrCreateTag().put(CREDIT, credit.save(new CompoundTag()));
        }
    }

    public static @Nullable PhotoCredit credit(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(CREDIT, 10)) {
            return null;
        }
        return PhotoCredit.load(tag.getCompound(CREDIT));
    }

    // ------------------------------------------------------------------ 下回り

    private static void removeTag(ItemStack stack, String key) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(key);
            if (tag.isEmpty()) {
                // 空になった compound は外す。素の板/写真と完全に同じ直列化へ戻す。
                stack.setTag(null);
            }
        }
    }
}

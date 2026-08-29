package com.kuronami.oldglassphotograph.menu;

import com.kuronami.oldglassphotograph.item.PhotographItem;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 製図台の地図スロットに写真を入れさせない。
 *
 * <p><b>なぜ要るか</b>: 製図台の左上スロットは
 * {@code itemStack.has(DataComponents.MAP_ID)} だけで受け入れを決める
 * （{@code MC: net/minecraft/world/inventory/CartographyTableMenu.java:51-53}）。
 * 写真は map の保存・同期をそのまま借りるので {@code MAP_ID} を持っており、そのまま入る。
 * 入ってしまうと空の地図と合わせた枝
 * （同 {@code :125-131} の {@code mapStack.copyWithCount(2)}）に落ちて<b>写真が複製される</b>。
 * 紙とガラス板の枝は写真の map が {@code locked} なので既に止まっている（同 {@code :116} / {@code :120}）が、
 * 複製の枝には {@code locked} の検査が無い。
 * {@link PhotographItem#onCraftedPostProcess} の握り潰しもこの枝は通らない
 * （複製は {@code MAP_POST_PROCESSING} を付けない）。
 *
 * <p><b>なぜ {@code MAP_ID} を外して逃げないか</b>: id は写真の描画と同期の一次情報で、
 * 自前の component へ移すと次の4箇所が同時に壊れる。どれも item 側の override が届かない。
 * <ul>
 *   <li>{@code MC: net/minecraft/server/level/ServerPlayer.java:770-773} — 手持ちの写真の同期。
 *       {@code itemStack.get(MAP_ID)} をそのまま {@code getUpdatePacket(id, ...)} へ渡す</li>
 *   <li>{@code MC: net/minecraft/server/level/ServerEntity.java:102-107} — 額縁の写真の同期。同上</li>
 *   <li>{@code MC: net/minecraft/world/entity/decoration/ItemFrame.java:262-268} —
 *       {@code getFramedMapId} / {@code hasFramedMap} は entity 側で component を直に読む。
 *       item ごとに差し替えられない（当たり判定の 1.0F/0.75F もここで決まる）</li>
 *   <li>{@code MC: net/minecraft/client/renderer/entity/ItemFrameRenderer.java:133-139} —
 *       {@code getFramedMapId} が null だと {@code state.mapId} が立たず、額縁の像が出ない</li>
 * </ul>
 * {@code MapItem.getSavedData(ItemStack, Level)} は NeoForge が
 * {@code getCustomMapData} へ委譲するよう手を入れている
 * （{@code MC: net/minecraft/world/item/MapItem.java:54-61}）が、これは<b>データの引き当てだけ</b>で、
 * packet と描画が使う <b>id そのもの</b>は素通しのまま。
 *
 * <p><b>なぜスロットの差し替えか</b>: 製図台スロットへの書き込みは全て
 * {@link Slot#mayPlace} を通る。
 * {@code MC: net/minecraft/world/inventory/AbstractContainerMenu.java} の
 * {@code :355} / {@code :381}（ドラッグ配分）、{@code :451}（通常クリック）、
 * {@code :484} / {@code :493}（swap・ホットバーキー）、
 * {@code :687}（{@code moveItemStackTo} = shift クリック）がその全量で、
 * {@code slotsChanged:99} が生で読む {@code container.getItem(0)} は
 * この6箇所の結果でしかない。
 * よって {@code mayPlace} を1つ塞げば経路は閉じる。component も item クラスも触らないので、
 * 額縁・一人称・インベントリ・地面の描画、{@code GuiItemAtlas} のキャッシュ鍵、
 * dedicated server を跨いだ保存に手が入らない。
 */
public final class CartographyPhotographGuard {

    private CartographyPhotographGuard() {
    }

    /**
     * 製図台 menu の地図スロットを、写真を弾く版へ差し替える。
     *
     * <p>client の menu は別インスタンスなので、client 側からも同じ menu 種別に対して呼ぶ。
     * 片側だけだと client が置けたと予測して server が撥ね返す＝アイテムが一瞬跳ねる。
     */
    public static void apply(AbstractContainerMenu menu) {
        if (!(menu instanceof CartographyTableMenu) || menu.slots.isEmpty()) {
            return;
        }
        Slot original = menu.slots.get(0);
        if (original instanceof PhotographRejectingSlot) {
            // client の Screen#init は画面サイズ変更のたびに走る。二重に包まない。
            return;
        }
        PhotographRejectingSlot guarded = new PhotographRejectingSlot(original);
        guarded.index = original.index;
        menu.slots.set(0, guarded);
    }

    /**
     * 元のスロットへ委譲した上で写真だけを落とす。
     *
     * <p>vanilla の左上スロットは {@code mayPlace} しか上書きしていない
     * （{@code MC: CartographyTableMenu.java:49-54}）ので、残りは素の {@link Slot} の挙動と一致する。
     * 判定を自前で書き直さず委譲するのは、他 MOD が同じスロットに手を入れていた場合にそれを残すため。
     */
    private static final class PhotographRejectingSlot extends Slot {
        private final Slot original;

        private PhotographRejectingSlot(Slot original) {
            super(original.container, original.getContainerSlot(), original.x, original.y);
            this.original = original;
        }

        @Override
        public boolean mayPlace(ItemStack itemStack) {
            return !(itemStack.getItem() instanceof PhotographItem) && this.original.mayPlace(itemStack);
        }
    }
}

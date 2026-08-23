package com.kuronami.oldglassphotograph.gametest;

import java.util.regex.Pattern;

import com.kuronami.oldglassphotograph.OgpRegistry;
import com.kuronami.oldglassphotograph.capture.PhotoDeveloper;
import com.kuronami.oldglassphotograph.component.LatentImage;
import com.kuronami.oldglassphotograph.component.OgpDataComponents;
import com.kuronami.oldglassphotograph.component.PhotoCredit;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * commit 3fc8524 の検証: 現像の瞬間に実世界の日時（{@code PhotoCredit#capturedAt}）が写真へ
 * 書き込まれること。値そのものは実行時刻に依存するので固定値とは比較せず、
 * 「その成分が存在し、{@code yyyy-MM-dd HH:mm} 形式で、空でない」ことだけを見る
 * （{@code PhotoCredit.captureTimestamp()} の書式）。
 */
public final class OgpPhotoCreditGameTests {

    private static final BlockPos POS = new BlockPos(1, 1, 1);

    /** {@code PhotoCredit.TIMESTAMP_FORMAT}（"yyyy-MM-dd HH:mm"）と同じ書式。 */
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$");

    private OgpPhotoCreditGameTests() {
    }

    private static void failAt(GameTestHelper helper, String message) {
        helper.fail(Component.literal(message), POS);
    }

    public static void developedPhotoHasRealWorldCapturedAtTimestamp(GameTestHelper helper) {
        ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.CREATIVE);

        ItemStack plate = new ItemStack(OgpRegistry.GLASS_PLATE.get());
        byte[] pixels = new byte[LatentImage.SIZE];
        // 全灰色(128)の潜像。ExposureModel が扱える値であればよく、痕跡合成の内容は対象外。
        java.util.Arrays.fill(pixels, (byte) 128);
        plate.set(OgpDataComponents.LATENT_IMAGE.get(), new LatentImage(pixels, 0, 0));

        boolean developed = PhotoDeveloper.develop(player, plate);
        if (!developed) {
            failAt(helper, "PhotoDeveloper.develop returned false for a plate with a valid latent image");
            return;
        }

        ItemStack photo = null;
        for (ItemStack candidate : player.getInventory().getNonEquipmentItems()) {
            if (!candidate.isEmpty() && candidate.is(OgpRegistry.PHOTOGRAPH.get())) {
                photo = candidate;
                break;
            }
        }
        if (photo == null) {
            failAt(helper, "no photograph item was added to the player's inventory after develop()");
            return;
        }

        PhotoCredit credit = photo.get(OgpDataComponents.PHOTO_CREDIT.get());
        if (credit == null) {
            failAt(helper, "developed photograph has no PHOTO_CREDIT component");
            return;
        }
        String capturedAt = credit.capturedAt();
        if (capturedAt == null || capturedAt.isEmpty()) {
            failAt(helper, "PhotoCredit.capturedAt() is empty on a freshly developed photograph");
            return;
        }
        if (!TIMESTAMP_PATTERN.matcher(capturedAt).matches()) {
            failAt(helper, "PhotoCredit.capturedAt() = \"" + capturedAt + "\" does not match yyyy-MM-dd HH:mm");
            return;
        }
        helper.succeed();
    }
}

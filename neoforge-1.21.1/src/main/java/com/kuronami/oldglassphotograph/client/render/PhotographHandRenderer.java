package com.kuronami.oldglassphotograph.client.render;

import com.kuronami.oldglassphotograph.item.PhotographItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * 一人称の手持ちを横取りして、写真を<b>地図と同じ構えで正面に</b>掲げる。
 *
 * <p><b>なぜ横取りが要るか</b>: {@code ItemInHandRenderer#renderArmWithItem} は
 * {@code itemStack.has(DataComponents.MAP_ID)} で地図の描画へ分岐する。
 * 写真は map の保存・同期をそのまま借りるために map id を持つので、何もしないと
 * {@code renderMap} が紙地図の背景（{@code MAP_BACKGROUND_CHECKERBOARD}）を敷いてしまう。
 * <b>表面が map だと分かってはいけない</b>ので、その 1 枚だけを外す必要がある。
 *
 * <p>NeoForge は一人称の手ごとの描画（{@code ItemInHandRenderer.submitArmWithItem} 相当）で
 * {@code RenderHandEvent} を発火する。ここはその cancel 先として vanilla
 * {@code renderTwoHandedMap} / {@code renderOneHandedMap} / {@code renderMap}
 * （1.21.1 実装・{@code ItemInHandRenderer.java:174-246}）の写しを置く。
 *
 * <h2>姿勢</h2>
 *
 * 要件は「完成した写真を持つ時、地図みたいに正面から見れるような持ち方にしよう」
 * （2026-08-23 実機）。<b>vanilla の地図の姿勢をそのまま借りて、中身だけ写真に差し替える。</b>
 *
 * <ul>
 *   <li>主手に持ちオフハンドが空 → {@code renderTwoHandedMap}。両手で正面に構え、上を向くほど寝る</li>
 *   <li>それ以外 → {@code renderOneHandedMap}。片手で斜めに持つ</li>
 * </ul>
 */
public final class PhotographHandRenderer {

    private PhotographHandRenderer() {
    }

    /**
     * 写真の一人称描画を引き受ける。引き受けた caller は vanilla の該当手の描画を飛ばすこと。
     *
     * <p>引数は {@code RenderHandEvent} の getter と一対一に対応する
     * （swingProgress = attack / equipProgress = inverseArmHeight / interpolatedPitch = xRot）。
     *
     * @return 描画を引き受けたか（true なら vanilla の経路は cancel 済みとして扱う）
     */
    public static boolean trySubmit(AbstractClientPlayer player, float partialTick, float interpolatedPitch,
                                    InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
                                    PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (!(stack.getItem() instanceof PhotographItem)) {
            return false;
        }
        if (player == null) {
            return false;
        }
        // ここから先は vanilla の代わりを務めるので、描かない場合も必ず引き返らない。
        if (player.isScoping()) {
            // vanilla の同じ判定は迂回した先（renderArmWithItem の spyglass 分岐）にあるので、こちらで持つ。
            return true;
        }

        boolean mainHand = hand == InteractionHand.MAIN_HAND;
        int light = packedLight;
        float attack = swingProgress;
        float inverseArmHeight = equipProgress;

        poseStack.pushPose();
        if (mainHand && player.getOffhandItem().isEmpty()) {
            twoHanded(poseStack, bufferSource, light, interpolatedPitch, inverseArmHeight,
                    attack, player, stack);
        } else {
            HumanoidArm arm = mainHand ? player.getMainArm() : player.getMainArm().getOpposite();
            oneHanded(poseStack, bufferSource, light, inverseArmHeight, arm, attack, player, stack);
        }
        poseStack.popPose();
        return true;
    }

    // ------------------------------------------------------------------ 姿勢

    /**
     * 両手で正面に構える。{@code ItemInHandRenderer#renderTwoHandedMap} の写し
     * （{@code MC: .../ItemInHandRenderer.java:200-224}）。
     *
     * <p>{@code mapTilt} は視線の上下で 0..1 に動き、上を向くほど板が寝る。
     * 導出せずに vanilla の式をそのまま使う。
     */
    private static void twoHanded(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                                  float xRot, float inverseArmHeight, float attack,
                                  AbstractClientPlayer player, ItemStack stack) {
        float sqrtAttack = Mth.sqrt(attack);
        float ySwing = -0.2F * Mth.sin(attack * (float) Math.PI);
        float zSwing = -0.4F * Mth.sin(sqrtAttack * (float) Math.PI);
        poseStack.translate(0.0F, -ySwing / 2.0F, zSwing);
        float mapTilt = mapTilt(xRot);
        poseStack.translate(0.0F, 0.04F + inverseArmHeight * -1.2F + mapTilt * -0.5F, -0.72F);
        poseStack.mulPose(Axis.XP.rotationDegrees(mapTilt * -85.0F));
        if (!player.isInvisible()) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            hand(poseStack, bufferSource, light, player, HumanoidArm.RIGHT);
            hand(poseStack, bufferSource, light, player, HumanoidArm.LEFT);
            poseStack.popPose();
        }

        float xzSwing = Mth.sin(sqrtAttack * (float) Math.PI);
        poseStack.mulPose(Axis.XP.rotationDegrees(xzSwing * 20.0F));
        poseStack.scale(2.0F, 2.0F, 2.0F);
        plate(poseStack, bufferSource, light, stack);
    }

    /**
     * 片手で持つ。{@code ItemInHandRenderer#renderOneHandedMap} の写し
     * （{@code MC: .../ItemInHandRenderer.java:174-198}）。
     *
     * <p>オフハンドに写真を持った時と、主手に持って反対の手が塞がっている時がここへ来る。
     * <b>左右は {@code invert} だけで切り替わる</b>ので、どちらの手でも同じ形になる。
     */
    private static void oneHanded(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                                  float inverseArmHeight, HumanoidArm arm, float attack,
                                  AbstractClientPlayer player, ItemStack stack) {
        float invert = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.translate(invert * 0.125F, -0.125F, 0.0F);
        if (!player.isInvisible()) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.ZP.rotationDegrees(invert * 10.0F));
            arm(poseStack, bufferSource, light, inverseArmHeight, attack, arm, player);
            poseStack.popPose();
        }

        poseStack.pushPose();
        poseStack.translate(invert * 0.51F, -0.08F + inverseArmHeight * -1.2F, -0.75F);
        float sqrtAttack = Mth.sqrt(attack);
        float xSwing = Mth.sin(sqrtAttack * (float) Math.PI);
        float xSwingPosition = -0.5F * xSwing;
        float ySwingPosition = 0.4F * Mth.sin(sqrtAttack * (float) (Math.PI * 2));
        float zSwingPosition = -0.3F * Mth.sin(attack * (float) Math.PI);
        poseStack.translate(invert * xSwingPosition, ySwingPosition - 0.3F * xSwing, zSwingPosition);
        poseStack.mulPose(Axis.XP.rotationDegrees(xSwing * -45.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(invert * xSwing * -30.0F));
        plate(poseStack, bufferSource, light, stack);
        poseStack.popPose();
    }

    /** 視線の上下から板の寝かせ具合を出す。{@code ItemInHandRenderer#calculateMapTilt} の写し。 */
    private static float mapTilt(float xRot) {
        float tilt = Mth.clamp(1.0F - xRot / 45.0F + 0.1F, 0.0F, 1.0F);
        return -Mth.cos(tilt * (float) Math.PI) * 0.5F + 0.5F;
    }

    /** 板を支える手。{@code ItemInHandRenderer#renderMapHand} の写し（同 {@code :157-172}）。 */
    private static void hand(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                             AbstractClientPlayer player, HumanoidArm arm) {
        PlayerRenderer renderer = playerRenderer(player);
        poseStack.pushPose();
        float invert = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.mulPose(Axis.YP.rotationDegrees(92.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(invert * -41.0F));
        poseStack.translate(invert * 0.3F, -1.1F, 0.45F);
        if (arm == HumanoidArm.RIGHT) {
            renderer.renderRightHand(poseStack, bufferSource, light, player);
        } else {
            renderer.renderLeftHand(poseStack, bufferSource, light, player);
        }
        poseStack.popPose();
    }

    /** 片手持ちの腕。{@code ItemInHandRenderer#renderPlayerArm} の写し（同 {@code :241-277}）。 */
    private static void arm(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                            float inverseArmHeight, float attack, HumanoidArm arm, AbstractClientPlayer player) {
        PlayerRenderer renderer = playerRenderer(player);
        boolean right = arm == HumanoidArm.RIGHT;
        float invert = right ? 1.0F : -1.0F;
        float sqrtAttack = Mth.sqrt(attack);
        float xSwingPosition = -0.3F * Mth.sin(sqrtAttack * (float) Math.PI);
        float ySwingPosition = 0.4F * Mth.sin(sqrtAttack * (float) (Math.PI * 2));
        float zSwingPosition = -0.4F * Mth.sin(attack * (float) Math.PI);
        poseStack.translate(invert * (xSwingPosition + 0.64000005F),
                ySwingPosition + -0.6F + inverseArmHeight * -0.6F,
                zSwingPosition + -0.71999997F);
        poseStack.mulPose(Axis.YP.rotationDegrees(invert * 45.0F));
        float xzSwing = Mth.sin(attack * attack * (float) Math.PI);
        float ySwing = Mth.sin(sqrtAttack * (float) Math.PI);
        poseStack.mulPose(Axis.YP.rotationDegrees(invert * ySwing * 70.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(invert * xzSwing * -20.0F));
        poseStack.translate(invert * -1.0F, 3.6F, 3.5F);
        poseStack.mulPose(Axis.ZP.rotationDegrees(invert * 120.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(200.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(invert * -135.0F));
        poseStack.translate(invert * 5.6F, 0.0F, 0.0F);
        if (right) {
            renderer.renderRightHand(poseStack, bufferSource, light, player);
        } else {
            renderer.renderLeftHand(poseStack, bufferSource, light, player);
        }
    }

    /** 1.21.1 の player renderer 取得。26.x の {@code getPlayerRenderer} は無いため getRenderer をキャストする。 */
    private static PlayerRenderer playerRenderer(AbstractClientPlayer player) {
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        return (PlayerRenderer) dispatcher.getRenderer(player);
    }

    // ------------------------------------------------------------------ 板

    /**
     * 写真そのもの。{@code renderMap} の座標系（{@code MC: .../ItemInHandRenderer.java:227-233}）を
     * そのまま通し、<b>紙地図の背景は敷かない</b>。
     *
     * <p>vanilla は {@code scale(1/128)} の後で 128 単位の板を出すが、ここは 0..1 の板を
     * 直に出す（同じ大きさで、余計な桁を経由しない）。
     *
     * <p>裏面も出す。{@code RenderType.text} は背面を落とすので、巻き方向を逆にした板を重ねる。
     */
    private static void plate(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                              ItemStack stack) {
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
        poseStack.scale(0.38F, 0.38F, 0.38F);
        poseStack.translate(-0.5F, -0.5F, 0.0F);

        VertexConsumer vc = bufferSource.getBuffer(RenderType.text(texture(stack)));
        var pose = poseStack.last().pose();
        vc.addVertex(pose, 0.0F, 1.0F, 0.0F).setColor(-1).setUv(0.0F, 1.0F).setLight(light);
        vc.addVertex(pose, 1.0F, 1.0F, 0.0F).setColor(-1).setUv(1.0F, 1.0F).setLight(light);
        vc.addVertex(pose, 1.0F, 0.0F, 0.0F).setColor(-1).setUv(1.0F, 0.0F).setLight(light);
        vc.addVertex(pose, 0.0F, 0.0F, 0.0F).setColor(-1).setUv(0.0F, 0.0F).setLight(light);
        vc.addVertex(pose, 0.0F, 0.0F, 0.0F).setColor(-1).setUv(0.0F, 0.0F).setLight(light);
        vc.addVertex(pose, 1.0F, 0.0F, 0.0F).setColor(-1).setUv(1.0F, 0.0F).setLight(light);
        vc.addVertex(pose, 1.0F, 1.0F, 0.0F).setColor(-1).setUv(1.0F, 1.0F).setLight(light);
        vc.addVertex(pose, 0.0F, 1.0F, 0.0F).setColor(-1).setUv(0.0F, 1.0F).setLight(light);
    }

    /** 像が client に届いていれば動的テクスチャ、まだなら BLANK。 */
    private static ResourceLocation texture(ItemStack stack) {
        MapId id = stack.get(DataComponents.MAP_ID);
        if (id == null) {
            return PlateTextures.BLANK;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        MapItemSavedData data = level == null ? null : level.getMapData(id);
        return PlateTextures.texture(id, data);
    }
}

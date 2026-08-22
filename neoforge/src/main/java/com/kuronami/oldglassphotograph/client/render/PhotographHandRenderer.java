package com.kuronami.oldglassphotograph.client.render;

import com.kuronami.oldglassphotograph.OldGlassPhotograph;
import com.kuronami.oldglassphotograph.item.PhotographItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 一人称の手持ちを横取りして、写真を<b>地図と同じ構えで正面に</b>掲げる。
 *
 * <p><b>なぜ横取りが要るか</b>: {@code ItemInHandRenderer#submitArmWithItem} は
 * {@code itemStack.has(DataComponents.MAP_ID)} で地図の描画へ分岐する
 * （{@code MC: net/minecraft/client/renderer/ItemInHandRenderer.java:433-439}）。
 * 写真は map の保存・同期をそのまま借りるために map id を持つので、何もしないと
 * {@code renderMap} が紙地図の背景（{@code MAP_BACKGROUND_CHECKERBOARD}）を敷いてしまう
 * （同 {@code :227-247}）。<b>表面が map だと分かってはいけない</b>
 * （{@code MODJAM_DECISIONS_OGP.md} §29）ので、その 1 枚だけを外す必要がある。
 *
 * <p>{@code IClientItemExtensions#applyForgeHandTransform} はこの分岐より後の {@code else} 側
 * にしか無く、map id を持つ stack には届かない。分岐そのものを迂回できる正規の入口は
 * {@code ClientHooks#renderSpecificFirstPersonHand} が投げる {@link RenderHandEvent} で、
 * これは手ごとに、分岐より前に来る
 * （NeoForge patched {@code ItemInHandRenderer.java:348}（主手） / {@code :367}（オフハンド））。
 *
 * <h2>姿勢</h2>
 *
 * 要件は「完成した写真を持つ時、地図みたいに正面から見れるような持ち方にしよう」
 * （2026-08-23 実機）。<b>vanilla の地図の姿勢をそのまま借りて、中身だけ写真に差し替える。</b>
 *
 * <ul>
 *   <li>主手に持ちオフハンドが空 → {@code renderTwoHandedMap}（同 {@code :203-225}）。
 *       両手で正面に構え、上を向くほど寝る</li>
 *   <li>それ以外 → {@code renderOneHandedMap}（同 {@code :171-201}）。片手で斜めに持つ</li>
 * </ul>
 *
 * <p>写真そのものは {@code renderMap} の座標系（同 {@code :227-233}）に
 * {@link PhotographSpecialRenderer} と同じ板を出す。<b>アイテムモデル経由にしない</b>のは、
 * {@code ItemDisplayContext} の変換が地図の姿勢の上に重なるとこの座標系が崩れるためで、
 * vanilla の地図も同じ理由でモデルを通さず板を直接出している。
 */
public final class PhotographHandRenderer {

    /**
     * 像がまだ client に届いていない時に貼る地。
     * {@code PhotographSpecialRenderer.BLANK} と同じ絵・同じ理由。
     */
    private static final Identifier BLANK =
            Identifier.fromNamespaceAndPath(OldGlassPhotograph.MODID, "textures/item/photograph.png");

    private PhotographHandRenderer() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(PhotographHandRenderer::onRenderHand);
    }

    private static void onRenderHand(RenderHandEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof PhotographItem)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        // ここから先は vanilla の代わりを務めるので、描かない場合も必ず cancel する。
        event.setCanceled(true);
        if (player.isScoping()) {
            // vanilla の同じ判定は迂回した先（同 :428）にあるので、こちらで持つ。
            return;
        }

        boolean mainHand = event.getHand() == InteractionHand.MAIN_HAND;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        int light = event.getPackedLight();
        // RenderHandEvent の getter 名は vanilla の引数名と揃っていない。
        // ClientHooks:255-256 の呼び出し（ItemInHandRenderer:348 / :367）では
        // swingProgress = attack、equipProgress = inverseArmHeight、interpolatedPitch = xRot。
        float attack = event.getSwingProgress();
        float inverseArmHeight = event.getEquipProgress();

        poseStack.pushPose();
        if (mainHand && player.getOffhandItem().isEmpty()) {
            twoHanded(poseStack, collector, light, event.getInterpolatedPitch(), inverseArmHeight,
                    attack, player, stack);
        } else {
            HumanoidArm arm = mainHand ? player.getMainArm() : player.getMainArm().getOpposite();
            oneHanded(poseStack, collector, light, inverseArmHeight, arm, attack, player, stack);
        }
        poseStack.popPose();
    }

    // ------------------------------------------------------------------ 姿勢

    /**
     * 両手で正面に構える。{@code ItemInHandRenderer#renderTwoHandedMap} の写し
     * （{@code MC: .../ItemInHandRenderer.java:203-225}）。
     *
     * <p>{@code mapTilt} は視線の上下で 0..1 に動き、上を向くほど板が寝る。
     * 導出せずに vanilla の式をそのまま使う。
     */
    private static void twoHanded(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                  float xRot, float inverseArmHeight, float attack,
                                  LocalPlayer player, ItemStack stack) {
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
            hand(poseStack, collector, light, player, HumanoidArm.RIGHT);
            hand(poseStack, collector, light, player, HumanoidArm.LEFT);
            poseStack.popPose();
        }

        float xzSwing = Mth.sin(sqrtAttack * (float) Math.PI);
        poseStack.mulPose(Axis.XP.rotationDegrees(xzSwing * 20.0F));
        poseStack.scale(2.0F, 2.0F, 2.0F);
        plate(poseStack, collector, light, stack);
    }

    /**
     * 片手で持つ。{@code ItemInHandRenderer#renderOneHandedMap} の写し
     * （{@code MC: .../ItemInHandRenderer.java:171-201}）。
     *
     * <p>オフハンドに写真を持った時と、主手に持って反対の手が塞がっている時がここへ来る。
     * <b>左右は {@code invert} だけで切り替わる</b>ので、どちらの手でも同じ形になる。
     */
    private static void oneHanded(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                  float inverseArmHeight, HumanoidArm arm, float attack,
                                  LocalPlayer player, ItemStack stack) {
        float invert = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.translate(invert * 0.125F, -0.125F, 0.0F);
        if (!player.isInvisible()) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.ZP.rotationDegrees(invert * 10.0F));
            arm(poseStack, collector, light, inverseArmHeight, attack, arm, player);
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
        plate(poseStack, collector, light, stack);
        poseStack.popPose();
    }

    /** 視線の上下から板の寝かせ具合を出す。{@code ItemInHandRenderer#calculateMapTilt} の写し（同 {@code :143-147}）。 */
    private static float mapTilt(float xRot) {
        float tilt = Mth.clamp(1.0F - xRot / 45.0F + 0.1F, 0.0F, 1.0F);
        return -Mth.cos(tilt * (float) Math.PI) * 0.5F + 0.5F;
    }

    /** 板を支える手。{@code ItemInHandRenderer#renderMapHand} の写し（同 {@code :149-169}）。 */
    private static void hand(PoseStack poseStack, SubmitNodeCollector collector, int light,
                             LocalPlayer player, HumanoidArm arm) {
        AvatarRenderer<AbstractClientPlayer> renderer =
                Minecraft.getInstance().getEntityRenderDispatcher().getPlayerRenderer(player);
        poseStack.pushPose();
        float invert = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.mulPose(Axis.YP.rotationDegrees(92.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(invert * -41.0F));
        poseStack.translate(invert * 0.3F, -1.1F, 0.45F);
        PlayerSkin skin = player.getSkin();
        if (arm == HumanoidArm.RIGHT) {
            renderer.renderRightHand(poseStack, collector, light, skin.body().texturePath(),
                    player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
        } else {
            renderer.renderLeftHand(poseStack, collector, light, skin.body().texturePath(),
                    player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
        }
        poseStack.popPose();
    }

    /** 片手持ちの腕。{@code ItemInHandRenderer#renderPlayerArm} の写し（同 {@code :249-277}）。 */
    private static void arm(PoseStack poseStack, SubmitNodeCollector collector, int light,
                            float inverseArmHeight, float attack, HumanoidArm arm, LocalPlayer player) {
        AvatarRenderer<AbstractClientPlayer> renderer =
                Minecraft.getInstance().getEntityRenderDispatcher().getPlayerRenderer(player);
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
        PlayerSkin skin = player.getSkin();
        poseStack.translate(invert * -1.0F, 3.6F, 3.5F);
        poseStack.mulPose(Axis.ZP.rotationDegrees(invert * 120.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(200.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(invert * -135.0F));
        poseStack.translate(invert * 5.6F, 0.0F, 0.0F);
        if (right) {
            renderer.renderRightHand(poseStack, collector, light, skin.body().texturePath(),
                    player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
        } else {
            renderer.renderLeftHand(poseStack, collector, light, skin.body().texturePath(),
                    player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
        }
    }

    // ------------------------------------------------------------------ 板

    /**
     * 写真そのもの。{@code renderMap} の座標系（{@code MC: .../ItemInHandRenderer.java:227-233}）を
     * そのまま通し、<b>紙地図の背景は敷かない</b>。
     *
     * <p>vanilla は {@code scale(1/128)} の後で 128 単位の板を出すが、ここは 0..1 の板を
     * 直に出す（同じ大きさで、余計な桁を経由しない）。頂点と UV の並びは
     * {@code MapRenderer#render}（{@code MC: .../MapRenderer.java:36-41}）の像の板と同じで、
     * {@code YP 180} と {@code ZP 180} が既に天地を返しているので v は反転させない。
     *
     * <p>裏面も出す。{@code RenderTypes.text} は背面を落とすので、
     * {@link PhotographSpecialRenderer} と同じく巻き方向を逆にした板を重ねる。
     */
    private static void plate(PoseStack poseStack, SubmitNodeCollector collector, int light,
                              ItemStack stack) {
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
        poseStack.scale(0.38F, 0.38F, 0.38F);
        poseStack.translate(-0.5F, -0.5F, 0.0F);

        RenderType renderType = RenderTypes.text(texture(stack));
        collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            buffer.addVertex(pose, 0.0F, 1.0F, 0.0F).setColor(-1).setUv(0.0F, 1.0F).setLight(light);
            buffer.addVertex(pose, 1.0F, 1.0F, 0.0F).setColor(-1).setUv(1.0F, 1.0F).setLight(light);
            buffer.addVertex(pose, 1.0F, 0.0F, 0.0F).setColor(-1).setUv(1.0F, 0.0F).setLight(light);
            buffer.addVertex(pose, 0.0F, 0.0F, 0.0F).setColor(-1).setUv(0.0F, 0.0F).setLight(light);
        });
        collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            buffer.addVertex(pose, 0.0F, 0.0F, 0.0F).setColor(-1).setUv(0.0F, 0.0F).setLight(light);
            buffer.addVertex(pose, 1.0F, 0.0F, 0.0F).setColor(-1).setUv(1.0F, 0.0F).setLight(light);
            buffer.addVertex(pose, 1.0F, 1.0F, 0.0F).setColor(-1).setUv(1.0F, 1.0F).setLight(light);
            buffer.addVertex(pose, 0.0F, 1.0F, 0.0F).setColor(-1).setUv(0.0F, 1.0F).setLight(light);
        });
    }

    /** 像が client に届いていれば動的テクスチャ、まだなら {@link #BLANK}。 */
    private static Identifier texture(ItemStack stack) {
        MapId id = stack.get(DataComponents.MAP_ID);
        if (id == null) {
            return BLANK;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        MapItemSavedData data = level == null ? null : level.getMapData(id);
        if (data == null) {
            return BLANK;
        }
        return minecraft.getMapTextureManager().prepareMapTexture(id, data);
    }
}

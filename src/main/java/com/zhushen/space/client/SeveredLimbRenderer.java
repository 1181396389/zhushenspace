package com.zhushen.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zhushen.space.entity.dismember.BodyPart;
import com.zhushen.space.entity.dismember.SeveredLimb;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 断肢渲染：
 * <ul>
 *   <li>丧尸断肢：原版僵尸模型部件 + T 病毒丧尸贴图</li>
 *   <li>玩家断肢：原版玩家模型（按皮肤选择粗臂 / 细臂）部件 + 外层（袖套 / 裤腿 / 帽子），使用原主人的皮肤；
 *       主人不在线时回退为按 UUID 的默认皮肤</li>
 * </ul>
 * 空中绕横轴翻滚，落地后横躺。
 */
public class SeveredLimbRenderer extends EntityRenderer<SeveredLimb> {

    private final ModelPart zombie;
    private final ModelPart playerWide;
    private final ModelPart playerSlim;

    public SeveredLimbRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.zombie = context.bakeLayer(ModelLayers.ZOMBIE);
        this.playerWide = context.bakeLayer(ModelLayers.PLAYER);
        this.playerSlim = context.bakeLayer(ModelLayers.PLAYER_SLIM);
        this.shadowRadius = 0.15f;
    }

    /** 玩家断肢的皮肤（null = 丧尸断肢） */
    private static PlayerSkin skinOf(SeveredLimb limb) {
        UUID owner = limb.owner();
        if (owner == null) return null;
        var conn = Minecraft.getInstance().getConnection();
        PlayerInfo info = conn != null ? conn.getPlayerInfo(owner) : null;
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(owner);
    }

    private static String overlayOf(BodyPart bp) {
        return switch (bp) {
            case HEAD -> "hat";
            case TORSO -> "jacket";
            case RIGHT_ARM -> "right_sleeve";
            case LEFT_ARM -> "left_sleeve";
            case RIGHT_LEG -> "right_pants";
            case LEFT_LEG -> "left_pants";
        };
    }

    @Override
    public void render(SeveredLimb limb, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        BodyPart bp = limb.part();
        PlayerSkin skin = skinOf(limb);
        boolean slim = skin != null && skin.model() == PlayerSkin.Model.SLIM;
        ModelPart root = skin == null ? zombie : slim ? playerSlim : playerWide;
        ResourceLocation tex = skin == null ? TVirusZombieRenderer.TEXTURE : skin.texture();
        float s = limb.scale();
        // 细臂模型手臂宽 3 像素：方块中心比粗臂向内偏 0.5 像素
        float cx = bp.cx;
        if (slim && bp.isArm()) cx = bp == BodyPart.RIGHT_ARM ? -0.5f : 0.5f;

        poseStack.pushPose();
        // 以部件方块中心为旋转中心：先抬到「横躺厚度的一半」，再做朝向与翻滚
        poseStack.translate(0, bp.thickness * s, 0);
        poseStack.mulPose(Axis.YP.rotationDegrees(180f - limb.getYRot()));
        poseStack.mulPose(Axis.XP.rotationDegrees(limb.spin(partialTick)));
        poseStack.scale(-s, -s, s);
        poseStack.translate(-cx / 16f, -bp.cy / 16f, -bp.cz / 16f);

        drawPart(root.getChild(bp.modelPart), poseStack, buffer.getBuffer(RenderType.entityCutoutNoCull(tex)), packedLight);
        if (skin != null && root.hasChild(overlayOf(bp))) {
            // 皮肤外层（半透明像素）
            drawPart(root.getChild(overlayOf(bp)), poseStack,
                    buffer.getBuffer(RenderType.entityTranslucent(tex)), packedLight);
        }
        poseStack.popPose();
        super.render(limb, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    private static void drawPart(ModelPart part, PoseStack poseStack, com.mojang.blaze3d.vertex.VertexConsumer vc,
                                 int light) {
        PartPose saved = part.storePose();
        part.setPos(0, 0, 0);
        part.setRotation(0, 0, 0);
        part.visible = true;
        part.render(poseStack, vc, light, OverlayTexture.NO_OVERLAY);
        part.loadPose(saved);
    }

    @Override
    public ResourceLocation getTextureLocation(SeveredLimb limb) {
        PlayerSkin skin = skinOf(limb);
        return skin != null ? skin.texture() : TVirusZombieRenderer.TEXTURE;
    }
}

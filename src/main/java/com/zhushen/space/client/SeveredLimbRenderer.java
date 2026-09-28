package com.zhushen.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zhushen.space.entity.dismember.BodyPart;
import com.zhushen.space.entity.dismember.SeveredLimb;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 断肢渲染：直接取原版僵尸模型（与 T 病毒丧尸相同的模型层）中对应的部件，
 * 用 T 病毒丧尸贴图单独绘制；空中绕横轴翻滚，落地后横躺。
 */
public class SeveredLimbRenderer extends EntityRenderer<SeveredLimb> {

    private final ModelPart root;

    public SeveredLimbRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.root = context.bakeLayer(ModelLayers.ZOMBIE);
        this.shadowRadius = 0.15f;
    }

    @Override
    public void render(SeveredLimb limb, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        BodyPart bp = limb.part();
        ModelPart part = root.getChild(bp.modelPart);
        float s = limb.scale();

        poseStack.pushPose();
        // 以部件方块中心为旋转中心：先抬到「横躺厚度的一半」，再做朝向与翻滚
        poseStack.translate(0, bp.thickness * s, 0);
        poseStack.mulPose(Axis.YP.rotationDegrees(180f - limb.getYRot()));
        poseStack.mulPose(Axis.XP.rotationDegrees(limb.spin(partialTick)));
        poseStack.scale(-s, -s, s);
        poseStack.translate(-bp.cx / 16f, -bp.cy / 16f, -bp.cz / 16f);

        PartPose saved = part.storePose();
        part.setPos(0, 0, 0);
        part.setRotation(0, 0, 0);
        part.visible = true;
        part.render(poseStack, buffer.getBuffer(RenderType.entityCutoutNoCull(getTextureLocation(limb))),
                packedLight, OverlayTexture.NO_OVERLAY);
        part.loadPose(saved);
        poseStack.popPose();
        super.render(limb, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(SeveredLimb limb) {
        return TVirusZombieRenderer.TEXTURE;
    }
}

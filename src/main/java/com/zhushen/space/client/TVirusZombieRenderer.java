package com.zhushen.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.entity.ModEntities;
import com.zhushen.space.entity.TVirusZombie;
import com.zhushen.space.entity.dismember.BodyPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;

/**
 * T病毒丧尸渲染：复用原版僵尸模型/动画/装备层，替换为 T 病毒变异纹理。
 * <p>
 * 测试功能（部位肢解）：
 * <ul>
 *   <li>已断的部位不渲染（护甲层同步隐藏对应部位）；双腿全断时上半身下沉贴地</li>
 *   <li>受伤后头顶显示「人形部位血条」：头 / 躯干 / 双臂 / 双腿各一格，自下而上按剩余血量填充，
 *       绿 → 黄 → 红；已断的部位显示为灰色</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class TVirusZombieRenderer extends ZombieRenderer {
    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "textures/entity/t_virus_zombie.png");

    /** 部位血条的显示距离（格） */
    private static final double BAR_RANGE = 24.0;
    /** 部位血条像素 → 世界尺寸 */
    private static final float BAR_PIXEL = 0.025f;
    private static final int FULL_BRIGHT = 0xF000F0;

    public TVirusZombieRenderer(EntityRendererProvider.Context context) {
        super(context);
        // 换成会随断肢隐藏部位的护甲层
        this.layers.removeIf(layer -> layer instanceof HumanoidArmorLayer);
        this.addLayer(new DismemberArmorLayer(this,
                new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE_INNER_ARMOR)),
                new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(Zombie entity) {
        return TEXTURE;
    }

    @Override
    public void render(Zombie entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        if (!(entity instanceof TVirusZombie zombie) || zombie.severedMask() == 0) {
            super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
            if (entity instanceof TVirusZombie z) renderPartBars(z, poseStack, buffer);
            return;
        }
        ZombieModel<Zombie> model = getModel();
        boolean headOn = !zombie.isSevered(BodyPart.HEAD);
        model.head.visible = headOn;
        model.hat.visible = headOn;
        model.rightArm.visible = !zombie.isSevered(BodyPart.RIGHT_ARM);
        model.leftArm.visible = !zombie.isSevered(BodyPart.LEFT_ARM);
        model.rightLeg.visible = !zombie.isSevered(BodyPart.RIGHT_LEG);
        model.leftLeg.visible = !zombie.isSevered(BodyPart.LEFT_LEG);
        poseStack.pushPose();
        if (zombie.legless()) poseStack.translate(0, -BodyPart.LEG_DROP * zombie.getScale(), 0);
        try {
            super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
        } finally {
            poseStack.popPose();
            // 模型为同类实体共用：渲染完立即复原
            model.head.visible = true;
            model.hat.visible = true;
            model.rightArm.visible = true;
            model.leftArm.visible = true;
            model.rightLeg.visible = true;
            model.leftLeg.visible = true;
        }
        renderPartBars(zombie, poseStack, buffer);
    }

    // ===== 部位血条 =====

    /** 部位在血条人形中的矩形（像素，x 向右、y 向下，底边为 0；面向玩家时丧尸的右臂在画面左侧） */
    private static float[] rect(BodyPart p) {
        return switch (p) {
            case HEAD -> new float[]{-4, -34, 4, -26};
            case TORSO -> new float[]{-4, -25, 4, -13};
            case RIGHT_ARM -> new float[]{-9.5f, -25, -5.5f, -13};
            case LEFT_ARM -> new float[]{5.5f, -25, 9.5f, -13};
            case RIGHT_LEG -> new float[]{-4, -12, -0.5f, 0};
            case LEFT_LEG -> new float[]{0.5f, -12, 4, 0};
        };
    }

    private void renderPartBars(TVirusZombie zombie, PoseStack poseStack, MultiBufferSource buffer) {
        if (!zombie.partsActive() || !zombie.isAlive() || zombie.isInvisible()) return;
        if (Minecraft.getInstance().options.hideGui || !zombie.anyPartDamaged()) return;
        if (entityRenderDispatcher.distanceToSqr(zombie) > BAR_RANGE * BAR_RANGE) return;

        poseStack.pushPose();
        poseStack.translate(0, zombie.getBbHeight() + 0.3, 0);
        poseStack.mulPose(entityRenderDispatcher.cameraOrientation());
        poseStack.scale(BAR_PIXEL, -BAR_PIXEL, BAR_PIXEL);
        Matrix4f mat = poseStack.last().pose();
        VertexConsumer vc = buffer.getBuffer(RenderType.textBackground());

        for (BodyPart p : BodyPart.values()) {
            float[] r = rect(p);
            float x0 = r[0], y0 = r[1], x1 = r[2], y1 = r[3];
            // 黑色描边（四条边，不与内部重叠，避免深度冲突）
            int edge = 0xD0000000;
            quad(vc, mat, x0 - 1, y0 - 1, x1 + 1, y0, edge);
            quad(vc, mat, x0 - 1, y1, x1 + 1, y1 + 1, edge);
            quad(vc, mat, x0 - 1, y0, x0, y1, edge);
            quad(vc, mat, x1, y0, x1 + 1, y1, edge);
            if (zombie.isSevered(p)) {
                quad(vc, mat, x0, y0, x1, y1, 0xB0505050);
                continue;
            }
            float ratio = zombie.partHealthRatio(p);
            float fillTop = y1 - (y1 - y0) * ratio;
            if (fillTop > y0) quad(vc, mat, x0, y0, x1, fillTop, 0xA0381010);   // 已损失
            if (ratio > 0) quad(vc, mat, x0, fillTop, x1, y1, healthColor(ratio)); // 剩余
        }
        poseStack.popPose();
    }

    /** 绿（满）→ 黄（半）→ 红（空） */
    private static int healthColor(float ratio) {
        float r, g;
        if (ratio > 0.5f) {
            r = (1f - ratio) * 2f;
            g = 1f;
        } else {
            r = 1f;
            g = ratio * 2f;
        }
        int ri = (int) (60 + 180 * r), gi = (int) (40 + 170 * g), bi = 40;
        return 0xE0000000 | (ri << 16) | (gi << 8) | bi;
    }

    /** 双面四边形（不依赖剔除方向） */
    private static void quad(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, int argb) {
        vc.addVertex(m, x0, y0, 0).setColor(argb).setLight(FULL_BRIGHT);
        vc.addVertex(m, x0, y1, 0).setColor(argb).setLight(FULL_BRIGHT);
        vc.addVertex(m, x1, y1, 0).setColor(argb).setLight(FULL_BRIGHT);
        vc.addVertex(m, x1, y0, 0).setColor(argb).setLight(FULL_BRIGHT);
        vc.addVertex(m, x1, y0, 0).setColor(argb).setLight(FULL_BRIGHT);
        vc.addVertex(m, x1, y1, 0).setColor(argb).setLight(FULL_BRIGHT);
        vc.addVertex(m, x0, y1, 0).setColor(argb).setLight(FULL_BRIGHT);
        vc.addVertex(m, x0, y0, 0).setColor(argb).setLight(FULL_BRIGHT);
    }

    /** 护甲层：断掉的部位不渲染对应护甲 */
    private static final class DismemberArmorLayer
            extends HumanoidArmorLayer<Zombie, ZombieModel<Zombie>, ZombieModel<Zombie>> {
        private int mask;

        DismemberArmorLayer(RenderLayerParent<Zombie, ZombieModel<Zombie>> parent,
                            ZombieModel<Zombie> inner, ZombieModel<Zombie> outer, ModelManager models) {
            super(parent, inner, outer, models);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, int light, Zombie entity,
                           float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                           float netHeadYaw, float headPitch) {
            mask = entity instanceof TVirusZombie z ? z.severedMask() : 0;
            super.render(poseStack, buffer, light, entity, limbSwing, limbSwingAmount, partialTick,
                    ageInTicks, netHeadYaw, headPitch);
        }

        @Override
        protected void setPartVisibility(ZombieModel<Zombie> model, EquipmentSlot slot) {
            super.setPartVisibility(model, slot);
            if (mask == 0) return;
            if ((mask & BodyPart.HEAD.bit()) != 0) {
                model.head.visible = false;
                model.hat.visible = false;
            }
            if ((mask & BodyPart.RIGHT_ARM.bit()) != 0) model.rightArm.visible = false;
            if ((mask & BodyPart.LEFT_ARM.bit()) != 0) model.leftArm.visible = false;
            if ((mask & BodyPart.RIGHT_LEG.bit()) != 0) model.rightLeg.visible = false;
            if ((mask & BodyPart.LEFT_LEG.bit()) != 0) model.leftLeg.visible = false;
        }
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.T_VIRUS_ZOMBIE.get(), TVirusZombieRenderer::new);
        event.registerEntityRenderer(ModEntities.SEVERED_LIMB.get(), SeveredLimbRenderer::new);
    }
}

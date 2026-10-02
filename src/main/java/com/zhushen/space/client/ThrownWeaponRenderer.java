package com.zhushen.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.entity.ModEntities;
import com.zhushen.space.entity.ThrownWeapon;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 投出的冷兵器：直接渲染物品模型，刀尖 / 斧头沿飞行方向（物品贴图的头部朝右上，绕 Z 轴转 −45° 对齐 +X）；
 * 飞行中匕首沿刀身滚转、飞斧 / 飞锤首尾翻滚，插在地上 / 目标上后静止。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ThrownWeaponRenderer extends EntityRenderer<ThrownWeapon> {

    private final ItemRenderer items;

    public ThrownWeaponRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.items = ctx.getItemRenderer();
    }

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ModEntities.THROWN_WEAPON.get(), ThrownWeaponRenderer::new);
    }

    @Override
    public void render(ThrownWeapon e, float yaw, float pt, PoseStack pose, MultiBufferSource buf, int light) {
        ItemStack st = e.displayItem();
        if (st.isEmpty()) return;
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(Mth.lerp(pt, e.yRotO, e.getYRot()) - 90.0f));
        pose.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(pt, e.xRotO, e.getXRot())));
        if (!e.inGround()) {
            var w = com.zhushen.space.data.MeleeWeapon.of(st);
            if (w != null && w.has(com.zhushen.space.data.MeleeWeapon.Trait.HEAVY_THROWN)) {
                // 飞斧 / 飞锤：首尾翻滚
                pose.mulPose(Axis.ZP.rotationDegrees(-(e.tickCount + pt) * 45f));
            } else {
                // 匕首：沿刀身轴（+X）滚转
                pose.mulPose(Axis.XP.rotationDegrees((e.tickCount + pt) * 40f));
            }
        }
        pose.translate(0.15f, 0f, 0f); // 刀尖前置：插入方块 / 目标时刀尖没入、刀柄露在外面
        pose.mulPose(Axis.ZP.rotationDegrees(-45.0f));
        pose.scale(0.85f, 0.85f, 0.85f);
        items.renderStatic(st, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY, pose, buf, e.level(), e.getId());
        pose.popPose();
        super.render(e, yaw, pt, pose, buf, light);
    }

    @Override
    public ResourceLocation getTextureLocation(ThrownWeapon e) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}

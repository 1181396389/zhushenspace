package com.zhushen.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.item.MahoragaWheelItem;
import com.zhushen.space.screen.ZsAnim;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

/**
 * 魔虚罗之法阵：头顶悬浮的八柄法轮（随头部朝向，轻微浮动）。每次适应转动一格（45°，带回弹），
 * 转动时金色辉光一闪。法阵贴图为卡通描边风格（textures/entity/mahoraga_wheel.png）。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class MahoragaWheelRenderer {
    private MahoragaWheelRenderer() {}

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "textures/entity/mahoraga_wheel.png");

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Post e) {
        Player p = e.getEntity();
        if (p.isInvisible() || !(p.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof MahoragaWheelItem)) return;
        float pt = e.getPartialTick();
        PoseStack ps = e.getPoseStack();
        float headYaw = Mth.rotLerp(pt, p.yHeadRotO, p.yHeadRot);
        float bob = (float) Math.sin((p.tickCount + pt) * 0.08f) * 0.025f;
        float turns = ClientAdaptData.turns(p.getId());
        float flash = ClientAdaptData.flash(p.getId());
        // 穿戴中：半透明虚影（本人可见进度；他人看到的始终是实体）
        float alpha = 1f;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == p) {
            ClientGearData.View v = ClientGearData.get("v:head");
            if (v != null && v.state() != 1) alpha = 0.45f + 0.25f * ZsAnim.pulse(1200);
        }

        ps.pushPose();
        ps.translate(0, p.getBbHeight() + 0.36 + bob, 0);
        ps.mulPose(Axis.YP.rotationDegrees(-headYaw));
        ps.mulPose(Axis.XP.rotationDegrees(-14f));
        ps.mulPose(Axis.YP.rotationDegrees(turns * 45f));
        float s = 0.56f * (1f + 0.06f * flash);
        int a = (int) (255 * alpha);
        quad(ps, e.getMultiBufferSource().getBuffer(RenderType.entityTranslucent(TEX)), s, false, 255, 255, 255, a, LightTexture.FULL_BRIGHT);
        if (flash > 0.01f) {
            int c = (int) (220 * flash);
            VertexConsumer glow = e.getMultiBufferSource().getBuffer(RenderType.eyes(TEX));
            float gs = s * (1f + 0.25f * (1f - flash));
            quad(ps, glow, gs, false, c, (int) (c * 0.85f), (int) (c * 0.45f), 255, LightTexture.FULL_BRIGHT);
            quad(ps, glow, gs, true, c, (int) (c * 0.85f), (int) (c * 0.45f), 255, LightTexture.FULL_BRIGHT); // 背面（辉光层有背面剔除）
        }
        ps.popPose();
    }

    private static void quad(PoseStack ps, VertexConsumer vc, float s, boolean back, int r, int g, int b, int a, int light) {
        PoseStack.Pose pose = ps.last();
        float[][] v = {{-s, -s, 0, 0}, {-s, s, 0, 1}, {s, s, 1, 1}, {s, -s, 1, 0}};
        float ny = back ? -1 : 1;
        for (int i = 0; i < 4; i++) {
            float[] q = v[back ? 3 - i : i];
            vc.addVertex(pose, q[0], 0, q[1]).setColor(r, g, b, a).setUv(q[2], q[3]).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light).setNormal(pose, 0, ny, 0);
        }
    }
}

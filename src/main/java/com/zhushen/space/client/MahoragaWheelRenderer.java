package com.zhushen.space.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.item.MahoragaWheelItem;
import com.zhushen.space.screen.ZsAnim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

import java.io.Reader;
import java.util.Optional;

/**
 * 魔虚罗之法阵：头顶水平悬浮的八柄法轮（Blockbench 模型 art/mahoraga_wheel.bbmodel，
 * 由 tools/bake_bbmodel.py 烘焙为 models/entity/mahoraga_wheel.json + textures/entity/mahoraga_wheel_model.png）。
 * 随头部朝向转动、轻微浮动；每次适应顺时针转动一格（45°，与模型自带动画一致：约 0.21 秒匀速），转动时金色辉光一闪。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class MahoragaWheelRenderer {
    private MahoragaWheelRenderer() {}

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "textures/entity/mahoraga_wheel_model.png");
    private static final ResourceLocation MESH =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "models/entity/mahoraga_wheel.json");
    /** 模型中心（法轮所在平面，像素单位） */
    private static final float PIVOT_Y = 7.025f;
    /** 模型直径 22 像素 → 约 1.1 格 */
    private static final float SCALE = 0.8f / 16f;
    /** 法轮平面离头顶的高度（格） */
    private static final float HOVER = 0.34f;

    /** 每个四边形：4 × (x, y, z, u, v) + 法线 (nx, ny, nz) */
    private static float[][] mesh;
    private static boolean loaded;

    private static float[][] mesh() {
        if (!loaded) {
            loaded = true;
            mesh = load();
        }
        return mesh;
    }

    private static float[][] load() {
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(MESH);
            if (res.isEmpty()) return null;
            try (Reader r = res.get().openAsReader()) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                JsonArray arr = root.getAsJsonArray("quads");
                float[][] out = new float[arr.size()][23];
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject q = arr.get(i).getAsJsonObject();
                    JsonArray v = q.getAsJsonArray("v");
                    for (int j = 0; j < 4; j++) {
                        JsonArray pt = v.get(j).getAsJsonArray();
                        for (int k = 0; k < 5; k++) out[i][j * 5 + k] = pt.get(k).getAsFloat();
                    }
                    JsonArray n = q.getAsJsonArray("n");
                    for (int k = 0; k < 3; k++) out[i][20 + k] = n.get(k).getAsFloat();
                }
                return out;
            }
        } catch (Exception ex) {
            ZhuShenSpace.LOGGER.warn("魔虚罗法阵模型加载失败", ex);
            return null;
        }
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Post e) {
        Player p = e.getEntity();
        if (p.isInvisible() || !(p.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof MahoragaWheelItem)) return;
        float[][] m = mesh();
        if (m == null) return;
        float pt = e.getPartialTick();
        PoseStack ps = e.getPoseStack();
        MultiBufferSource buf = e.getMultiBufferSource();
        float headYaw = Mth.rotLerp(pt, p.yHeadRotO, p.yHeadRot);
        float bob = (float) Math.sin((p.tickCount + pt) * 0.08f) * 0.025f;
        float turns = ClientAdaptData.worldTurns(p.getId());
        float flash = ClientAdaptData.flash(p.getId());
        // 穿戴中：半透明虚影（本人可见进度；他人看到的始终是实体）
        float alpha = 1f;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == p) {
            ClientGearData.View v = ClientGearData.get("v:head");
            if (v != null && v.state() != 1) alpha = 0.45f + 0.25f * ZsAnim.pulse(1200);
        }

        ps.pushPose();
        ps.translate(0, p.getBbHeight() + HOVER + bob, 0);
        ps.mulPose(Axis.YP.rotationDegrees(-headYaw));
        ps.mulPose(Axis.YP.rotationDegrees(-turns * 45f)); // 从上往下看顺时针
        float s = SCALE * (1f + 0.04f * flash);
        ps.scale(s, s, s);
        ps.translate(0, -PIVOT_Y, 0);
        PoseStack.Pose pose = ps.last();
        int a = (int) (255 * alpha);
        VertexConsumer vc = buf.getBuffer(alpha < 1f ? RenderType.entityTranslucent(TEX) : RenderType.entityCutoutNoCull(TEX));
        emit(pose, vc, m, 255, 255, 255, a);
        if (flash > 0.01f) {
            int c = (int) (230 * flash);
            emit(pose, buf.getBuffer(RenderType.eyes(TEX)), m, c, (int) (c * 0.88f), (int) (c * 0.5f), 255);
        }
        ps.popPose();
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer vc, float[][] m, int r, int g, int b, int a) {
        for (float[] q : m) {
            for (int j = 0; j < 4; j++) {
                int o = j * 5;
                vc.addVertex(pose, q[o], q[o + 1], q[o + 2]).setColor(r, g, b, a).setUv(q[o + 3], q[o + 4])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                        .setNormal(pose, q[20], q[21], q[22]);
            }
        }
    }

    /** 资源包重载时重新读取模型 */
    @EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Reload {
        private Reload() {}

        @SubscribeEvent
        public static void onRegister(RegisterClientReloadListenersEvent e) {
            e.registerReloadListener((ResourceManagerReloadListener) rm -> {
                mesh = null;
                loaded = false;
            });
        }
    }
}

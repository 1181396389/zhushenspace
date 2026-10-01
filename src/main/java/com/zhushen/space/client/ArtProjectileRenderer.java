package com.zhushen.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.client.fx.ZsRenderTypes;
import com.zhushen.space.entity.ModEntities;
import com.zhushen.space.entity.art.ArtProjectile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static com.zhushen.space.client.fx.AnimeFx.*;

/**
 * 动漫风技艺特效：全部为程序生成的纯色几何（赛璐璐色阶 + 白芯 + 加法光晕），每帧动态变形，不用贴图球 / 贴图平面。
 * 每个实体先画完所有 TOON（普通混合）再画 GLOW（加法），两种缓冲不交错，避免频繁切批。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ArtProjectileRenderer extends EntityRenderer<ArtProjectile> {
    public ArtProjectileRenderer(EntityRendererProvider.Context c) { super(c); shadowRadius = 0; }

    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ModEntities.ART_PROJECTILE.get(), ArtProjectileRenderer::new);
    }

    @Override public ResourceLocation getTextureLocation(ArtProjectile e) { return InventoryMenu.BLOCK_ATLAS; }

    @Override public boolean shouldRender(ArtProjectile e, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
        // 视觉体积远大于逻辑碰撞箱；跟随施法者的蓄力特效总是绘制（位置由客户端重新计算）
        if (e.anchored()) return true;
        double pad = e.kind() == ArtProjectile.BURST ? 12 : e.kind() == ArtProjectile.HIT_WIND ? 4 : 5;
        return e.shouldRender(x, y, z) && frustum.isVisible(e.getBoundingBox().inflate(pad));
    }

    // ───────────────────────── 每帧上下文 ─────────────────────────

    private final class Ctx {
        final ArtProjectile e;
        final PoseStack s;
        final MultiBufferSource buf;
        final float t, scale, alpha;
        final Quaternionf rot, cam;
        final Vector3f toCam;   // 世界方向：特效 → 相机
        final float camDist;
        final boolean ownerFP;

        Ctx(ArtProjectile e, PoseStack s, MultiBufferSource buf, float t, Quaternionf rot, Vector3f toCam,
            float scale, float alpha, boolean ownerFP) {
            this.e = e; this.s = s; this.buf = buf; this.t = t; this.rot = rot; this.toCam = toCam;
            this.scale = scale; this.alpha = alpha; this.ownerFP = ownerFP;
            this.cam = entityRenderDispatcher.cameraOrientation();
            this.camDist = toCam.length();
        }

        VertexConsumer toon() { return buf.getBuffer(ZsRenderTypes.TOON); }
        VertexConsumer glow() { return buf.getBuffer(ZsRenderTypes.GLOW); }
        int c(int rgb, float a) { return argb(rgb, a * alpha); }
        Matrix4f m() { return s.last().pose(); }

        /** 把世界方向转到某个局部坐标系 */
        Vector3f local(Quaternionf q, Vector3f world) { return new Quaternionf(q).conjugate().transform(new Vector3f(world)); }

        /** 进入飞行方向坐标系（+Z 前、+Y 上），可再绕 Z 轴滚转 */
        void pushFrame(float roll) {
            s.pushPose();
            s.mulPose(rot);
            if (roll != 0f) s.mulPose(new Quaternionf().rotationZ(roll));
        }

        /** 在飞行坐标系的点 (lx,ly,lz) 处进入面向相机的平面（+X 屏幕右、+Y 屏幕上） */
        void pushBillboard(float lx, float ly, float lz) {
            Vector3f o = rot.transform(new Vector3f(lx, ly, lz));
            s.pushPose();
            s.translate(o.x, o.y, o.z);
            s.mulPose(cam);
        }

        void pushBillboardWorld(float wx, float wy, float wz) {
            s.pushPose();
            s.translate(wx, wy, wz);
            s.mulPose(cam);
        }

        /** 飞行方向在屏幕上的投影（未归一化：长度≈横向运动占比） */
        float[] screenDir() {
            Vector3f d = local(cam, rot.transform(new Vector3f(0, 0, 1)));
            return new float[]{d.x, d.y};
        }

        /** 飞行坐标系（含滚转）里轴向面片的屏幕侧向 */
        float[] axialSide(float roll) {
            Quaternionf q = new Quaternionf(rot);
            if (roll != 0f) q.rotateZ(roll);
            Vector3f l = local(q, toCam);
            return com.zhushen.space.client.fx.AnimeFx.axialSide(l.x, l.y, l.z);
        }
    }

    // ───────────────────────── 入口 ─────────────────────────

    @Override public void render(ArtProjectile e, float yaw, float partial, PoseStack stack, MultiBufferSource buffers, int light) {
        Minecraft mc = Minecraft.getInstance();
        int kind = e.kind();
        float time = e.tickCount + partial;
        // 新发出的弹体第一 tick 与施法者重叠，跳过以免整屏闪一下
        if (e.tickCount < 1 && !ArtProjectile.isFx(kind) && !e.anchored() && kind != ArtProjectile.SEAL) return;

        Vec3 pos = e.getPosition(partial);
        float yRot = e.getYRot(), xRot = e.getXRot(), scale = 1f, alpha = 1f;
        Entity owner = e.level().getEntity(e.ownerId());
        boolean ownerFP = owner != null && owner == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();

        stack.pushPose();
        if (e.anchored()) {
            if (owner == null) { stack.popPose(); return; }
            // 按施法者本帧插值后的姿态重新定位：转身 / 走动时紧贴施法者，不拖影
            Vec3 want = ArtProjectile.anchorPos(owner, e.anchor(), partial, ownerFP);
            stack.translate(want.x - pos.x, want.y - pos.y, want.z - pos.z);
            pos = want;
            yRot = owner.getViewYRot(partial);
            xRot = owner.getViewXRot(partial);
            if (ownerFP) {
                scale = kind == ArtProjectile.SEAL ? 0.62f : kind == ArtProjectile.CHARGE_FIRE ? 0.6f : 0.75f;
                alpha = kind == ArtProjectile.SEAL ? 0.7f : 0.9f;
            }
        } else if (kind == ArtProjectile.SEAL && ownerFP) {
            // 释放后的阵：第一人称同样推远、缩小，与蓄力时一致
            Vec3 d = Vec3.directionFromRotation(xRot, yRot);
            stack.translate(d.x, d.y, d.z);
            pos = pos.add(d);
            scale = 0.62f;
            alpha = 0.7f;
        }
        Vec3 camPos = entityRenderDispatcher.camera.getPosition();
        Vector3f toCam = new Vector3f((float) (camPos.x - pos.x), (float) (camPos.y - pos.y), (float) (camPos.z - pos.z));
        Quaternionf rot = new Quaternionf().rotationY((float) Math.toRadians(-yRot)).rotateX((float) Math.toRadians(xRot));
        Ctx c = new Ctx(e, stack, buffers, time, rot, toCam, scale, alpha, ownerFP);

        switch (kind) {
            case ArtProjectile.SPIRIT -> slash(c, false);
            case ArtProjectile.WIND -> slash(c, true);
            case ArtProjectile.WAVE -> wave(c);
            case ArtProjectile.LASER -> laser(c);
            case ArtProjectile.SEAL -> seal(c);
            case ArtProjectile.FIREBALL -> fireball(c);
            case ArtProjectile.BURST -> burst(c);
            case ArtProjectile.CHARGE_ORB -> chargeOrb(c);
            case ArtProjectile.CHARGE_FIRE -> chargeFire(c);
            case ArtProjectile.RING -> launchRing(c);
            case ArtProjectile.HIT_SLASH -> hitSlash(c);
            case ArtProjectile.HIT_WIND -> hitWind(c);
            case ArtProjectile.HIT_WAVE -> hitWave(c);
            case ArtProjectile.HIT_SEAL -> hitSeal(c);
            case ArtProjectile.SWING -> swing(c);
            default -> {}
        }
        stack.popPose();

        // 大招命中：一次性屏幕白闪 + 冲击集中线 + 震屏（按距离衰减）
        if (!e.clientFxFired && (kind == ArtProjectile.BURST || kind == ArtProjectile.HIT_WAVE)) {
            e.clientFxFired = true;
            float k = kind == ArtProjectile.BURST ? clamp01(1f - c.camDist / 40f) : clamp01(1f - c.camDist / 20f) * 0.6f;
            if (k > 0.05f) {
                ClientArtScreenFx.impact(k);
                ClientCameraShake.trigger((kind == ArtProjectile.BURST ? 2.2f : 0.9f) * k, kind == ArtProjectile.BURST ? 14 : 7);
            }
        }
    }

    // ───────────────────────── 灵斩 / 风斩：新月刀光 ─────────────────────────

    private void slash(Ctx c, boolean wind) {
        float t = c.t;
        float grow = 0.55f + 0.45f * easeOut(t / 4f);
        float halfW = (wind ? 2.3f : 1.75f) * grow, sag = wind ? 0.95f : 0.55f, thick = wind ? 0.82f : 0.46f, depth = wind ? 1.1f : 0.8f;
        float roll = wind ? -0.75f : -0.12f;
        int outer = wind ? 0x1FC48A : 0x3AA8FF, mid = wind ? 0x8DF2C8 : 0x9FE4FF;
        float fadeIn = clamp01((t - 0.5f) / 1.5f);

        // TOON
        c.pushFrame(roll);
        Matrix4f m = c.m();
        VertexConsumer vc = c.toon();
        // 残影：沿飞行反方向的淡色重影
        for (int k = 3; k >= 1; k--) {
            Matrix4f mk = new Matrix4f(m).translate(0, 0, -0.7f * k).scale(1f - 0.06f * k);
            crescent(vc, mk, halfW, sag, thick, depth, 0f, 1f, c.c(outer, 0.34f / k * fadeIn), c.c(outer, 0f), -1f, 1f, 28);
        }
        if (wind) {
            // 两道副刃：更宽更薄、各自摆动
            for (int j = 1; j <= 2; j++) {
                Matrix4f mj = new Matrix4f(m).translate(0, -0.12f * j, -0.4f * j).rotateZ(0.22f * j * Mth.sin(t * 0.35f + j * 1.7f));
                crescent(vc, mj, halfW * (1f + 0.16f * j), sag * (1f + 0.3f * j), thick * 0.22f, depth, 0f, 1f,
                        c.c(mid, 0.75f / j * fadeIn), c.c(outer, 0f), -1f, 1f, 28);
            }
            // 螺旋风带
            for (int k = 0; k < 2; k++) {
                int n = 22;
                float px = 0, py = 0, pz = 0, pwx = 0, pwy = 0;
                int pc = 0;
                for (int i = 0; i <= n; i++) {
                    float sv = 3.2f * i / n, f = sv / 3.2f;
                    float a = sv * 2.6f + t * 0.5f + k * (float) Math.PI;
                    float r = halfW * 0.5f * (1f - 0.4f * f);
                    float x = r * Mth.cos(a), y = r * Mth.sin(a) + sag * 0.35f, z = -sv;
                    float w = 0.09f * (1f - f);
                    float wx = Mth.cos(a) * w, wy = Mth.sin(a) * w;
                    int cc = c.c(i % 2 == 0 ? mid : 0xFFFFFF, 0.6f * (1f - f) * fadeIn);
                    if (i > 0) quad(vc, m, px - pwx, py - pwy, pz, pc, px + pwx, py + pwy, pz, pc, x + wx, y + wy, z, cc, x - wx, y - wy, z, cc);
                    px = x; py = y; pz = z; pwx = wx; pwy = wy; pc = cc;
                }
            }
        }
        // 主刃：外层色带 → 中层 → 白色刃口
        crescent(vc, m, halfW * 1.04f, sag, thick * 1.25f, depth, -0.08f, 1f, c.c(outer, 0.85f * fadeIn), c.c(outer, 0.05f), -1f, 1f, 40);
        crescent(vc, m, halfW, sag, thick, depth, 0f, 0.8f, c.c(mid, fadeIn), c.c(outer, 0.9f * fadeIn), -1f, 1f, 40);
        crescent(vc, m, halfW * 0.97f, sag, thick, depth, 0f, 0.34f, c.c(0xFFFFFF, fadeIn), c.c(0xF2FDFF, fadeIn), -1f, 1f, 40);
        c.s.popPose();

        // GLOW
        c.pushFrame(roll);
        m = c.m();
        vc = c.glow();
        crescent(vc, m, halfW * 1.1f, sag + 0.1f, thick * 1.7f, depth, -0.3f, 1f, c.c(outer, 0.4f * fadeIn), c.c(outer, 0f), -1f, 1f, 32);
        float[] side = c.axialSide(roll);
        int seed = c.e.getId() * 17;
        for (int i = 0; i < 7; i++) {
            float tt = -0.85f + 1.7f * i / 6f;
            float base = 1f - tt * tt;
            float x = halfW * tt, y = sag * base - thick * (float) Math.pow(base, 0.7) * 0.25f, z = depth * base - 0.1f;
            float len = (1.2f + 1.8f * hash(seed + i)) * (wind ? 1.3f : 1f);
            float flick = 0.6f + 0.4f * Mth.sin(t * 1.3f + i * 2.1f);
            streak(vc, m, x, y, z, len, 0.035f + 0.02f * hash(seed + i * 5), side[0], side[1], c.c(0xFFFFFF, 0.8f * flick * fadeIn), c.c(outer, 0f));
        }
        c.s.popPose();
        // 刀尖闪光
        Quaternionf q = new Quaternionf(c.rot).rotateZ(roll);
        for (int k = -1; k <= 1; k += 2) {
            float tt = 0.9f * k, base = 1f - tt * tt;
            Vector3f p = q.transform(new Vector3f(halfW * tt, sag * base, depth * base));
            c.pushBillboardWorld(p.x, p.y, p.z);
            float sz = (wind ? 0.42f : 0.32f) * (0.6f + 0.4f * Mth.sin(t * 1.9f + k));
            sparkle(vc, c.m(), 0, 0, sz, t * 0.1f, c.c(0xFFFFFF, 0.95f * fadeIn), c.c(mid, 0f));
            c.s.popPose();
        }
    }

    // ───────────────────────── 波动拳：能量弹 ─────────────────────────

    private void wave(Ctx c) {
        float t = c.t, S = c.e.size();
        float[] sd = c.screenDir();
        float lateral = Mth.sqrt(sd[0] * sd[0] + sd[1] * sd[1]);
        float tail = 1.7f * lateral;
        float pulse = 1f + 0.05f * Mth.sin(t * 1.7f);

        c.pushBillboard(0, 0, 0);
        Matrix4f m = c.m();
        VertexConsumer vc = c.toon();
        int[] col = {0x1B4FE0, 0x2F93FF, 0x7FD8FF, 0xD6F7FF, 0xFFFFFF};
        float[] rr = {1.08f, 0.9f, 0.7f, 0.5f, 0.32f};
        float[] tl = {1.9f, 1.5f, 1.05f, 0.6f, 0.3f};
        for (int i = 0; i < col.length; i++) {
            blob(vc, m, 0, 0, S * rr[i] * pulse, 0.07f, 0.11f - i * 0.02f, t * (1.1f + i * 0.2f) + i * 1.9f,
                    -sd[0], -sd[1], tail * tl[i], c.c(col[i], i == 0 ? 0.6f : 0.95f), c.c(col[i], i == 0 ? 0.6f : 0.95f), 44);
        }
        c.s.popPose();

        // GLOW：大光晕 + 外放射
        c.pushBillboard(0, 0, 0);
        m = c.m();
        vc = c.glow();
        disc(vc, m, 0, 0, S * 1.9f, c.c(0x3C8CFF, 0.45f), c.c(0x3C8CFF, 0f), 32);
        starburst(vc, m, 12, S * 0.5f, S * (1.6f + 0.25f * Mth.sin(t * 2.3f)), t * 0.15f, (int) (t * 0.5f) * 7 + c.e.getId(),
                c.c(0x9FE6FF, 0.3f), c.c(0x2F93FF, 0f));
        c.s.popPose();
        // 环绕光环 + 尾迹流光（飞行坐标系）
        for (int k = 0; k < 2; k++) {
            c.pushFrame(0);
            c.s.mulPose(new Quaternionf().rotationZ(t * (k == 0 ? 0.25f : -0.33f)).rotateX(k == 0 ? 0.5f : -0.6f));
            ring(vc, c.m(), S * 1.0f, S * 1.1f, c.c(0xBFF3FF, 0.7f), c.c(0x4FA8FF, 0.0f), 0, TAU * 0.7f, 40);
            c.s.popPose();
        }
        c.pushFrame(0);
        m = c.m();
        float[] side = c.axialSide(0);
        for (int i = 0; i < 7; i++) {
            float a = TAU * i / 7f + t * 0.05f;
            float r = S * (0.35f + 0.45f * hash(c.e.getId() + i * 3));
            float len = S * (1.8f + 1.6f * hash(c.e.getId() * 3 + i)) * (0.8f + 0.2f * Mth.sin(t + i));
            streak(vc, m, Mth.cos(a) * r, Mth.sin(a) * r, -S * 0.2f, len, S * 0.06f, side[0], side[1], c.c(0xE8FBFF, 0.75f), c.c(0x2F93FF, 0f));
        }
        c.s.popPose();
    }

    // ───────────────────────── 八阵图：激光 ─────────────────────────

    private void laser(Ctx c) {
        float len = Math.min(4.5f, c.t * 2.5f);
        c.pushFrame(0);
        Matrix4f m = c.m();
        float[] side = c.axialSide(0);
        int col = c.e.color() & 0xFFFFFF;
        VertexConsumer vc = c.toon();
        streak(vc, m, 0, 0, 0.2f, len, 0.16f, side[0], side[1], c.c(col, 0.95f), c.c(col, 0f));
        streak(vc, m, 0, 0, 0.15f, len * 0.85f, 0.06f, side[0], side[1], c.c(0xFFFFFF, 1f), c.c(0xFFFFFF, 0f));
        c.s.popPose();
        c.pushFrame(0);
        vc = c.glow();
        streak(vc, c.m(), 0, 0, 0.35f, len * 1.1f, 0.42f, side[0], side[1], c.c(col, 0.5f), c.c(col, 0f));
        c.s.popPose();
        c.pushBillboard(0, 0, 0.1f);
        disc(vc, c.m(), 0, 0, 0.45f, c.c(col, 0.55f), c.c(col, 0f), 20);
        sparkle(vc, c.m(), 0, 0, 0.5f + 0.1f * Mth.sin(c.t * 3f), c.t * 0.2f, c.c(0xFFFFFF, 0.9f), c.c(col, 0f));
        c.s.popPose();
    }

    // ───────────────────────── 八阵图：八卦法阵 ─────────────────────────

    private static final int[] TRIGRAMS = {7, 3, 5, 1, 0, 4, 2, 6};

    private void seal(Ctx c) {
        ArtProjectile e = c.e;
        float t = c.t;
        boolean charging = e.anchored();
        float charge = charging ? clamp01(t / 40f) : 1f;
        float draw = charging ? clamp01(t / 10f) : 1f;
        float flare = charging ? 0f : clamp01(1f - t / 5f);
        float fade = charging ? 1f : clamp01((14f - t) / 4f);
        float pop = charging ? 0.55f + 0.45f * easeOutBack(t / 8f) : 1f + 0.12f * flare;
        float S = e.size() * c.scale * pop;
        int col = e.color() & 0xFFFFFF;
        int light = mix(col, 0xFFFFFF, 0.72f), dark = mix(col, 0x000000, 0.6f);
        float spin = charging ? t * (0.012f + 0.05f * charge) : 0.6f + t * 0.14f;
        float A = fade;

        c.pushFrame(0);
        Matrix4f m = c.m();
        VertexConsumer vc = c.toon();
        // 阵盘底色
        disc(vc, m, 0, 0, S * 0.98f * draw, c.c(dark, 0.22f * A), c.c(dark, 0.12f * A), 48);
        // 外圈双环（随蓄力逐笔画出）
        float a0 = (float) Math.PI / 2f, a1 = a0 + TAU * draw;
        ring(vc, m, S * 0.95f, S * 1.0f, c.c(col, 0.95f * A), c.c(light, 0.95f * A), a0, a1, 72);
        ring(vc, m, S * 0.87f, S * 0.895f, c.c(light, 0.9f * A), c.c(col, 0.9f * A), -a0, -a0 - TAU * draw, 72);
        // 刻度
        int ticks = (int) (48 * draw);
        for (int k = 0; k < ticks; k++) {
            float a = spin + TAU * k / 48f;
            boolean big = k % 4 == 0;
            float r = S * (big ? 0.80f : 0.815f);
            bar(vc, m, Mth.cos(a) * r, Mth.sin(a) * r, a, S * (big ? 0.045f : 0.022f), S * 0.007f, c.c(light, 0.9f * A));
        }
        // 内八角
        if (draw > 0.4f) {
            float k8 = clamp01((draw - 0.4f) / 0.3f);
            float r8 = S * 0.47f, rotO = -spin * 0.7f;
            for (int k = 0; k < 8; k++) {
                float p0 = rotO + TAU * k / 8f, p1 = rotO + TAU * (k + 1) / 8f;
                float x0 = Mth.cos(p0) * r8, y0 = Mth.sin(p0) * r8, x1 = Mth.cos(p1) * r8, y1 = Mth.sin(p1) * r8;
                float mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
                float ang = (float) Math.atan2(y1 - y0, x1 - x0);
                float hl = Mth.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0)) / 2f;
                bar(vc, m, mx, my, ang, hl * k8, S * 0.008f, c.c(col, 0.9f * A));
            }
        }
        // 八卦爻（与刻度反向旋转，逐个亮起）
        float rotT = -spin * 1.4f + (float) Math.PI / 2f;
        for (int k = 0; k < 8; k++) {
            float appear = clamp01(draw * 9f - k);
            if (appear <= 0f) continue;
            float th = rotT + TAU * k / 8f;
            float tx = -Mth.sin(th), ty = Mth.cos(th), ang = th + (float) Math.PI / 2f;
            for (int line = 0; line < 3; line++) {
                float r = S * (0.56f + 0.06f * line);
                float cx = Mth.cos(th) * r, cy = Mth.sin(th) * r;
                boolean yang = ((TRIGRAMS[k] >> line) & 1) == 1;
                int cc = c.c(light, 0.95f * A * appear);
                if (yang) bar(vc, m, cx, cy, ang, S * 0.11f, S * 0.016f, cc);
                else {
                    bar(vc, m, cx + tx * S * 0.068f, cy + ty * S * 0.068f, ang, S * 0.042f, S * 0.016f, cc);
                    bar(vc, m, cx - tx * S * 0.068f, cy - ty * S * 0.068f, ang, S * 0.042f, S * 0.016f, cc);
                }
            }
        }
        // 内环
        ring(vc, m, S * 0.40f, S * 0.42f, c.c(col, 0.9f * A), c.c(col, 0.9f * A), a0, a0 + TAU * clamp01(draw * 1.3f - 0.3f), 48);
        // 太极
        if (draw > 0.55f) {
            float yy = clamp01((draw - 0.55f) / 0.3f);
            float Y = S * 0.25f * easeOutBack(yy), phi = -t * 0.05f * (1f + 2.5f * charge);
            int L = c.c(light, A), D = c.c(dark, A);
            disc(vc, m, 0, 0, Y, D, D, 40);
            halfDisc(vc, m, 0, 0, Y, phi, L, 24);
            float hx = Mth.cos(phi) * Y / 2f, hy = Mth.sin(phi) * Y / 2f;
            disc(vc, m, hx, hy, Y / 2f, L, L, 28);
            disc(vc, m, -hx, -hy, Y / 2f, D, D, 28);
            disc(vc, m, hx, hy, Y / 7f, D, D, 16);
            disc(vc, m, -hx, -hy, Y / 7f, L, L, 16);
            ring(vc, m, Y, Y * 1.07f, c.c(col, A), c.c(col, A), 40);
        }
        c.s.popPose();

        // GLOW
        c.pushFrame(0);
        m = c.m();
        vc = c.glow();
        float breathe = charge >= 1f ? 0.5f + 0.5f * Mth.sin(t * 0.5f) : 0f;
        float g = (0.18f + 0.3f * charge + 0.2f * breathe + 0.6f * flare) * A;
        ring(vc, m, S * 0.88f, S * 0.975f, c.c(col, 0f), c.c(col, g), a0, a1, 64);
        ring(vc, m, S * 0.975f, S * 1.12f, c.c(col, g), c.c(col, 0f), a0, a1, 64);
        disc(vc, m, 0, 0, S * 0.55f, c.c(col, (0.12f + 0.25f * charge + 0.5f * flare) * A), c.c(col, 0f), 40);
        for (int k = 0; k < 3; k++) {
            float a = t * 0.09f + TAU * k / 3f;
            sparkle(vc, m, Mth.cos(a) * S * 1.05f, Mth.sin(a) * S * 1.05f, S * (0.12f + 0.06f * charge), t * 0.2f,
                    c.c(0xFFFFFF, 0.85f * A * draw), c.c(col, 0f));
        }
        if (flare > 0f) {
            float r = S * (1.0f + 0.5f * (1f - flare));
            ring(vc, m, r, r + S * 0.12f * flare, c.c(0xFFFFFF, flare), c.c(col, 0f), 64);
        }
        c.s.popPose();
    }

    // ───────────────────────── 豪火球 ─────────────────────────

    private static final int[] FIRE = {0xA3140A, 0xFF4E0A, 0xFFA62B, 0xFFF1C2};
    private static final float[] FIRE_R = {1f, 0.84f, 0.63f, 0.38f};

    private void fireLayers(VertexConsumer vc, Matrix4f m, Ctx c, float x, float y, float r, float phase,
                            float tx, float ty, float tail, float a, int from) {
        for (int i = from; i < 4; i++) {
            float spike = 0.24f - i * 0.06f, wob = 0.1f - i * 0.015f;
            blob(vc, m, x, y, r * FIRE_R[i], wob, spike, phase * (1f + i * 0.15f) + i * 1.7f, tx, ty, tail * (1f - i * 0.18f),
                    c.c(FIRE[i], a), c.c(FIRE[i], a), 40);
        }
    }

    private void fireball(Ctx c) {
        float t = c.t, S = c.e.size() * c.e.flightScale();
        float[] sd = c.screenDir();
        float lateral = Mth.sqrt(sd[0] * sd[0] + sd[1] * sd[1]);
        Vector3f fwd = c.rot.transform(new Vector3f(0, 0, 1));
        // 主火球 + 4 团拖尾，按离相机远近从远到近画（无深度写入，靠顺序遮挡）
        int n = 5;
        float[][] pts = new float[n][4];
        for (int k = 0; k < n; k++) {
            float back = k == 0 ? 0 : S * 0.75f * k;
            float wob = k == 0 ? 0 : 0.12f * S * Mth.sin(t * 0.8f + k * 2f);
            float px = -fwd.x * back + wob, py = -fwd.y * back + wob * 0.7f, pz = -fwd.z * back;
            float dx = c.toCam.x - px, dy = c.toCam.y - py, dz = c.toCam.z - pz;
            pts[k] = new float[]{px, py, pz, dx * dx + dy * dy + dz * dz, k};
        }
        java.util.Arrays.sort(pts, (a, b) -> Float.compare(b[3], a[3]));
        VertexConsumer vc = c.toon();
        for (float[] p : pts) {
            int k = (int) p[4];
            c.pushBillboardWorld(p[0], p[1], p[2]);
            if (k == 0) fireLayers(vc, c.m(), c, 0, 0, S, t * 0.9f, -sd[0], -sd[1], 1.3f * lateral, 1f, 0);
            else {
                float r = S * (0.66f - 0.11f * k);
                fireLayers(vc, c.m(), c, 0, 0, r, t * 1.1f + k * 3f, -sd[0], -sd[1], 0.6f * lateral, 1f - 0.17f * k, k >= 3 ? 1 : 0);
            }
            c.s.popPose();
        }
        // GLOW：光晕 + 火星
        vc = c.glow();
        c.pushBillboard(0, 0, 0);
        disc(vc, c.m(), 0, 0, S * 1.7f, c.c(0xFF7A1A, 0.4f), c.c(0xFF3A00, 0f), 32);
        c.s.popPose();
        c.pushFrame(0);
        Matrix4f m = c.m();
        float[] side = c.axialSide(0);
        int id = c.e.getId();
        for (int i = 0; i < 10; i++) {
            float cyc = (t * 0.12f + hash(id + i)) % 1f;
            float a = TAU * hash(id * 5 + i * 7);
            float r = S * (0.5f + 0.7f * hash(id * 3 + i));
            float z = -S * (0.6f + 4f * cyc);
            streak(vc, m, Mth.cos(a) * r, Mth.sin(a) * r, z, S * 0.35f, S * 0.05f, side[0], side[1],
                    c.c(0xFFD27A, 0.9f * (1f - cyc)), c.c(0xFF5A00, 0f));
        }
        c.s.popPose();
    }

    // ───────────────────────── 豪火球：爆炸 ─────────────────────────

    private void burst(Ctx c) {
        float t = c.t, p = clamp01(t / 24f);
        float R = 1.2f + 4.8f * easeOut(t / 7f);
        float inside = c.camDist < R + 0.5f ? 0.3f : 1f;
        int id = c.e.getId();

        VertexConsumer vc = c.toon();
        // 烟（先画在火的后面）
        if (p > 0.3f) {
            float sp = (p - 0.3f) / 0.7f;
            for (int k = 0; k < 9; k++) {
                float a = TAU * k / 9f + hash(id + k) * 0.6f;
                float d = R * (0.55f + 0.25f * hash(id * 3 + k)) + sp * 1.5f;
                float x = Mth.cos(a) * d, y = Mth.sin(a) * d * 0.7f + sp * 2.2f;
                float r = R * (0.32f + 0.12f * hash(id * 7 + k)) * (0.8f + 0.5f * sp);
                c.pushBillboard(0, 0, 0);
                float sa = 0.85f * (1f - sp) * inside;
                blob(vc, c.m(), x, y, r * 1.08f, 0.12f, 0.04f, k * 2f + t * 0.1f, 0, 0, 0, c.c(0x2E2826, sa), c.c(0x2E2826, sa), 28);
                blob(vc, c.m(), x, y + r * 0.08f, r, 0.12f, 0.04f, k * 2f + t * 0.1f, 0, 0, 0, c.c(0x5E5550, sa), c.c(0x5E5550, sa), 28);
                c.s.popPose();
            }
        }
        // 火团：层级优先（先所有暗红，再橙、黄、白芯），各层由内向外依次缩没
        float[] end = {0.9f, 0.7f, 0.5f, 0.3f};
        int subs = 6;
        c.pushBillboard(0, 0, 0);
        Matrix4f m = c.m();
        for (int layer = 0; layer < 4; layer++) {
            float f = clamp01((end[layer] - p) / (end[layer] * 0.5f));
            if (f <= 0f) continue;
            float a = inside * (layer == 0 ? 0.95f : 1f);
            for (int k = -1; k < subs; k++) {
                float x = 0, y = 0, r = R * 0.72f;
                if (k >= 0) {
                    float ang = TAU * k / subs + hash(id * 11 + k) * 0.8f;
                    float d = R * (0.38f + 0.12f * hash(id * 13 + k));
                    x = Mth.cos(ang) * d; y = Mth.sin(ang) * d; r = R * (0.42f + 0.14f * hash(id * 17 + k));
                }
                blob(vc, m, x, y, r * FIRE_R[layer] * f, 0.1f, 0.22f - layer * 0.05f, t * 0.6f + k * 1.3f + layer,
                        0, 1, 0.25f, c.c(FIRE[layer], a), c.c(FIRE[layer], a), 36);
            }
        }
        c.s.popPose();

        // 地面冲击环（水平）+ 范围圈
        c.s.pushPose();
        c.s.mulPose(new Quaternionf().rotationX((float) Math.PI / 2f));
        m = c.m();
        float rr = 10f * easeOut(t / 12f);
        ring(vc, m, rr - 0.12f, rr, c.c(0xFFF6D8, 0.9f * (1f - p)), c.c(0xFFF6D8, 0.9f * (1f - p)), 96);
        c.s.popPose();

        // GLOW
        vc = c.glow();
        c.pushBillboard(0, 0, 0);
        m = c.m();
        if (t < 4f) disc(vc, m, 0, 0, R * 1.6f, c.c(0xFFFFFF, 0.9f * (1f - t / 4f) * inside), c.c(0xFFD080, 0f), 40);
        if (p < 0.5f) starburst(vc, m, 18, R * 0.4f, R * 2.1f, id * 0.7f, id, c.c(0xFFC060, 0.6f * (1f - p * 2f) * inside), c.c(0xFF6000, 0f));
        disc(vc, m, 0, 0, R * 1.4f, c.c(0xFF7A1A, 0.35f * (1f - p) * inside), c.c(0xFF3A00, 0f), 36);
        c.s.popPose();
        c.s.pushPose();
        c.s.mulPose(new Quaternionf().rotationX((float) Math.PI / 2f));
        m = c.m();
        ring(vc, m, rr - 0.9f * (1f - p), rr, c.c(0xFF9A3C, 0f), c.c(0xFFB347, 0.75f * (1f - p)), 96);
        ring(vc, m, 9.75f, 10f, c.c(0xFF7A1A, 0f), c.c(0xFF7A1A, 0.35f * (1f - p)), 96);
        c.s.popPose();
    }

    // ───────────────────────── 蓄力：波动光团 / 口中火苗 ─────────────────────────

    private void converge(VertexConsumer vc, Matrix4f m, float t, int n, float rOut, float rIn, int rgb, float a, int seed, Ctx c) {
        float period = 9f;
        for (int k = 0; k < n; k++) {
            float tt = t + k * period / n;
            int cyc = (int) (tt / period);
            float f = (tt % period) / period;
            float ang = TAU * hash(seed + k * 13 + cyc * 101);
            float r = Mth.lerp(f * f, rOut, rIn);
            float len = (rOut - rIn) * 0.35f * (1f - 0.5f * f);
            float al = a * Mth.sin((float) Math.PI * f);
            needle(vc, m, Mth.cos(ang) * r, Mth.sin(ang) * r, Mth.cos(ang) * (r + len), Mth.sin(ang) * (r + len),
                    rOut * 0.018f, c.c(rgb, al), c.c(rgb, 0f));
        }
    }

    private void chargeOrb(Ctx c) {
        float t = c.t, ch = clamp01(t / 40f);
        float S = (0.14f + 0.3f * ch) * c.scale * (1f + 0.06f * Mth.sin(t * 2.1f));
        c.pushBillboard(0, 0, 0);
        Matrix4f m = c.m();
        VertexConsumer vc = c.toon();
        int[] col = {0x1B4FE0, 0x2F93FF, 0x9FE6FF, 0xFFFFFF};
        float[] rr = {1f, 0.8f, 0.58f, 0.36f};
        for (int i = 0; i < 4; i++)
            blob(vc, m, 0, 0, S * rr[i], 0.08f, (0.06f + 0.1f * ch) * (1f - i * 0.2f), t * (1.4f + i * 0.3f) + i * 2f, 0, 0, 0,
                    c.c(col[i], i == 0 ? 0.65f : 0.95f), c.c(col[i], i == 0 ? 0.65f : 0.95f), 36);
        c.s.popPose();
        c.pushBillboard(0, 0, 0);
        m = c.m();
        vc = c.glow();
        disc(vc, m, 0, 0, S * 2.4f, c.c(0x3C8CFF, 0.3f + 0.25f * ch), c.c(0x3C8CFF, 0f), 32);
        starburst(vc, m, 10, S * 0.7f, S * (1.8f + 0.5f * ch), t * 0.12f, (int) (t / 2f) * 3 + c.e.getId(),
                c.c(0x9FE6FF, 0.2f + 0.25f * ch), c.c(0x2F93FF, 0f));
        converge(vc, m, t, c.ownerFP ? 5 : 9, (c.ownerFP ? 0.7f : 1.3f) * c.scale, S * 1.1f, 0xCFF4FF, 0.85f, c.e.getId() * 7, c);
        if (ch >= 1f) {
            float f = (t % 10f) / 10f;
            ring(vc, m, S * (1f + 1.8f * f), S * (1.1f + 1.8f * f), c.c(0xE6FBFF, 0.7f * (1f - f)), c.c(0x4FA8FF, 0f), 40);
        }
        c.s.popPose();
    }

    private void chargeFire(Ctx c) {
        float t = c.t, ch = clamp01(t / 40f);
        float S = (0.06f + 0.12f * ch) * c.scale;
        c.pushBillboard(0, 0, 0);
        Matrix4f m = c.m();
        VertexConsumer vc = c.toon();
        fireLayers(vc, m, c, 0, 0, S, t * 1.6f, 0, 1, 1.1f + 0.3f * Mth.sin(t * 0.9f), 1f, 0);
        c.s.popPose();
        c.pushBillboard(0, 0, 0);
        m = c.m();
        vc = c.glow();
        disc(vc, m, 0, 0, S * 3.2f, c.c(0xFF7A1A, 0.3f + 0.25f * ch), c.c(0xFF3A00, 0f), 28);
        if (!c.ownerFP) converge(vc, m, t, 7, 1.1f, S * 1.2f, 0xFFB050, 0.8f, c.e.getId() * 5, c);
        c.s.popPose();
    }

    // ───────────────────────── 出手冲击环 ─────────────────────────

    private void launchRing(Ctx c) {
        float t = c.t, S = c.e.size();
        int col = c.e.color() & 0xFFFFFF;
        c.pushFrame(0);
        Matrix4f m = c.m();
        VertexConsumer vc = c.toon();
        for (int k = 0; k < 2; k++) {
            float p = clamp01((t - k * 2f) / 7f);
            if (p <= 0f || p >= 1f) continue;
            float r = S * (0.35f + 1.6f * easeOut(p)) * (1f - 0.25f * k);
            ring(vc, m, r, r + 0.05f * S, c.c(0xFFFFFF, 0.95f * (1f - p)), c.c(0xFFFFFF, 0.95f * (1f - p)), 56);
        }
        vc = c.glow();
        for (int k = 0; k < 2; k++) {
            float p = clamp01((t - k * 2f) / 7f);
            if (p <= 0f || p >= 1f) continue;
            float r = S * (0.35f + 1.6f * easeOut(p)) * (1f - 0.25f * k);
            float w = 0.25f * S * (1f - p);
            ring(vc, m, r - w, r, c.c(col, 0f), c.c(col, 0.7f * (1f - p)), 56);
            ring(vc, m, r, r + w * 0.6f, c.c(col, 0.7f * (1f - p)), c.c(col, 0f), 56);
        }
        c.s.popPose();
    }

    // ───────────────────────── 挥砍残光 ─────────────────────────

    private void swing(Ctx c) {
        float t = c.t;
        boolean wind = c.e.size() > 1.5f;
        float roll = wind ? -0.75f : -0.12f;
        float q = easeOut(t / 2.2f);
        float t0 = wind ? -1f : 1f - 2f * q, t1 = wind ? -1f + 2f * q : 1f;
        float fade = clamp01(1f - (t - 2.5f) / 4.5f);
        float halfW = wind ? 1.5f : 1.25f, sag = wind ? 0.65f : 0.45f, th = wind ? 0.6f : 0.42f, depth = wind ? 0.6f : 0.5f;
        int col = wind ? 0x5BE3A8 : 0x6FCBFF;
        c.pushFrame(roll);
        Matrix4f m = c.m();
        VertexConsumer vc = c.toon();
        crescent(vc, m, halfW, sag, th, depth, 0f, 1f, c.c(col, 0.8f * fade), c.c(col, 0f), t0, t1, 36);
        crescent(vc, m, halfW, sag, th, depth, 0f, 0.28f, c.c(0xFFFFFF, 0.95f * fade), c.c(0xFFFFFF, 0.6f * fade), t0, t1, 36);
        vc = c.glow();
        crescent(vc, m, halfW * 1.06f, sag + 0.06f, th * 1.5f, depth, -0.2f, 1f, c.c(col, 0.45f * fade), c.c(col, 0f), t0, t1, 30);
        c.s.popPose();
    }

    // ───────────────────────── 命中特效 ─────────────────────────

    private void hitSlash(Ctx c) {
        float t = c.t, p = clamp01(t / 11f);
        c.pushBillboard(0, 0, 0);
        Matrix4f m = c.m();
        VertexConsumer vc = c.toon();
        float[] angs = {0.45f, -1.05f};
        for (int k = 0; k < 2; k++) {
            float tk = t - k * 1.5f;
            if (tk <= 0f) continue;
            float L = 1.7f * easeOut(tk / 2f), w = 0.13f * clamp01(1f - (tk - 1f) / 7f);
            if (w <= 0f) continue;
            float cx = Mth.cos(angs[k]), cy = Mth.sin(angs[k]);
            needle(vc, m, -cx * L, -cy * L, cx * L, cy * L, w * 1.8f, c.c(0x3AA8FF, 0.9f), c.c(0x3AA8FF, 0f));
            needle(vc, m, -cx * L, -cy * L, cx * L, cy * L, w * 0.7f, c.c(0xFFFFFF, 1f), c.c(0xFFFFFF, 0.2f));
        }
        vc = c.glow();
        float r = 0.3f + 1.4f * easeOut(p * 1.4f);
        ring(vc, m, r, r + 0.12f * (1f - p), c.c(0x8FE3FF, 0.8f * (1f - p)), c.c(0x3AA8FF, 0f), 40);
        disc(vc, m, 0, 0, 0.9f, c.c(0x8FE3FF, 0.6f * (1f - p)), c.c(0x3AA8FF, 0f), 24);
        for (int k = 0; k < 4; k++) {
            float a = TAU * hash(c.e.getId() + k * 9), d = 0.5f + 0.7f * hash(c.e.getId() * 3 + k) + p * 0.6f;
            sparkle(vc, m, Mth.cos(a) * d, Mth.sin(a) * d, 0.28f * (1f - p), t * 0.15f, c.c(0xFFFFFF, 1f - p), c.c(0x3AA8FF, 0f));
        }
        c.s.popPose();
    }

    private void hitWind(Ctx c) {
        float t = c.t, p = clamp01(t / 17f);
        VertexConsumer vc = c.toon();
        // 上旋的倾斜风环：每层三段弧，交替反向旋转
        for (int j = 0; j < 4; j++) {
            float y = -0.6f + j * 0.45f + p * 1.2f;
            float r = 0.45f + j * 0.22f + easeOut(p) * 1.3f;
            float spin = t * 0.45f * (j % 2 == 0 ? 1f : -1f) + j;
            float a = 0.85f * (1f - p);
            c.s.pushPose();
            c.s.translate(0, y, 0);
            c.s.mulPose(new Quaternionf().rotationX((float) Math.PI / 2f).rotateZ(spin));
            Matrix4f m = c.m();
            for (int s = 0; s < 3; s++) {
                float a0 = TAU * s / 3f;
                coneArc(vc, m, r, 0.14f, 0.2f, a0, a0 + 1.4f, c.c(0xB8FFE0, a), c.c(0x1FC48A, a * 0.3f));
            }
            c.s.popPose();
        }
        if (t < 4f) {
            vc = c.glow();
            c.pushBillboard(0, 0, 0);
            disc(vc, c.m(), 0, 0, 1.2f, c.c(0x8DF2C8, 0.7f * (1f - t / 4f)), c.c(0x1FC48A, 0f), 24);
            sparkle(vc, c.m(), 0, 0, 0.9f * (1f - t / 4f), 0.3f, c.c(0xFFFFFF, 1f - t / 4f), c.c(0x8DF2C8, 0f));
            c.s.popPose();
        }
    }

    /** 斜面弧带：内沿在平面上，外沿向 -Z（旋转后为向上）抬起，侧面也看得见 */
    private static void coneArc(VertexConsumer vc, Matrix4f m, float r, float w, float lift, float a0, float a1, int cIn, int cOut) {
        int n = 14;
        for (int i = 0; i < n; i++) {
            float t0 = a0 + (a1 - a0) * i / n, t1 = a0 + (a1 - a0) * (i + 1) / n;
            float e0 = clamp01(Math.min(i, n - i) / 3f), e1 = clamp01(Math.min(i + 1, n - i - 1) / 3f);
            float c0 = Mth.cos(t0), s0 = Mth.sin(t0), c1 = Mth.cos(t1), s1 = Mth.sin(t1);
            quad(vc, m, c0 * r, s0 * r, 0, fadeA(cIn, e0), c0 * (r + w), s0 * (r + w), -lift, fadeA(cOut, e0),
                    c1 * (r + w), s1 * (r + w), -lift, fadeA(cOut, e1), c1 * r, s1 * r, 0, fadeA(cIn, e1));
        }
    }

    private void hitWave(Ctx c) {
        float t = c.t, p = clamp01(t / 13f), S = Math.max(0.75f, c.e.size());
        float pop = easeOutBack(t / 3f), fade = clamp01(1f - (t - 3f) / 9f);
        c.pushBillboard(0, 0, 0);
        Matrix4f m = c.m();
        VertexConsumer vc = c.toon();
        starburst(vc, m, 16, S * 0.5f * pop, S * 3.0f * pop, 0.4f, c.e.getId() * 3, c.c(0x2F93FF, 0.55f * fade), c.c(0x1B4FE0, 0f));
        starburst(vc, m, 14, S * 0.4f * pop, S * 2.3f * pop, 0f, c.e.getId(), c.c(0xFFFFFF, fade), c.c(0x7FD8FF, 0.2f * fade));
        vc = c.glow();
        disc(vc, m, 0, 0, S * 2.6f, c.c(0x3C8CFF, 0.5f * (1f - p)), c.c(0x3C8CFF, 0f), 32);
        float r = S * (0.5f + 3f * easeOut(p));
        ring(vc, m, r, r + S * 0.25f * (1f - p), c.c(0xFFFFFF, 0.9f * (1f - p)), c.c(0x5FC8FF, 0f), 56);
        c.s.popPose();
        // 沿冲击方向扩散的环
        c.pushFrame(0);
        float r2 = S * (0.4f + 2.4f * easeOut(p));
        ring(vc, c.m(), r2 - S * 0.2f * (1f - p), r2, c.c(0x5FC8FF, 0f), c.c(0xBFF3FF, 0.8f * (1f - p)), 56);
        c.s.popPose();
    }

    private void hitSeal(Ctx c) {
        float t = c.t, p = clamp01(t / 10f);
        int col = c.e.color() & 0xFFFFFF;
        c.pushBillboard(0, 0, 0);
        Matrix4f m = c.m();
        VertexConsumer vc = c.toon();
        float pop = easeOutBack(t / 2.5f);
        starburst(vc, m, 10, 0.25f * pop, 1.3f * pop, t * 0.05f, c.e.getId(), c.c(0xFFFFFF, 1f - p), c.c(col, 0.2f * (1f - p)));
        vc = c.glow();
        disc(vc, m, 0, 0, 1.4f, c.c(col, 0.55f * (1f - p)), c.c(col, 0f), 24);
        float r = 0.3f + 1.5f * easeOut(p);
        ring(vc, m, r, r + 0.1f * (1f - p), c.c(0xFFFFFF, 0.8f * (1f - p)), c.c(col, 0f), 40);
        c.s.popPose();
    }
}

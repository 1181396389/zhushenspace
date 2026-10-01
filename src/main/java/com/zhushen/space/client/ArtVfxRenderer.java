package com.zhushen.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.client.fx.ZsRenderTypes;
import com.zhushen.space.entity.ModEntities;
import com.zhushen.space.entity.art.ArtVfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
 * 非弹体技艺的动漫风 v3 特效（与弹体技艺同一画风：程序生成的纯色几何、赛璐璐色阶 + 白芯 + 加法光晕，每帧动态变形）。
 * 14 个技艺各有独立的造型语言：
 * <pre>
 * 精神冲击 第三只眼 + 精神涟漪光束      精神震荡 陀螺仪震环 + 信号故障条纹      魔能爆 六芒魔法阵 + 奥术光枪 + 碎阵
 * 五行道法 飞符 → 雷劈 / 旋风 / 水柱 / 火柱 / 地刺                      生物闪电 逐帧重生成的分叉电弧
 * 凤仙火 多枚弧线小火球                  灵力治疗 螺旋光带 + 十字光点          息法 法轮 + 吐纳光环（按已习得的息分层）
 * 夜叉空行 背后展开的羽翼 + 上升气流     初级防护 由下而上拼合的六角结界      无视我 故障风碎散残影
 * 黄泉活力 环绕的鬼火 + 足下幽焰         基础掌法 向前推出的巨大掌印          起死回生 天降光柱 + 莲台 + 光羽
 * </pre>
 * 坐标：所有绘制都以“锚点”（跟随实体的脚底 / 实体自身位置）为原点、世界轴向。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ArtVfxRenderer extends EntityRenderer<ArtVfx> {
    public ArtVfxRenderer(EntityRendererProvider.Context c) { super(c); shadowRadius = 0; }

    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ModEntities.ART_VFX.get(), ArtVfxRenderer::new);
    }

    /**
     * 两种特效渲染类型注册为独立缓冲：同一帧内 TOON / GLOW 交替取用时互不结束对方的批次
     * （共享缓冲下切换类型会提前提交上一批，已取得的 VertexConsumer 随之失效）。
     */
    @SubscribeEvent public static void buffers(net.neoforged.neoforge.client.event.RegisterRenderBuffersEvent e) {
        e.registerRenderBuffer(ZsRenderTypes.TOON);
        e.registerRenderBuffer(ZsRenderTypes.GLOW);
    }

    @Override public ResourceLocation getTextureLocation(ArtVfx e) { return InventoryMenu.BLOCK_ATLAS; }

    @Override public boolean shouldRender(ArtVfx e, Frustum frustum, double x, double y, double z) {
        // 光束可长达 20 米、附身特效跟随别的实体：只按距离裁剪
        return e.distanceToSqr(x, y, z) < 96 * 96;
    }

    private static final int WHITE = 0xFFFFFF;

    // ───────────────────────── 每帧上下文 ─────────────────────────

    private final class C {
        final ArtVfx e;
        final PoseStack s;
        final MultiBufferSource buf;
        final float t, life, alpha;
        final Vector3f cam;      // 相机相对锚点
        final Quaternionf camQ;
        final Entity follow, owner;
        final boolean selfFP, ownerFP;
        final float h, yaw;      // 跟随实体高度、身体朝向（弧度）
        final int col, variant;

        C(ArtVfx e, PoseStack s, MultiBufferSource buf, float t, float alpha, Vector3f cam, Entity follow, Entity owner,
          boolean selfFP, boolean ownerFP, float h, float yaw) {
            this.e = e; this.s = s; this.buf = buf; this.t = t; this.life = e.life(); this.alpha = alpha; this.cam = cam;
            this.camQ = entityRenderDispatcher.cameraOrientation();
            this.follow = follow; this.owner = owner; this.selfFP = selfFP; this.ownerFP = ownerFP; this.h = h; this.yaw = yaw;
            this.col = e.color() & 0xFFFFFF; this.variant = e.variant();
        }

        VertexConsumer toon() { return buf.getBuffer(ZsRenderTypes.TOON); }
        VertexConsumer glow() { return buf.getBuffer(ZsRenderTypes.GLOW); }
        int c(int rgb, float a) { return argb(rgb, Mth.clamp(a, 0f, 1f) * alpha); }
        Matrix4f m() { return s.last().pose(); }

        /** 面向相机的平面（+X 屏幕右、+Y 屏幕上） */
        void bb(float x, float y, float z) { s.pushPose(); s.translate(x, y, z); s.mulPose(camQ); }
        void bb(Vector3f p) { bb(p.x, p.y, p.z); }
        /** 法线为 n 的平面（局部 +Z = n） */
        void plane(float x, float y, float z, float nx, float ny, float nz) {
            s.pushPose(); s.translate(x, y, z);
            s.mulPose(new Quaternionf().rotationTo(0, 0, 1, nx, ny, nz));
        }
        /** 水平面（局部 XY = 世界 XZ） */
        void flat(float y) { s.pushPose(); s.translate(0, y, 0); s.mulPose(new Quaternionf().rotationX((float) Math.PI / 2f)); }
        /** 身体坐标系：+Z 身前、+Y 上、+X 身体左侧 */
        void body() { s.pushPose(); s.mulPose(new Quaternionf().rotationY(-yaw)); }
        void pop() { s.popPose(); }

        /** 进出场淡入淡出 */
        float env(float in, float out) {
            float a = in <= 0 ? 1f : clamp01(t / in);
            float b = out <= 0 ? 1f : clamp01((life - t) / out);
            return a * b;
        }

        /** 身体坐标系中的点 → 锚点世界坐标 */
        Vector3f bodyPt(float x, float y, float z) { return new Quaternionf().rotationY(-yaw).transform(new Vector3f(x, y, z)); }
    }

    // ───────────────────────── 入口 ─────────────────────────

    @Override public void render(ArtVfx e, float entityYaw, float partial, PoseStack stack, MultiBufferSource buffers, int light) {
        float t = e.tickCount + partial;
        if (t > e.life() + 1) return;
        Minecraft mc = Minecraft.getInstance();
        Entity follow = e.followId() >= 0 ? e.level().getEntity(e.followId()) : null;
        if (e.followId() >= 0 && follow == null) return;
        Entity owner = e.level().getEntity(e.ownerId());
        Vec3 pos = e.getPosition(partial), anchor = pos;
        stack.pushPose();
        if (follow != null) {
            anchor = follow.getPosition(partial);
            stack.translate(anchor.x - pos.x, anchor.y - pos.y, anchor.z - pos.z);
        }
        Entity camE = mc.getCameraEntity();
        boolean fp = mc.options.getCameraType().isFirstPerson();
        boolean selfFP = fp && follow != null && follow == camE;
        boolean ownerFP = fp && owner != null && owner == camE;
        Vec3 camPos = entityRenderDispatcher.camera.getPosition();
        Vector3f cam = new Vector3f((float) (camPos.x - anchor.x), (float) (camPos.y - anchor.y), (float) (camPos.z - anchor.z));
        Entity bodyOf = follow != null ? follow : owner;
        float h = follow != null ? follow.getBbHeight() : 1.8f;
        float yawDeg = bodyOf instanceof LivingEntity le ? Mth.rotLerp(partial, le.yBodyRotO, le.yBodyRot) : e.getYRot();
        float alpha = 1f;
        if (e.fadeAt() >= 0) alpha = clamp01(1f - (t - e.fadeAt()) / Math.max(1f, e.life() - e.fadeAt()));
        C c = new C(e, stack, buffers, t, alpha, cam, follow, owner, selfFP, ownerFP, h, (float) Math.toRadians(yawDeg));

        switch (e.kind()) {
            case ArtVfx.MIND_BEAM -> mindBeam(c);
            case ArtVfx.MIND_QUAKE -> mindQuake(c);
            case ArtVfx.ARCANE -> arcane(c);
            case ArtVfx.ELEMENT -> element(c);
            case ArtVfx.BIO_BOLT -> bioBolt(c);
            case ArtVfx.PHOENIX -> phoenix(c);
            case ArtVfx.HEAL -> heal(c);
            case ArtVfx.BREATH -> breath(c);
            case ArtVfx.YAKSHA -> yaksha(c);
            case ArtVfx.WARD -> ward(c);
            case ArtVfx.VANISH -> vanish(c);
            case ArtVfx.NETHER -> nether(c);
            case ArtVfx.PALM -> palm(c);
            case ArtVfx.REVIVE -> revive(c);
            case ArtVfx.THUNDER_BLADE -> thunderBlade(c);
            case ArtVfx.THUNDER_MARK -> thunderMark(c);
            case ArtVfx.THUNDER_STRIKE -> thunderStrike(c);
            case ArtVfx.LUMEN -> lumen(c);
            case ArtVfx.LIGHT_ORBS -> lightOrbs(c);
            case ArtVfx.FROST_CLAW -> frostClaw(c);
            default -> {}
        }
        stack.popPose();

        // 被精神震荡击中的本地玩家：屏幕震颤
        if (!e.clientFxFired && e.kind() == ArtVfx.MIND_QUAKE && follow != null && follow == camE) {
            e.clientFxFired = true;
            ClientArtScreenFx.impact(0.55f);
            ClientCameraShake.trigger(1.3f, 10);
        }
        if (!e.clientFxFired && e.kind() == ArtVfx.THUNDER_STRIKE && t >= 1.5f) {
            e.clientFxFired = true;
            float k = clamp01(1f - cam.length() / 28f);
            if (k > 0.05f) { ClientArtScreenFx.impact(0.6f * k); ClientCameraShake.trigger(1.3f * k, 9); }
        }
        if (!e.clientFxFired && e.kind() == ArtVfx.ELEMENT && e.variant() == 0 && t >= 4) {
            e.clientFxFired = true;
            float k = clamp01(1f - cam.length() / 24f);
            if (k > 0.05f) { ClientArtScreenFx.impact(0.5f * k); ClientCameraShake.trigger(1.1f * k, 8); }
        }
    }

    // ───────────────────────── 几何工具 ─────────────────────────

    /** 面向相机的 3D 条带：穿过各点，宽度 / 颜色逐点给出 */
    private static void rib(VertexConsumer vc, Matrix4f m, Vector3f[] p, float[] w, int[] col, Vector3f cam) {
        int n = p.length;
        if (n < 2) return;
        Vector3f[] side = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            Vector3f tan = new Vector3f(p[Math.min(n - 1, i + 1)]).sub(p[Math.max(0, i - 1)]);
            Vector3f toC = new Vector3f(cam).sub(p[i]);
            Vector3f sd = tan.cross(toC, new Vector3f());
            float l = sd.length();
            if (l < 1e-6f) sd.set(1, 0, 0); else sd.div(l);
            side[i] = sd.mul(w[i]);
        }
        for (int i = 0; i < n - 1; i++) {
            Vector3f a = p[i], b = p[i + 1], sa = side[i], sb = side[i + 1];
            quad(vc, m, a.x - sa.x, a.y - sa.y, a.z - sa.z, col[i], a.x + sa.x, a.y + sa.y, a.z + sa.z, col[i],
                    b.x + sb.x, b.y + sb.y, b.z + sb.z, col[i + 1], b.x - sb.x, b.y - sb.y, b.z - sb.z, col[i + 1]);
        }
    }

    /** 条带：宽度 w0→w1、颜色 c0→c1 线性渐变（alpha 也渐变） */
    private static void ribLerp(VertexConsumer vc, Matrix4f m, Vector3f[] p, float w0, float w1, int c0, int c1, Vector3f cam) {
        int n = p.length;
        float[] w = new float[n];
        int[] cc = new int[n];
        for (int i = 0; i < n; i++) {
            float f = n == 1 ? 0 : i / (float) (n - 1);
            w[i] = Mth.lerp(f, w0, w1);
            cc[i] = lerpArgb(c0, c1, f);
        }
        rib(vc, m, p, w, cc, cam);
    }

    /** 两端尖、中段宽的条带（光枪 / 羽毛 / 火舌） */
    private static void ribSpindle(VertexConsumer vc, Matrix4f m, Vector3f a, Vector3f b, float w, float peak, int cEnd, int cMid, Vector3f cam) {
        Vector3f mid = new Vector3f(a).lerp(b, peak);
        rib(vc, m, new Vector3f[]{a, mid, b}, new float[]{0.002f, w, 0.002f}, new int[]{cEnd, cMid, cEnd}, cam);
    }

    private static int lerpArgb(int a, int b, float f) {
        int aa = (a >>> 24) & 255, ba = (b >>> 24) & 255;
        int al = (int) Mth.lerp(f, aa, ba);
        return (al << 24) | mix(a & 0xFFFFFF, b & 0xFFFFFF, f);
    }

    private static Vector3f[] line(Vector3f a, Vector3f b, int n) {
        Vector3f[] p = new Vector3f[n + 1];
        for (int i = 0; i <= n; i++) p[i] = new Vector3f(a).lerp(b, i / (float) n);
        return p;
    }

    /** 与方向 d 垂直的两根单位向量 */
    private static Vector3f[] perp(Vector3f d) {
        Vector3f n = new Vector3f(d).normalize();
        Vector3f up = Math.abs(n.y) > 0.95f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f p1 = n.cross(up, new Vector3f()).normalize();
        Vector3f p2 = n.cross(p1, new Vector3f()).normalize();
        return new Vector3f[]{p1, p2};
    }

    /** 闪电折线：a → b，垂直方向随机抖动（两端收拢） */
    private static Vector3f[] boltPts(Vector3f a, Vector3f b, int segs, float amp, int seed) {
        Vector3f d = new Vector3f(b).sub(a);
        Vector3f[] pp = perp(d.lengthSquared() < 1e-6f ? new Vector3f(0, 1, 0) : d);
        Vector3f[] p = new Vector3f[segs + 1];
        for (int i = 0; i <= segs; i++) {
            float f = i / (float) segs;
            float k = (i == 0 || i == segs) ? 0 : amp * Mth.sin((float) Math.PI * f);
            float u = (hash(seed + i * 17) - 0.5f) * 2f * k, v = (hash(seed * 3 + i * 29 + 5) - 0.5f) * 2f * k;
            p[i] = new Vector3f(a).lerp(b, f).add(new Vector3f(pp[0]).mul(u)).add(new Vector3f(pp[1]).mul(v));
        }
        return p;
    }

    /** 3D 正多边形面片（中心 c、平面基 e1 / e2）：半透明填充 + 发光描边 */
    private static void polyPanel(VertexConsumer fill, VertexConsumer edge, Matrix4f m, Vector3f c, Vector3f e1, Vector3f e2,
                                  float r, int sides, float rot, int cFill, int cEdge, float edgeK) {
        Vector3f[] v = new Vector3f[sides], vi = new Vector3f[sides];
        for (int k = 0; k < sides; k++) {
            float a = rot + TAU * k / sides;
            Vector3f o = new Vector3f(e1).mul(Mth.cos(a)).add(new Vector3f(e2).mul(Mth.sin(a)));
            v[k] = new Vector3f(c).add(new Vector3f(o).mul(r));
            vi[k] = new Vector3f(c).add(new Vector3f(o).mul(r * edgeK));
        }
        for (int k = 0; k < sides; k++) {
            Vector3f a = vi[k], b = vi[(k + 1) % sides];
            tri(fill, m, c.x, c.y, c.z, cFill, a.x, a.y, a.z, cFill, b.x, b.y, b.z, cFill);
        }
        for (int k = 0; k < sides; k++) {
            Vector3f a = v[k], b = v[(k + 1) % sides], ai = vi[k], bi = vi[(k + 1) % sides];
            quad(edge, m, a.x, a.y, a.z, cEdge, b.x, b.y, b.z, cEdge, bi.x, bi.y, bi.z, fadeA(cEdge, 0.2f), ai.x, ai.y, ai.z, fadeA(cEdge, 0.2f));
        }
    }

    /** 平面内的多边形描边（魔法阵的三角形 / 六边形） */
    private static void polyLine(VertexConsumer vc, Matrix4f m, int sides, float r, float rot, float w, int c) {
        for (int k = 0; k < sides; k++) {
            float a0 = rot + TAU * k / sides, a1 = rot + TAU * (k + 1) / sides;
            float x0 = Mth.cos(a0) * r, y0 = Mth.sin(a0) * r, x1 = Mth.cos(a1) * r, y1 = Mth.sin(a1) * r;
            float mx = (x0 + x1) * 0.5f, my = (y0 + y1) * 0.5f;
            float hl = Mth.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0)) * 0.5f;
            bar(vc, m, mx, my, (float) Math.atan2(y1 - y0, x1 - x0), hl, w, c);
        }
    }

    /** 赛璐璐火团：外深内浅 4 层 + 白芯，沿屏幕方向 (tx,ty) 反向拖尾 */
    private static void toonFlame(C c, VertexConsumer vc, Matrix4f m, float r, float tx, float ty, float tail, int[] cols, float a, float phase) {
        float[] rr = {1f, 0.78f, 0.56f, 0.34f};
        for (int i = 0; i < cols.length && i < rr.length; i++) {
            blob(vc, m, 0, 0, r * rr[i], 0.1f, 0.16f - i * 0.03f, phase * (1.2f + i * 0.25f) + i * 1.7f,
                    tx, ty, tail * (1f - i * 0.18f), c.c(cols[i], a * (i == 0 ? 0.8f : 0.95f)), c.c(cols[i], a * (i == 0 ? 0.8f : 0.95f)), 28);
        }
    }

    /** 世界方向在屏幕平面上的投影（供拖尾方向用） */
    private static float[] screenOf(C c, Vector3f worldDir) {
        Vector3f l = new Quaternionf(c.camQ).conjugate().transform(new Vector3f(worldDir));
        return new float[]{l.x, l.y};
    }

    private static Vector3f bezier(Vector3f p0, Vector3f p1, Vector3f p2, float f) {
        float u = 1f - f;
        return new Vector3f(p0).mul(u * u).add(new Vector3f(p1).mul(2 * u * f)).add(new Vector3f(p2).mul(f * f));
    }

    /** 光束起点：施法者第一人称时推离镜头，避免糊屏 */
    private static Vector3f beamStart(C c, Vector3f dir) {
        return c.ownerFP ? new Vector3f(dir).normalize().mul(0.6f).add(0, -0.1f, 0) : new Vector3f();
    }

    // ───────────────────────── 精神冲击：第三只眼 + 精神涟漪 ─────────────────────────

    private void mindBeam(C c) {
        float t = c.t;
        Vector3f E = c.e.end();
        float len = E.length();
        if (len < 0.1f) return;
        Vector3f dir = new Vector3f(E).div(len);
        Vector3f S = beamStart(c, dir);
        float head = easeOut(clamp01(t / 3f));
        float A = clamp01((c.life - t) / 6f);
        boolean psy = c.variant == 1;
        int col = c.col, deep = psy ? 0x7B2FD0 : 0x5F8FFF;
        Vector3f H = new Vector3f(S).lerp(E, head);
        Quaternionf q = new Quaternionf().rotationTo(0, 0, 1, dir.x, dir.y, dir.z);

        // 光束：外晕 + 白芯 + 双螺旋
        VertexConsumer g = c.glow();
        ribLerp(g, c.m(), line(S, H, 10), 0.26f, 0.12f, c.c(col, 0.4f * A), c.c(deep, 0.15f * A), c.cam);
        VertexConsumer vc = c.toon();
        ribLerp(vc, c.m(), line(S, H, 10), 0.05f, 0.03f, c.c(WHITE, 0.95f * A), c.c(col, 0.8f * A), c.cam);
        Vector3f[] pp = perp(dir);
        float span = H.distance(S);
        for (int k = 0; k < 2; k++) {
            int n = Math.max(8, (int) (span * 5));
            Vector3f[] hx = new Vector3f[n + 1];
            for (int i = 0; i <= n; i++) {
                float f = i / (float) n, a = f * span * 4.5f - t * 1.3f + k * (float) Math.PI;
                float r = 0.17f * Mth.sin((float) Math.PI * Math.min(1f, f * 1.5f + 0.1f));
                hx[i] = new Vector3f(S).lerp(H, f).add(new Vector3f(pp[0]).mul(Mth.cos(a) * r)).add(new Vector3f(pp[1]).mul(Mth.sin(a) * r));
            }
            ribLerp(g, c.m(), hx, 0.022f, 0.012f, c.c(col, 0.85f * A), c.c(WHITE, 0.4f * A), c.cam);
        }
        // 精神涟漪：沿光束推进的同心环
        c.s.pushPose();
        c.s.mulPose(q);
        Matrix4f m = c.m();
        float sOff = S.length();
        for (int k = 0; k < 7; k++) {
            float f = ((k / 7f) + t * 0.13f) % 1f;
            float z = sOff + f * span;
            float r = 0.16f + 0.26f * Mth.sin((float) Math.PI * f);
            float a = Mth.sin((float) Math.PI * f) * A * 0.85f;
            Matrix4f mk = new Matrix4f(m).translate(0, 0, z);
            ring(g, mk, r, r + 0.045f, c.c(WHITE, a), c.c(col, a * 0.5f), 30);
            ring(g, mk, r + 0.045f, r + 0.12f, c.c(col, a * 0.5f), c.c(deep, 0f), 30);
        }
        c.pop();

        // 额前第三只眼
        float eyeIn = easeOutBack(clamp01(t / 2.5f)), eyeA = clamp01(1f - (t - 4f) / 5f) * A;
        if (eyeA > 0.01f && !c.ownerFP) {
            c.bb(S);
            m = c.m();
            disc(c.glow(), m, 0, 0, 0.42f * eyeIn, c.c(col, 0.45f * eyeA), c.c(col, 0f), 24);
            Matrix4f me = new Matrix4f(m).scale(eyeIn, eyeIn * 0.46f, 1f);
            ring(c.toon(), me, 0.17f, 0.22f, c.c(deep, eyeA), c.c(col, eyeA), 32);
            disc(c.toon(), me, 0, 0, 0.17f, c.c(WHITE, 0.75f * eyeA), c.c(col, 0.55f * eyeA), 24);
            disc(c.toon(), m, 0, 0, 0.065f * eyeIn, c.c(deep, eyeA), c.c(deep, eyeA), 16);
            disc(c.toon(), m, 0.02f, 0.02f, 0.022f * eyeIn, c.c(WHITE, eyeA), c.c(WHITE, eyeA), 10);
            c.pop();
        }

        // 命中：同心震环 + 闪光
        float k = t - 3f;
        if (k >= 0) {
            c.bb(E);
            m = c.m();
            for (int j = 0; j < 3; j++) {
                float r = 0.2f + k * 0.22f + j * 0.22f;
                float a = clamp01(1f - k / 9f) * (1f - j * 0.25f);
                ring(g, m, r, r + 0.06f, c.c(WHITE, a), c.c(col, a * 0.4f), 32);
                ring(g, m, r + 0.06f, r + 0.16f, c.c(col, a * 0.4f), c.c(deep, 0f), 32);
            }
            if (k < 4) {
                float f = 1f - k / 4f;
                disc(g, m, 0, 0, 0.7f * f, c.c(WHITE, 0.9f * f), c.c(col, 0f), 24);
                starburst(g, m, 10, 0.08f, 1.1f * (0.6f + 0.4f * f), k * 0.2f, c.e.getId() * 7 + (int) k, c.c(WHITE, 0.8f * f), c.c(col, 0f));
            }
            c.pop();
        }
    }

    // ───────────────────────── 精神震荡：陀螺仪震环 + 故障条纹 ─────────────────────────

    private void mindQuake(C c) {
        float t = c.t, A = c.env(0, 8);
        int col = c.col, deep = 0x6A4CFF;
        float cy = c.h * 0.85f;
        VertexConsumer g = c.glow();
        // 三轴震环向外扩张
        float R = 0.35f + 1.9f * easeOut(t / 9f);
        float ringA = A * (c.selfFP ? 0.45f : 1f);
        for (int i = 0; i < 3; i++) {
            c.s.pushPose();
            c.s.translate(0, cy, 0);
            c.s.mulPose(new Quaternionf().rotateY(i * 1.05f + t * 0.16f).rotateX(0.55f + i * 0.95f));
            Matrix4f m = c.m();
            ring(g, m, R, R + 0.06f, c.c(WHITE, 0.9f * ringA), c.c(col, 0.6f * ringA), 40);
            ring(g, m, R - 0.16f, R, c.c(deep, 0f), c.c(col, 0.5f * ringA), 40);
            c.pop();
        }
        if (c.selfFP) return; // 本地受击者：改用屏幕震颤
        c.bb(0, cy, 0);
        Matrix4f m = c.m();
        // 冲击圆盘：中空、边缘亮
        float r = 2.4f * easeOut(t / 5f), da = 0.55f * clamp01(1f - t / 7f);
        disc(g, m, 0, 0, r, c.c(col, 0f), c.c(col, da), 36);
        if (t < 4) {
            float f = 1f - t / 4f;
            starburst(g, m, 14, 0.1f, 1.5f * (0.5f + 0.5f * f), 0.3f, c.e.getId() * 5, c.c(WHITE, 0.95f * f), c.c(col, 0f));
            disc(g, m, 0, 0, 0.55f * f, c.c(WHITE, f), c.c(col, 0f), 20);
        }
        // 故障条纹：逐帧跳变的横条，青 / 品红色差
        float gA = clamp01(1f - t / 13f) * A;
        if (gA > 0.01f) {
            VertexConsumer vc = c.toon();
            int seed = (int) (t * 1.5f) * 131 + c.e.getId();
            for (int i = 0; i < 10; i++) {
                float y = (hash(seed + i * 7) - 0.5f) * c.h * 0.8f;
                float w = 0.18f + 0.55f * hash(seed + i * 11);
                float x = (hash(seed + i * 13) - 0.5f) * 0.7f;
                float th = 0.015f + 0.035f * hash(seed + i * 17);
                bar(vc, m, x - 0.035f, y, 0, w, th, c.c(0x5CF6FF, 0.8f * gA));
                bar(vc, m, x + 0.035f, y, 0, w, th, c.c(0xFF4FD8, 0.8f * gA));
                bar(vc, m, x, y, 0, w * 0.9f, th * 0.45f, c.c(WHITE, 0.9f * gA));
            }
        }
        c.pop();
    }

    // ───────────────────────── 魔能爆：六芒魔法阵 + 奥术光枪 ─────────────────────────

    private void arcane(C c) {
        float t = c.t;
        Vector3f E = c.e.end();
        float len = E.length();
        if (len < 0.1f) return;
        Vector3f dir = new Vector3f(E).div(len);
        Vector3f S = beamStart(c, dir);
        boolean force = c.variant == 1;
        int col = c.col, light = force ? 0xB9A2FF : 0xE6D6FF, deep = force ? 0x3A1C9E : 0x6A3FD8;
        Quaternionf q = new Quaternionf().rotationTo(0, 0, 1, dir.x, dir.y, dir.z);
        VertexConsumer g = c.glow(), vc = c.toon();

        // 掌前魔法阵
        float sIn = easeOutBack(clamp01(t / 3f)), cA = clamp01(1f - (t - 7f) / 5f);
        if (cA > 0.01f) {
            Vector3f P = new Vector3f(S).add(new Vector3f(dir).mul(0.35f));
            c.s.pushPose();
            c.s.translate(P.x, P.y, P.z);
            c.s.mulPose(q);
            float sc = sIn * (c.ownerFP ? 0.75f : 1f);
            c.s.scale(sc, sc, sc);
            Matrix4f m = new Matrix4f(c.m()).rotateZ(t * 0.22f);
            disc(g, m, 0, 0, 0.85f, c.c(col, 0.35f * cA), c.c(col, 0f), 32);
            ring(vc, m, 0.6f, 0.65f, c.c(light, cA), c.c(light, cA), 48);
            ring(vc, m, 0.5f, 0.52f, c.c(light, 0.9f * cA), c.c(light, 0.9f * cA), 48);
            polyLine(vc, m, 3, 0.5f, (float) Math.PI / 2f, 0.012f, c.c(WHITE, cA));
            polyLine(vc, m, 3, 0.5f, -(float) Math.PI / 2f, 0.012f, c.c(WHITE, cA));
            for (int k = 0; k < 18; k++) {
                float a = TAU * k / 18f;
                float l = k % 3 == 0 ? 0.045f : 0.025f;
                bar(vc, m, Mth.cos(a) * 0.565f, Mth.sin(a) * 0.565f, a, l, 0.008f, c.c(WHITE, 0.85f * cA));
            }
            Matrix4f mi = new Matrix4f(c.m()).rotateZ(-t * 0.5f);
            ring(vc, mi, 0.22f, 0.245f, c.c(light, cA), c.c(light, cA), 32);
            polyLine(vc, mi, 6, 0.22f, 0, 0.008f, c.c(WHITE, 0.8f * cA));
            for (int k = 0; k < 6; k++) {
                float a = TAU * k / 6f + 0.52f;
                disc(vc, mi, Mth.cos(a) * 0.36f, Mth.sin(a) * 0.36f, 0.03f, c.c(WHITE, cA), c.c(light, cA), 10);
            }
            ring(g, m, 0.62f, 0.75f, c.c(col, 0.6f * cA), c.c(col, 0f), 40);
            c.pop();
        }

        // 光枪：2~4 tick 飞完全程
        float f = clamp01((t - 2f) / 2f);
        if (t >= 2f && t < 6f) {
            Vector3f Hd = new Vector3f(S).lerp(E, f);
            Vector3f tail = new Vector3f(Hd).sub(new Vector3f(dir).mul(Math.min(1.6f, Hd.distance(S))));
            float la = clamp01((6f - t) / 2f);
            ribSpindle(g, c.m(), tail, Hd, 0.32f, 0.75f, c.c(col, 0f), c.c(col, 0.55f * la), c.cam);
            ribSpindle(vc, c.m(), tail, Hd, 0.13f, 0.75f, c.c(col, 0.2f * la), c.c(light, la), c.cam);
            ribSpindle(vc, c.m(), new Vector3f(tail).lerp(Hd, 0.3f), Hd, 0.05f, 0.8f, c.c(WHITE, 0.3f * la), c.c(WHITE, la), c.cam);
        }
        // 尾迹符文：光枪经过处留下旋转的小菱形
        int nr = (int) (len / 0.55f);
        for (int j = 1; j < nr; j++) {
            float z = j / (float) nr;
            float age = t - (2f + 2f * z);
            if (age < 0 || age > 6) continue;
            float a = (1f - age / 6f);
            Vector3f P = new Vector3f(S).lerp(E, z);
            c.bb(P);
            Matrix4f m = new Matrix4f(c.m()).rotateZ(age * 0.6f + j);
            float s = 0.09f * (1f - age / 8f);
            needle(vc, m, -s, 0, s, 0, s * 0.6f, c.c(light, 0.9f * a), c.c(col, 0.6f * a));
            disc(g, m, 0, 0, s * 1.8f, c.c(col, 0.4f * a), c.c(col, 0f), 12);
            c.pop();
        }
        // 命中：目标面前的小法阵碎裂成碎片
        float k = t - 4f;
        if (k >= 0) {
            Vector3f back = new Vector3f(dir).negate();
            c.plane(E.x - dir.x * 0.3f, E.y - dir.y * 0.3f, E.z - dir.z * 0.3f, back.x, back.y, back.z);
            Matrix4f m = c.m();
            float a = clamp01(1f - k / 8f);
            if (k < 3) {
                float r = 0.35f + k * 0.12f;
                ring(vc, m, r, r + 0.04f, c.c(light, a), c.c(light, a), 36);
                polyLine(vc, m, 6, r * 0.8f, k * 0.3f, 0.01f, c.c(WHITE, a));
            }
            for (int i = 0; i < 9; i++) {
                float ang = TAU * i / 9f + hash(c.e.getId() + i) * 0.6f;
                float d = 0.3f + k * (0.18f + 0.1f * hash(i * 7 + 3));
                Matrix4f ms = new Matrix4f(m).translate(Mth.cos(ang) * d, Mth.sin(ang) * d, 0).rotateZ(k * 0.5f + i);
                float s = 0.09f * a;
                tri(vc, ms, -s, -s * 0.6f, 0, c.c(light, a), s, -s * 0.4f, 0, c.c(WHITE, a), 0, s, 0, c.c(col, a));
            }
            if (force) {
                for (int j = 0; j < 2; j++) {
                    float r = 0.3f + k * (0.35f + j * 0.15f);
                    ring(g, m, r, r + 0.18f, c.c(col, 0.6f * a), c.c(deep, 0f), 36);
                }
            }
            if (k < 3.5f) {
                float ff = 1f - k / 3.5f;
                disc(g, m, 0, 0, 0.8f * ff, c.c(WHITE, ff), c.c(col, 0f), 24);
                starburst(g, m, 8, 0.06f, 0.9f * ff + 0.3f, k * 0.3f, c.e.getId(), c.c(WHITE, 0.8f * ff), c.c(col, 0f));
            }
            c.pop();
        }
    }

    // ───────────────────────── 五行道法：飞符 → 元素显化 ─────────────────────────

    private void element(C c) {
        float t = c.t;
        Vector3f E = c.e.end();              // 目标躯干中心
        Vector3f G = new Vector3f(E.x, E.y - 0.9f, E.z);   // 地面（近似）
        int col = c.col;
        // 符箓飞行（0~4 tick），抛物线 + 自转
        if (t < 4.6f) {
            float f = clamp01(t / 4f);
            Vector3f S = beamStart(c, E);
            Vector3f P = new Vector3f(S).lerp(E, f).add(0, Mth.sin((float) Math.PI * f) * 0.5f, 0);
            float fa = t < 4f ? 1f : clamp01((4.6f - t) / 0.6f);
            // 金色尾迹
            int n = 8;
            Vector3f[] tr = new Vector3f[n + 1];
            for (int i = 0; i <= n; i++) {
                float g = Math.max(0, f - 0.3f * (1f - i / (float) n));
                tr[i] = new Vector3f(S).lerp(E, g).add(0, Mth.sin((float) Math.PI * g) * 0.5f, 0);
            }
            ribLerp(c.glow(), c.m(), tr, 0.01f, 0.1f, c.c(col, 0f), c.c(col, 0.6f * fa), c.cam);
            c.bb(P);
            Matrix4f m = new Matrix4f(c.m()).rotateZ(t * 0.9f);
            VertexConsumer vc = c.toon();
            bar(vc, m, 0, 0, (float) Math.PI / 2f, 0.17f, 0.065f, c.c(0xF2D36B, fa));
            bar(vc, m, 0, 0, (float) Math.PI / 2f, 0.15f, 0.05f, c.c(0xFFE9A0, fa));
            bar(vc, m, 0, 0.06f, 0, 0.032f, 0.008f, c.c(0xC8202A, fa));
            bar(vc, m, 0, 0, (float) Math.PI / 2f, 0.1f, 0.008f, c.c(0xC8202A, fa));
            bar(vc, m, 0, -0.07f, 0.6f, 0.03f, 0.007f, c.c(0xC8202A, fa));
            disc(c.glow(), m, 0, 0, 0.3f, c.c(col, 0.45f * fa), c.c(col, 0f), 16);
            c.pop();
        }
        float k = t - 4f;
        if (k < 0) return;
        float A = clamp01((c.life - t) / 6f);
        // 符箓燃尽的闪光
        if (k < 3) {
            c.bb(E);
            float ff = 1f - k / 3f;
            disc(c.glow(), c.m(), 0, 0, 0.9f * ff, c.c(WHITE, ff), c.c(col, 0f), 20);
            c.pop();
        }
        c.s.pushPose();
        c.s.translate(G.x, G.y, G.z);
        Vector3f cam = new Vector3f(c.cam).sub(G);
        switch (c.variant) {
            case 0 -> elemThunder(c, k, A, cam);
            case 1 -> elemWind(c, k, A, cam);
            case 2 -> elemWater(c, k, A, cam);
            case 3 -> elemFire(c, k, A, cam);
            default -> elemEarth(c, k, A, cam);
        }
        c.pop();
    }

    /** 雷：天降落雷（逐 2 帧重生成）+ 地面电环 */
    private void elemThunder(C c, float k, float A, Vector3f cam) {
        int col = c.col;
        VertexConsumer g = c.glow(), vc = c.toon();
        float strike = k < 6 ? 1f : clamp01(1f - (k - 6f) / 6f);
        float flick = ((int) (k * 2f) % 3 == 1) ? 0.5f : 1f;
        float a = strike * flick * A;
        int seed = (int) (k * 1.5f) * 97 + c.e.getId();
        Vector3f top = new Vector3f(0.4f * (hash(seed) - 0.5f), 9f, 0.4f * (hash(seed + 1) - 0.5f));
        Vector3f[] main = boltPts(top, new Vector3f(0, 0.05f, 0), 16, 0.7f, seed);
        ribLerp(g, c.m(), main, 0.4f, 0.3f, c.c(col, 0.55f * a), c.c(col, 0.6f * a), cam);
        ribLerp(vc, c.m(), main, 0.07f, 0.09f, c.c(WHITE, a), c.c(WHITE, a), cam);
        for (int b = 0; b < 3; b++) {
            int idx = 3 + (int) (hash(seed + b * 5) * 9);
            Vector3f from = main[idx];
            Vector3f to = new Vector3f(from).add((hash(seed + b * 9) - 0.5f) * 2.4f, -1.2f - hash(seed + b) * 1.5f, (hash(seed + b * 13) - 0.5f) * 2.4f);
            Vector3f[] br = boltPts(from, to, 5, 0.3f, seed + b * 31);
            ribLerp(g, c.m(), br, 0.16f, 0.02f, c.c(col, 0.5f * a), c.c(col, 0f), cam);
            ribLerp(vc, c.m(), br, 0.03f, 0.005f, c.c(WHITE, a), c.c(WHITE, 0.2f * a), cam);
        }
        // 地面电环 + 放射电火花
        c.flat(0.05f);
        Matrix4f m = c.m();
        float r = 0.3f + 1.5f * easeOut(k / 6f);
        float ra = clamp01(1f - k / 12f) * A;
        ring(g, m, r, r + 0.12f, c.c(WHITE, 0.9f * ra), c.c(col, 0f), 40);
        ring(g, m, r * 0.6f, r * 0.6f + 0.06f, c.c(col, 0.7f * ra), c.c(col, 0f), 32);
        disc(g, m, 0, 0, 1.4f, c.c(col, 0.35f * ra), c.c(col, 0f), 28);
        c.pop();
        if (k < 5) {
            c.bb(0, 0.3f, 0);
            starburst(g, c.m(), 12, 0.1f, 1.4f, k, seed, c.c(WHITE, (1f - k / 5f) * A), c.c(col, 0f));
            c.pop();
        }
    }

    /** 风：三股上旋风刃 + 环身飞旋的月牙风刃 */
    private void elemWind(C c, float k, float A, Vector3f cam) {
        int col = c.col;
        VertexConsumer g = c.glow(), vc = c.toon();
        float grow = easeOut(k / 5f), H = 2.6f * grow;
        for (int j = 0; j < 3; j++) {
            int n = 28;
            Vector3f[] p = new Vector3f[n + 1];
            float[] w = new float[n + 1];
            int[] cc = new int[n + 1], cw = new int[n + 1];
            for (int i = 0; i <= n; i++) {
                float f = i / (float) n, y = f * H;
                float a = f * 7.5f + k * 0.85f + j * TAU / 3f;
                float r = 0.35f + 0.5f * f;
                p[i] = new Vector3f(Mth.cos(a) * r, y, Mth.sin(a) * r);
                w[i] = 0.07f * Mth.sin((float) Math.PI * f) + 0.01f;
                float aa = A * (0.4f + 0.6f * Mth.sin((float) Math.PI * f));
                cc[i] = c.c(col, 0.8f * aa);
                cw[i] = c.c(WHITE, 0.9f * aa);
            }
            rib(vc, c.m(), p, w, cc, cam);
            float[] w2 = new float[n + 1];
            for (int i = 0; i <= n; i++) w2[i] = w[i] * 0.35f;
            rib(vc, c.m(), p, w2, cw, cam);
        }
        for (int i = 0; i < 5; i++) {
            float y = 0.3f + 2.0f * hash(c.e.getId() + i * 3) * grow;
            c.flat(y);
            float rot = k * (0.9f + 0.3f * i) + i * 1.3f;
            float r = 0.75f + 0.25f * hash(i * 5 + 1);
            float a = A * (0.5f + 0.5f * Mth.sin(k * 0.8f + i));
            ring(g, c.m(), r, r + 0.07f, c.c(WHITE, a), c.c(col, 0f), rot, rot + 1.3f, 20);
            ring(vc, c.m(), r - 0.02f, r + 0.03f, c.c(col, 0.7f * a), c.c(col, 0.7f * a), rot + 0.2f, rot + 1.0f, 16);
            c.pop();
        }
        c.flat(0.04f);
        ring(g, c.m(), 0.5f, 1.3f * grow + 0.5f, c.c(col, 0.35f * A), c.c(col, 0f), 36);
        c.pop();
    }

    /** 水：拔地水柱（白色高光）+ 抛洒水珠 + 地面涟漪 */
    private void elemWater(C c, float k, float A, Vector3f cam) {
        int col = c.col, deep = 0x1565C0;
        VertexConsumer g = c.glow(), vc = c.toon();
        float H = k < 8 ? 2.4f * easeOutBack(clamp01(k / 4f)) : 2.4f * clamp01(1f - (k - 8f) / 8f);
        if (H > 0.05f) {
            int n = 10;
            Vector3f[] p = new Vector3f[n + 1];
            float[] w = new float[n + 1], wh = new float[n + 1];
            int[] cc = new int[n + 1], ch = new int[n + 1];
            for (int i = 0; i <= n; i++) {
                float f = i / (float) n;
                p[i] = new Vector3f(0.06f * Mth.sin(k * 0.9f + f * 6f), f * H, 0.06f * Mth.cos(k * 0.7f + f * 5f));
                w[i] = (0.42f - 0.12f * f) * (1f + 0.08f * Mth.sin(k * 1.5f + f * 9f));
                wh[i] = w[i] * 0.25f;
                cc[i] = c.c(mix(deep, col, f), 0.85f * A);
                ch[i] = c.c(WHITE, 0.75f * A);
            }
            rib(vc, c.m(), p, w, cc, cam);
            Vector3f[] hp = new Vector3f[n + 1];
            for (int i = 0; i <= n; i++) hp[i] = new Vector3f(p[i]).add(0.12f, 0, 0.0f);
            rib(vc, c.m(), hp, wh, ch, cam);
            c.bb(0, H, 0);
            blob(vc, c.m(), 0, 0, 0.45f, 0.18f, 0.2f, k * 1.3f, 0, 0, 0, c.c(col, 0.9f * A), c.c(col, 0.9f * A), 24);
            blob(vc, c.m(), 0.04f, 0.04f, 0.22f, 0.15f, 0.1f, k * 1.7f, 0, 0, 0, c.c(WHITE, 0.8f * A), c.c(WHITE, 0.8f * A), 18);
            c.pop();
        }
        for (int i = 0; i < 14; i++) {
            float a = TAU * i / 14f + hash(c.e.getId() + i);
            float sp = 0.06f + 0.05f * hash(i * 3 + 7);
            float tt = Math.max(0, k - 2f);
            float x = Mth.cos(a) * sp * tt, z = Mth.sin(a) * sp * tt;
            float y = 1.8f + 0.28f * tt - 0.035f * tt * tt;
            if (y < 0) continue;
            c.bb(x, y, z);
            float s = 0.06f + 0.04f * hash(i);
            disc(vc, c.m(), 0, 0, s, c.c(col, 0.9f * A), c.c(deep, 0.9f * A), 10);
            disc(vc, c.m(), s * 0.3f, s * 0.3f, s * 0.35f, c.c(WHITE, A), c.c(WHITE, A), 8);
            c.pop();
        }
        c.flat(0.03f);
        for (int j = 0; j < 3; j++) {
            float r = 0.3f + Math.max(0, k - j * 2.5f) * 0.18f;
            float a = clamp01(1f - (k - j * 2.5f) / 10f) * A;
            if (k < j * 2.5f) continue;
            ring(vc, c.m(), r, r + 0.05f, c.c(WHITE, 0.8f * a), c.c(col, 0.6f * a), 40);
            ring(g, c.m(), r + 0.05f, r + 0.2f, c.c(col, 0.4f * a), c.c(col, 0f), 40);
        }
        c.pop();
    }

    /** 火：螺旋上升的火团柱 + 地面火环 */
    private void elemFire(C c, float k, float A, Vector3f cam) {
        int[] cols = {0xD8361A, 0xFF7A2A, 0xFFC04A, 0xFFF3C0};
        VertexConsumer g = c.glow(), vc = c.toon();
        float grow = easeOut(k / 4f);
        c.flat(0.04f);
        disc(g, c.m(), 0, 0, 1.3f * grow, c.c(0xFF7A2A, 0.45f * A), c.c(0xFF3A10, 0f), 32);
        ring(g, c.m(), 0.7f * grow, 0.9f * grow, c.c(0xFFC04A, 0.8f * A), c.c(0xFF3A10, 0f), 36);
        c.pop();
        for (int i = 0; i < 12; i++) {
            float ph = (k * 0.09f + i / 12f) % 1f;
            float y = ph * 2.6f * grow;
            float a = TAU * i / 12f * 2.3f + k * 0.4f;
            float rr = 0.3f * (1f - ph * 0.6f);
            c.bb(Mth.cos(a) * rr, y, Mth.sin(a) * rr);
            float r = (0.5f * (1f - ph) + 0.12f) * grow;
            toonFlame(c, vc, c.m(), r, 0, 1, 0.9f, cols, A * Mth.sin((float) Math.PI * Math.min(1f, ph * 1.2f + 0.08f)), k + i * 2.1f);
            c.pop();
        }
        c.bb(0, 1.2f * grow, 0);
        disc(g, c.m(), 0, 0, 1.6f * grow, c.c(0xFF7A2A, 0.35f * A), c.c(0xFF3A10, 0f), 28);
        c.pop();
    }

    /** 土：环绕目标拔地而起的岩刺 + 飞溅碎石 */
    private void elemEarth(C c, float k, float A, Vector3f cam) {
        VertexConsumer vc = c.toon(), g = c.glow();
        int[] face = {0x8D6E4F, 0xB08A63, 0x6B5038, 0x9C7B58};
        for (int i = 0; i < 8; i++) {
            boolean center = i == 7;
            float ang = TAU * i / 7f + hash(c.e.getId() + i) * 0.4f;
            float rr = center ? 0f : 0.75f + 0.2f * hash(i * 3);
            float rise = easeOutBack(clamp01((k - (center ? 0.6f : i * 0.25f)) / 3f));
            float sink = k > 12 ? clamp01(1f - (k - 12f) / 6f) : 1f;
            float H = (center ? 1.9f : 0.9f + 0.6f * hash(i * 11 + 2)) * rise * sink;
            if (H < 0.02f) continue;
            float bw = center ? 0.32f : 0.2f + 0.08f * hash(i * 5);
            float lean = center ? 0f : 0.28f;
            float bx = Mth.cos(ang) * rr, bz = Mth.sin(ang) * rr;
            Vector3f tip = new Vector3f(bx + Mth.cos(ang) * lean * H, H, bz + Mth.sin(ang) * lean * H);
            for (int f = 0; f < 4; f++) {
                float a0 = ang + f * TAU / 4f + 0.785f, a1 = ang + (f + 1) * TAU / 4f + 0.785f;
                int cf = c.c(face[f], A);
                tri(vc, c.m(), bx + Mth.cos(a0) * bw, 0, bz + Mth.sin(a0) * bw, cf,
                        bx + Mth.cos(a1) * bw, 0, bz + Mth.sin(a1) * bw, cf, tip.x, tip.y, tip.z, c.c(mix(face[f], 0xE8D3B0, 0.4f), A));
            }
        }
        // 碎石
        for (int i = 0; i < 12; i++) {
            float tt = Math.max(0, k - 0.5f);
            float a = TAU * i / 12f + hash(i + 3);
            float sp = 0.05f + 0.05f * hash(i * 7);
            float y = 0.25f * tt - 0.03f * tt * tt + 0.1f;
            if (y < 0) continue;
            c.bb(Mth.cos(a) * sp * tt * 1.5f, y, Mth.sin(a) * sp * tt * 1.5f);
            Matrix4f m = new Matrix4f(c.m()).rotateZ(tt * 0.7f + i);
            float s = 0.05f + 0.05f * hash(i * 13);
            quad(vc, m, -s, -s, 0, c.c(face[i % 4], A), s, -s * 0.7f, 0, c.c(face[(i + 1) % 4], A), s * 0.8f, s, 0, c.c(face[i % 4], A), -s * 0.6f, s * 0.8f, 0, c.c(face[(i + 2) % 4], A));
            c.pop();
        }
        c.flat(0.04f);
        float r = 0.4f + 1.6f * easeOut(k / 6f);
        ring(g, c.m(), r * 0.5f, r, c.c(0x7A6248, 0f), c.c(0xC9A27E, 0.45f * clamp01(1f - k / 12f) * A), 36);
        c.pop();
    }

    // ───────────────────────── 生物闪电：分叉电弧 ─────────────────────────

    private void bioBolt(C c) {
        float t = c.t;
        Vector3f E = c.e.end();
        float len = E.length();
        if (len < 0.1f) return;
        Vector3f S = beamStart(c, E);
        int col = c.col, violet = 0xB48CFF;
        float on = t < 6 ? 1f : clamp01(1f - (t - 6f) / 4f);
        float flick = ((int) (t * 2f) % 3 == 1) ? 0.45f : 1f;
        float a = on * flick;
        int seed = (int) (t * 2f) * 53 + c.e.getId() * 7;
        int segs = Math.max(6, (int) (len * 2.5f));
        VertexConsumer g = c.glow(), vc = c.toon();
        Vector3f[] main = boltPts(S, E, segs, Math.min(0.45f, 0.08f * len + 0.1f), seed);
        ribLerp(g, c.m(), main, 0.24f, 0.24f, c.c(col, 0.55f * a), c.c(col, 0.55f * a), c.cam);
        ribLerp(vc, c.m(), main, 0.045f, 0.05f, c.c(WHITE, a), c.c(WHITE, a), c.cam);
        Vector3f[] second = boltPts(S, E, segs, Math.min(0.6f, 0.1f * len + 0.15f), seed + 77);
        ribLerp(g, c.m(), second, 0.03f, 0.03f, c.c(violet, 0.75f * a), c.c(col, 0.75f * a), c.cam);
        // 分叉
        for (int b = 0; b < 5; b++) {
            int idx = 1 + (int) (hash(seed + b * 11) * (segs - 2));
            Vector3f from = main[idx];
            Vector3f to = new Vector3f(from).add((hash(seed + b * 3) - 0.5f) * 1.6f, (hash(seed + b * 5) - 0.5f) * 1.6f, (hash(seed + b * 7) - 0.5f) * 1.6f);
            Vector3f[] br = boltPts(from, to, 4, 0.18f, seed + b * 19);
            ribLerp(g, c.m(), br, 0.1f, 0.01f, c.c(col, 0.5f * a), c.c(col, 0f), c.cam);
            ribLerp(vc, c.m(), br, 0.022f, 0.004f, c.c(WHITE, 0.9f * a), c.c(WHITE, 0.1f * a), c.cam);
        }
        // 两端电光
        c.bb(E);
        Matrix4f m = c.m();
        disc(g, m, 0, 0, 0.55f * a + 0.1f, c.c(WHITE, 0.9f * a), c.c(col, 0f), 20);
        sparkle(g, m, 0, 0, 0.8f * (0.6f + 0.4f * flick), t * 0.4f, c.c(WHITE, a), c.c(col, 0f));
        starburst(g, m, 9, 0.05f, 0.9f, t, seed, c.c(WHITE, 0.7f * a), c.c(col, 0f));
        c.pop();
        if (!c.ownerFP) {
            c.bb(S);
            m = c.m();
            disc(g, m, 0, 0, 0.3f, c.c(col, 0.7f * a), c.c(col, 0f), 16);
            for (int i = 0; i < 3; i++) {
                float ang = hash(seed + i * 23) * TAU;
                needle(g, m, 0, 0, Mth.cos(ang) * 0.35f, Mth.sin(ang) * 0.35f, 0.02f, c.c(WHITE, a), c.c(col, 0f));
            }
            c.pop();
        }
    }

    // ───────────────────────── 凤仙火：弧线小火球群 ─────────────────────────

    private void phoenix(C c) {
        float t = c.t;
        Vector3f E = c.e.end();
        float len = E.length();
        if (len < 0.1f) return;
        Vector3f S = beamStart(c, E);
        Vector3f[] pp = perp(E);
        int n = Math.max(3, c.variant);
        int[] cols = {0xC8281A, 0xFF6A1C, 0xFFB13C, 0xFFF0B0};
        VertexConsumer g = c.glow(), vc = c.toon();
        float T = 6f;
        for (int i = 0; i < n; i++) {
            float t0 = i * 0.8f;
            float f = (t - t0) / T;
            if (f < 0) continue;
            float ang = hash(c.e.getId() * 13 + i) * TAU;
            float off = 0.7f + 1.0f * hash(i * 7 + c.e.getId());
            Vector3f land = new Vector3f(E).add(new Vector3f(pp[0]).mul((hash(i * 3 + 1) - 0.5f) * 0.7f)).add(new Vector3f(pp[1]).mul((hash(i * 5 + 2) - 0.5f) * 0.7f));
            Vector3f ctrl = new Vector3f(S).lerp(land, 0.5f).add(new Vector3f(pp[0]).mul(Mth.cos(ang) * off)).add(new Vector3f(pp[1]).mul(Mth.sin(ang) * off)).add(0, 0.35f, 0);
            if (f < 1f) {
                Vector3f P = bezier(S, ctrl, land, f);
                Vector3f V = bezier(S, ctrl, land, Math.min(1f, f + 0.02f)).sub(bezier(S, ctrl, land, Math.max(0f, f - 0.02f)));
                // 尾焰
                int m = 8;
                Vector3f[] tr = new Vector3f[m + 1];
                for (int j = 0; j <= m; j++) tr[j] = bezier(S, ctrl, land, Math.max(0f, f - 0.28f * (1f - j / (float) m)));
                ribLerp(g, c.m(), tr, 0.01f, 0.14f, c.c(0xFF6A1C, 0f), c.c(0xFF8A3C, 0.65f), c.cam);
                c.bb(P);
                float[] sd = screenOf(c, V);
                float lat = Mth.sqrt(sd[0] * sd[0] + sd[1] * sd[1]);
                float sc = c.ownerFP && f < 0.15f ? 0.5f : 1f;
                toonFlame(c, vc, c.m(), 0.24f * sc, -sd[0], -sd[1], 1.8f * Math.min(1f, lat * 3f), cols, 1f, t + i * 1.9f);
                disc(g, c.m(), 0, 0, 0.55f * sc, c.c(0xFF8A3C, 0.4f), c.c(0xFF3A10, 0f), 18);
                c.pop();
            } else {
                float k = (f - 1f) * T;
                if (k > 7) continue;
                float a = 1f - k / 7f;
                c.bb(land);
                Matrix4f m = c.m();
                disc(g, m, 0, 0, 0.9f * (0.4f + 0.6f * a), c.c(0xFFB13C, 0.7f * a), c.c(0xFF3A10, 0f), 20);
                starburst(g, m, 9, 0.06f, 0.8f, k * 0.2f + i, c.e.getId() + i * 7, c.c(0xFFF0B0, 0.9f * a), c.c(0xFF6A1C, 0f));
                ring(g, m, 0.2f + k * 0.12f, 0.28f + k * 0.12f, c.c(0xFFF0B0, 0.8f * a), c.c(0xFF6A1C, 0f), 24);
                toonFlame(c, vc, m, 0.32f * a, 0, 1, 0.6f, cols, a, t + i);
                c.pop();
            }
        }
    }

    // ───────────────────────── 灵力治疗：螺旋光带 + 十字光点 ─────────────────────────

    private void heal(C c) {
        float t = c.t, A = c.env(4, 10);
        int col = c.col, deep = 0x26B39A;
        float h = c.h;
        VertexConsumer g = c.glow(), vc = c.toon();
        float fpK = c.selfFP ? 0.5f : 1f;
        // 地面光环
        c.flat(0.03f);
        Matrix4f m = c.m();
        float r0 = 0.5f + 0.4f * easeOut(t / 6f);
        disc(g, m, 0, 0, r0 + 0.5f, c.c(col, 0.3f * A), c.c(col, 0f), 32);
        ring(vc, m, r0, r0 + 0.05f, c.c(WHITE, 0.8f * A), c.c(col, 0.8f * A), 40);
        Matrix4f mr = new Matrix4f(m).rotateZ(t * 0.08f);
        for (int k = 0; k < 3; k++) {
            float a0 = k * TAU / 3f;
            ring(g, mr, r0 + 0.15f, r0 + 0.22f, c.c(col, 0.8f * A), c.c(deep, 0f), a0, a0 + 1.4f, 16);
        }
        c.pop();
        // 螺旋光带：沿带向上流动的光脉冲
        for (int j = 0; j < 3; j++) {
            int n = 30;
            Vector3f[] p = new Vector3f[n + 1];
            float[] w = new float[n + 1], wc = new float[n + 1];
            int[] cc = new int[n + 1], cw = new int[n + 1];
            for (int i = 0; i <= n; i++) {
                float u = i / (float) n;
                float a = u * TAU * 1.3f + t * 0.17f + j * TAU / 3f;
                float r = 0.62f - 0.2f * u;
                p[i] = new Vector3f(Mth.cos(a) * r, u * h * 1.25f, Mth.sin(a) * r);
                float pulse = 0.5f + 0.5f * Mth.sin(u * 10f - t * 0.55f + j);
                float aa = A * fpK * (0.2f + 0.8f * pulse * pulse) * Mth.sin((float) Math.PI * u);
                w[i] = 0.045f;
                wc[i] = 0.016f;
                cc[i] = c.c(col, 0.85f * aa);
                cw[i] = c.c(WHITE, aa);
            }
            rib(g, c.m(), p, w, cc, c.cam);
            rib(vc, c.m(), p, wc, cw, c.cam);
        }
        // 十字光点上升
        for (int i = 0; i < 10; i++) {
            float ph = (hash(c.e.getId() + i * 7) + t * 0.028f) % 1f;
            float ang = hash(i * 13 + 5) * TAU, rr = 0.35f + 0.45f * hash(i * 3 + 1);
            Vector3f P = new Vector3f(Mth.cos(ang) * rr, ph * h * 1.35f, Mth.sin(ang) * rr);
            if (c.selfFP && P.y > h * 0.75f) continue;
            float a = A * Mth.sin((float) Math.PI * ph);
            c.bb(P);
            m = c.m();
            float s = 0.09f * (1f - ph * 0.4f);
            bar(vc, m, 0, 0, 0, s, s * 0.32f, c.c(col, a));
            bar(vc, m, 0, 0, (float) Math.PI / 2f, s, s * 0.32f, c.c(col, a));
            bar(vc, m, 0, 0, 0, s * 0.7f, s * 0.14f, c.c(WHITE, a));
            bar(vc, m, 0, 0, (float) Math.PI / 2f, s * 0.7f, s * 0.14f, c.c(WHITE, a));
            disc(g, m, 0, 0, s * 2.4f, c.c(col, 0.35f * a), c.c(col, 0f), 12);
            c.pop();
        }
        // 施法者 → 目标：柔和的光流
        Vector3f O = c.e.end();
        if (c.variant == 1 && O.lengthSquared() > 0.01f && t < 13) {
            float a = A * clamp01(1f - (t - 7f) / 6f);
            Vector3f D = new Vector3f(0, h * 0.6f, 0);
            Vector3f[] pp = perp(new Vector3f(D).sub(O));
            int n = 22;
            Vector3f[] p = new Vector3f[n + 1];
            float reach = easeOut(clamp01(t / 4f));
            for (int i = 0; i <= n; i++) {
                float u = i / (float) n * reach;
                float wv = 0.14f * Mth.sin((float) Math.PI * u) * Mth.sin(u * 12f - t * 0.8f);
                p[i] = new Vector3f(O).lerp(D, u).add(new Vector3f(pp[0]).mul(wv)).add(new Vector3f(pp[1]).mul(wv * 0.5f));
            }
            ribLerp(g, c.m(), p, 0.1f, 0.06f, c.c(col, 0.5f * a), c.c(col, 0.7f * a), c.cam);
            ribLerp(vc, c.m(), p, 0.02f, 0.02f, c.c(WHITE, 0.8f * a), c.c(WHITE, a), c.cam);
            for (int k = 0; k < 4; k++) {
                float u = ((t * 0.12f + k / 4f) % 1f) * reach;
                Vector3f P = new Vector3f(O).lerp(D, u);
                c.bb(P);
                sparkle(g, c.m(), 0, 0, 0.18f, t * 0.2f + k, c.c(WHITE, a), c.c(col, 0f));
                c.pop();
            }
        }
    }

    // ───────────────────────── 息法：法轮 + 吐纳光环 ─────────────────────────

    private void breath(C c) {
        float t = c.t, A = c.env(6, 12);
        int bits = c.variant == 0 ? 1 : c.variant;
        int gold = 0xFFD35A, pale = 0xCFF6FF, violet = 0xC9A0FF;
        int[] owned = new int[3];
        int no = 0;
        if ((bits & 1) != 0) owned[no++] = gold;
        if ((bits & 2) != 0) owned[no++] = pale;
        if ((bits & 4) != 0) owned[no++] = violet;
        float h = c.h;
        VertexConsumer g = c.glow(), vc = c.toon();
        float fpK = c.selfFP ? 0.45f : 1f;
        // 吐纳：三道光环由外向内收拢（吸气），再在结尾向外散开（呼气）
        for (int j = 0; j < 3; j++) {
            float ph = ((t + j * 4f) % 12f) / 12f;
            float r = 1.8f - 1.35f * easeOut(ph);
            float a = A * fpK * Mth.sin((float) Math.PI * ph);
            int cc = owned[j % no];
            c.flat(h * (0.35f + 0.12f * j));
            ring(g, c.m(), r, r + 0.07f, c.c(WHITE, 0.8f * a), c.c(cc, 0f), 40);
            ring(g, c.m(), r - 0.15f, r, c.c(cc, 0f), c.c(cc, 0.6f * a), 40);
            c.pop();
        }
        float ex = t - (c.life - 14f);
        if (ex > 0) {
            float a = clamp01(1f - ex / 12f);
            c.flat(h * 0.5f);
            float r = 0.5f + ex * 0.22f;
            ring(g, c.m(), r, r + 0.18f, c.c(owned[0], 0.8f * a), c.c(owned[0], 0f), 48);
            c.pop();
        }
        // 外息：金色护体光流（沿身体上升的光条）+ 腰间金环
        if ((bits & 1) != 0) {
            for (int i = 0; i < 8; i++) {
                float ang = TAU * i / 8f + t * 0.03f;
                float ph = (t * 0.05f + hash(i * 7)) % 1f;
                float y0 = ph * h * 1.1f;
                Vector3f a0 = new Vector3f(Mth.cos(ang) * 0.5f, y0, Mth.sin(ang) * 0.5f);
                Vector3f a1 = new Vector3f(Mth.cos(ang) * 0.46f, y0 + 0.5f, Mth.sin(ang) * 0.46f);
                ribSpindle(g, c.m(), a0, a1, 0.035f, 0.5f, c.c(gold, 0f), c.c(gold, 0.8f * A * fpK * Mth.sin((float) Math.PI * ph)), c.cam);
            }
            c.flat(h * 0.5f);
            Matrix4f m = new Matrix4f(c.m()).rotateZ(t * 0.1f);
            for (int k = 0; k < 4; k++) {
                float a0 = k * TAU / 4f;
                ring(g, m, 0.56f, 0.62f, c.c(gold, 0.8f * A * fpK), c.c(gold, 0.1f * A), a0, a0 + 1.2f, 16);
            }
            c.pop();
        }
        // 中息：足下白色莲纹（不会措手不及）
        if ((bits & 2) != 0) {
            c.flat(0.03f);
            Matrix4f m = new Matrix4f(c.m()).rotateZ(-t * 0.04f);
            float s = easeOutBack(clamp01(t / 8f));
            ring(vc, m, 0.78f * s, 0.82f * s, c.c(pale, 0.9f * A), c.c(pale, 0.9f * A), 48);
            for (int k = 0; k < 8; k++) {
                float a = TAU * k / 8f;
                needle(vc, m, Mth.cos(a) * 0.22f * s, Mth.sin(a) * 0.22f * s, Mth.cos(a) * 0.76f * s, Mth.sin(a) * 0.76f * s, 0.09f * s,
                        c.c(pale, 0.55f * A), c.c(WHITE, 0.2f * A));
            }
            disc(g, m, 0, 0, 1.0f * s, c.c(pale, 0.3f * A), c.c(pale, 0f), 32);
            c.pop();
        }
        if (c.selfFP) return;
        // 脑后法轮
        c.body();
        c.s.translate(0, h * 0.94f, -0.34f);
        float s = easeOutBack(clamp01((t - 2f) / 6f));
        Matrix4f m = new Matrix4f(c.m()).scale(s, s, s);
        int wc = owned[0];
        disc(g, m, 0, 0, 0.62f, c.c(wc, 0.4f * A), c.c(wc, 0f), 32);
        Matrix4f mw = new Matrix4f(m).rotateZ(t * 0.06f);
        ring(vc, mw, 0.34f, 0.39f, c.c(wc, A), c.c(WHITE, A), 40);
        for (int k = 0; k < 8; k++) {
            float a = TAU * k / 8f;
            bar(vc, mw, Mth.cos(a) * 0.21f, Mth.sin(a) * 0.21f, a, 0.13f, 0.014f, c.c(wc, A));
            disc(vc, mw, Mth.cos(a) * 0.42f, Mth.sin(a) * 0.42f, 0.03f, c.c(WHITE, A), c.c(wc, A), 10);
        }
        disc(vc, mw, 0, 0, 0.075f, c.c(WHITE, A), c.c(wc, A), 16);
        if ((bits & 4) != 0) ring(g, new Matrix4f(m).rotateZ(-t * 0.1f), 0.46f, 0.52f, c.c(violet, 0.9f * A), c.c(violet, 0f), 40);
        c.pop();
        // 内息：胸前紫色心灯
        if ((bits & 4) != 0) {
            Vector3f P = c.bodyPt(0, h * 0.6f, 0.32f);
            c.bb(P);
            int[] lamp = {0x6A3FD8, 0x9A6BFF, 0xD8C2FF, 0xFFFFFF};
            toonFlame(c, vc, c.m(), 0.11f, 0, 1, 0.9f, lamp, A, t);
            disc(g, c.m(), 0, 0, 0.4f, c.c(violet, 0.45f * A), c.c(violet, 0f), 16);
            c.pop();
        }
    }

    // ───────────────────────── 夜叉空行：背后羽翼 + 上升气流 ─────────────────────────

    private void yaksha(C c) {
        float t = c.t, A = c.env(3, 12);
        int col = c.col, gold = 0xFFD35A;
        float h = c.h;
        VertexConsumer g = c.glow(), vc = c.toon();
        // 足下上升气流
        for (int j = 0; j < 3; j++) {
            float ph = (t * 0.05f + j / 3f) % 1f;
            float y = ph * 1.3f;
            float r = 0.45f + 0.45f * ph;
            float a = A * (1f - ph) * (c.selfFP ? 0.5f : 1f);
            c.flat(y + 0.05f);
            float rot = t * 0.35f + j * 2f;
            ring(g, c.m(), r, r + 0.06f, c.c(WHITE, 0.85f * a), c.c(col, 0f), rot, rot + 2.2f, 24);
            ring(g, c.m(), r, r + 0.05f, c.c(gold, 0.6f * a), c.c(col, 0f), rot + 3.1f, rot + 4.6f, 20);
            c.pop();
        }
        for (int i = 0; i < 6; i++) {
            float ang = TAU * i / 6f + t * 0.05f;
            float ph = (t * 0.07f + hash(i * 3)) % 1f;
            Vector3f a0 = new Vector3f(Mth.cos(ang) * 0.6f, ph * 1.6f, Mth.sin(ang) * 0.6f);
            ribSpindle(g, c.m(), a0, new Vector3f(a0).add(0, 0.45f, 0), 0.02f, 0.5f, c.c(WHITE, 0f), c.c(WHITE, 0.6f * A * (1f - ph)), c.cam);
        }
        if (c.selfFP) return;
        // 羽翼：由收拢到展开（easeOutBack），之后缓慢扇动
        float u = easeOutBack(clamp01((t - 1f) / 8f));
        float flap = Mth.sin(t * 0.42f) * 0.2f * clamp01((t - 8f) / 4f);
        Quaternionf bq = new Quaternionf().rotationY(-c.yaw);
        for (int side = -1; side <= 1; side += 2) {
            Vector3f root = bq.transform(new Vector3f(side * 0.14f, h * 0.76f, -0.2f));
            for (int j = 0; j < 6; j++) {
                float phi = Mth.lerp(u, 1.25f, -0.45f + j * 0.3f) + flap * (1f + j * 0.15f);
                float L = (0.75f + 0.13f * j) * (0.35f + 0.65f * u);
                Vector3f d = bq.transform(new Vector3f(side * Mth.cos(phi), Mth.sin(phi), -0.38f - 0.06f * j)).normalize();
                Vector3f tip = new Vector3f(root).add(new Vector3f(d).mul(L));
                Vector3f base = new Vector3f(root).add(new Vector3f(d).mul(0.08f));
                float fa = A * (0.55f + 0.06f * j);
                ribSpindle(vc, c.m(), base, tip, 0.1f + 0.012f * j, 0.4f, c.c(col, 0.2f * fa), c.c(col, 0.7f * fa), c.cam);
                ribSpindle(vc, c.m(), base, new Vector3f(base).lerp(tip, 0.85f), 0.025f, 0.45f, c.c(WHITE, 0.3f * fa), c.c(WHITE, 0.95f * fa), c.cam);
                ribSpindle(g, c.m(), base, tip, 0.17f, 0.45f, c.c(gold, 0f), c.c(gold, 0.35f * fa), c.cam);
            }
        }
        // 飘落的光羽
        for (int i = 0; i < 6; i++) {
            float ph = (t * 0.03f + hash(c.e.getId() + i * 5)) % 1f;
            float ang = hash(i * 9) * TAU;
            Vector3f P = new Vector3f(Mth.cos(ang) * 0.9f + Mth.sin(t * 0.2f + i) * 0.15f, h * 1.1f - ph * h, Mth.sin(ang) * 0.9f);
            c.bb(P);
            Matrix4f m = new Matrix4f(c.m()).rotateZ(Mth.sin(t * 0.25f + i) * 0.8f);
            float a = A * Mth.sin((float) Math.PI * ph);
            needle(vc, m, 0, -0.1f, 0, 0.1f, 0.035f, c.c(WHITE, a), c.c(col, 0.3f * a));
            c.pop();
        }
    }

    // ───────────────────────── 初级防护：六角结界 ─────────────────────────

    private void ward(C c) {
        float t = c.t, A = c.env(0, 10) * (c.selfFP ? 0.35f : 1f);
        int col = c.col, light = 0xC7D4FF;
        float h = c.h;
        float R = Math.max(1.05f, h * 0.68f);
        Vector3f O = new Vector3f(0, h * 0.5f, 0);
        VertexConsumer g = c.glow(), vc = c.toon();
        float[] lats = {-62, -38, -14, 10, 34, 58, 82};
        for (int li = 0; li < lats.length; li++) {
            float lat = (float) Math.toRadians(lats[li]);
            int n = Math.max(1, Math.round(14 * Mth.cos(lat)));
            float age = t - li * 1.4f;
            if (age <= 0) continue;
            float s = easeOutBack(clamp01(age / 3f));
            float flash = clamp01(1f - age / 4f);
            for (int j = 0; j < n; j++) {
                float lon = TAU * j / n + li * 0.21f;
                Vector3f nrm = new Vector3f(Mth.cos(lat) * Mth.cos(lon), Mth.sin(lat), Mth.cos(lat) * Mth.sin(lon));
                Vector3f e1 = new Vector3f(-Mth.sin(lon), 0, Mth.cos(lon));
                Vector3f e2 = new Vector3f(nrm).cross(e1).normalize();
                Vector3f ctr = new Vector3f(O).add(new Vector3f(nrm).mul(R));
                float pr = R * (li == lats.length - 1 ? 0.22f : 0.235f) * s;
                float shimmer = 0.5f + 0.5f * Mth.sin(t * 0.3f + j * 1.7f + li * 2.3f);
                int fill = c.c(mix(col, WHITE, flash), (0.1f + 0.06f * shimmer + 0.5f * flash) * A);
                int edge = c.c(mix(light, WHITE, flash), (0.55f + 0.45f * flash) * A);
                polyPanel(vc, g, c.m(), ctr, e1, e2, pr, 6, (float) Math.PI / 6f, fill, edge, 0.82f);
            }
        }
        // 结界成型后：一道扫描环由下而上
        if (t > 10) {
            float ph = ((t - 10f) * 0.07f) % 1f;
            float y = -R + 2f * R * ph;
            float rr = Mth.sqrt(Math.max(0, R * R - y * y)) + 0.02f;
            c.flat(O.y + y);
            ring(g, c.m(), rr - 0.05f, rr + 0.03f, c.c(light, 0.0f), c.c(WHITE, 0.7f * A * Mth.sin((float) Math.PI * ph)), 48);
            c.pop();
        }
        // 成型瞬间的地面闪环
        if (t < 8) {
            c.flat(0.04f);
            float r = 0.3f + R * easeOut(t / 5f);
            ring(g, c.m(), r, r + 0.12f, c.c(WHITE, (1f - t / 8f) * A), c.c(col, 0f), 40);
            c.pop();
        }
    }

    // ───────────────────────── 无视我：故障风碎散残影 ─────────────────────────

    private void vanish(C c) {
        float t = c.t;
        int col = c.col, cyan = 0x5CF6FF;
        float H = 1.8f;
        VertexConsumer vc = c.toon(), g = c.glow();
        // 圆柱公告板：横条永远水平
        float face = (float) Math.atan2(c.cam.x, c.cam.z);
        c.s.pushPose();
        c.s.mulPose(new Quaternionf().rotationY(face));
        Matrix4f m = c.m();
        int rows = 18;
        int seed = (int) (t * 1.5f) * 71 + c.e.getId();
        for (int i = 0; i < rows; i++) {
            float y = (i + 0.5f) / rows * H;
            float td = (rows - 1 - i) * 0.45f;       // 由上而下碎散
            float k = t - td;
            float a = k < 0 ? 0.85f : clamp01(1f - k / 6f);
            if (a <= 0.01f) continue;
            float w = y < 0.75f ? 0.17f : y < 1.42f ? 0.27f : y < 1.5f ? 0.1f : 0.15f;
            float dx = k < 0 ? 0 : (hash(seed + i * 13) - 0.5f) * 0.9f * clamp01(k / 3f);
            float th = H / rows * 0.42f;
            bar(vc, m, dx - 0.03f, y, 0, w, th, c.c(cyan, 0.7f * a));
            bar(vc, m, dx + 0.03f, y, 0, w, th, c.c(col, 0.7f * a));
            bar(vc, m, dx, y, 0, w * 0.92f, th * 0.5f, c.c(WHITE, 0.85f * a));
        }
        c.pop();
        // 像素碎片
        for (int i = 0; i < 26; i++) {
            float st = hash(c.e.getId() + i * 3) * 6f;
            float k = t - st;
            if (k < 0 || k > 14) continue;
            float ang = hash(i * 17) * TAU;
            float y0 = hash(i * 5 + 1) * H;
            Vector3f P = new Vector3f(Mth.cos(ang) * (0.2f + 0.05f * k), y0 + 0.05f * k, Mth.sin(ang) * (0.2f + 0.05f * k));
            c.bb(P);
            float s = 0.04f + 0.03f * hash(i * 7);
            float a = 1f - k / 14f;
            bar(vc, c.m(), 0, 0, 0, s, s, c.c(i % 2 == 0 ? col : cyan, 0.9f * a));
            c.pop();
        }
        // 「嘘」的涟漪
        if (t < 10) {
            c.bb(0, 1.45f, 0);
            float r = 0.15f + t * 0.13f;
            ring(g, c.m(), r, r + 0.04f, c.c(WHITE, (1f - t / 10f)), c.c(col, 0f), 32);
            c.pop();
        }
    }

    // ───────────────────────── 黄泉活力：鬼火 + 幽焰 ─────────────────────────

    private void nether(C c) {
        float t = c.t, A = c.env(5, 10);
        int col = c.col, deep = 0x0E6E78, dark = 0x2A1450;
        int[] cols = {deep, 0x2AB8C8, 0x9FFFF4, 0xFFFFFF};
        float h = c.h;
        VertexConsumer g = c.glow(), vc = c.toon();
        float fpK = c.selfFP ? 0.5f : 1f;
        // 施放瞬间：地面暗紫冲击环
        if (t < 9) {
            c.flat(0.04f);
            float r = 0.3f + t * 0.24f, a = 1f - t / 9f;
            ring(vc, c.m(), r, r + 0.14f, c.c(dark, 0.7f * a), c.c(dark, 0f), 40);
            ring(g, c.m(), r + 0.1f, r + 0.18f, c.c(col, 0.8f * a), c.c(col, 0f), 40);
            c.pop();
        }
        // 足下幽焰
        for (int i = 0; i < 9; i++) {
            float ang = TAU * i / 9f + t * 0.02f;
            float sway = Mth.sin(t * 0.3f + i * 1.3f) * 0.06f;
            float hh = 0.3f + 0.14f * Mth.sin(t * 0.45f + i * 2.1f);
            Vector3f a0 = new Vector3f(Mth.cos(ang) * 0.45f, 0.02f, Mth.sin(ang) * 0.45f);
            Vector3f a1 = new Vector3f(a0.x * 0.85f + sway, hh, a0.z * 0.85f);
            ribSpindle(g, c.m(), a0, a1, 0.07f, 0.3f, c.c(col, 0.1f * A), c.c(col, 0.75f * A * fpK), c.cam);
        }
        // 环绕鬼火
        int n = 2 + Math.min(4, c.variant);
        for (int i = 0; i < n; i++) {
            float base = t * 0.12f + i * TAU / n;
            Vector3f P = orbit(base, i, t, h);
            if (c.selfFP && new Vector3f(P).sub(c.cam).length() < 0.35f) continue;
            int m = 8;
            Vector3f[] tr = new Vector3f[m + 1];
            for (int j = 0; j <= m; j++) tr[j] = orbit(base - 0.9f * (1f - j / (float) m), i, t - 7f * (1f - j / (float) m), h);
            ribLerp(g, c.m(), tr, 0.005f, 0.07f, c.c(col, 0f), c.c(col, 0.7f * A * fpK), c.cam);
            Vector3f V = new Vector3f(P).sub(orbit(base - 0.1f, i, t - 0.8f, h));
            float[] sd = screenOf(c, V);
            c.bb(P);
            float fl = 0.85f + 0.15f * Mth.sin(t * 1.3f + i * 2f);
            toonFlame(c, vc, c.m(), 0.13f * fl, -sd[0] * 0.5f, -sd[1] * 0.5f + 0.5f, 1.4f, cols, A * fpK, t + i * 3f);
            disc(g, c.m(), 0, 0, 0.38f, c.c(col, 0.35f * A * fpK), c.c(col, 0f), 16);
            c.pop();
        }
        // 右拳缠绕的幽焰
        if (!c.selfFP) {
            Vector3f F = c.bodyPt(-0.38f, h * 0.42f, 0.08f);
            c.bb(F);
            toonFlame(c, vc, c.m(), 0.12f, 0, 1, 1.3f, cols, 0.9f * A, t * 1.3f);
            disc(g, c.m(), 0, 0, 0.32f, c.c(col, 0.4f * A), c.c(col, 0f), 14);
            c.pop();
        }
    }

    private static Vector3f orbit(float ang, int i, float t, float h) {
        float r = 0.75f;
        return new Vector3f(Mth.cos(ang) * r, h * 0.55f + 0.18f * Mth.sin(t * 0.2f + i * 1.7f), Mth.sin(ang) * r);
    }

    // ───────────────────────── 基础掌法：巨大掌印 ─────────────────────────

    private void palm(C c) {
        float t = c.t;
        int col = c.col;
        Vec3 dv = Vec3.directionFromRotation(c.e.getXRot(), c.e.getYRot());
        Vector3f dir = new Vector3f((float) dv.x, (float) dv.y, (float) dv.z);
        float d = 0.15f + 3.4f * easeOut(t / 10f);
        float S = 0.55f + 1.05f * easeOut(t / 8f);
        float A = clamp01(1f - (t - 5f) / 10f) * (c.ownerFP ? 0.75f : 1f);
        VertexConsumer g = c.glow(), vc = c.toon();
        Quaternionf q = new Quaternionf().rotationTo(0, 0, 1, dir.x, dir.y, dir.z);
        // 让掌印保持“手指朝上”：绕光轴滚正
        Vector3f upL = new Quaternionf(q).conjugate().transform(new Vector3f(0, 1, 0));
        float roll = (float) Math.atan2(-upL.x, upL.y);
        c.s.pushPose();
        c.s.mulPose(q);
        c.s.mulPose(new Quaternionf().rotationZ(-roll));
        Matrix4f base = c.m();
        // 速度线
        float[] side = axialSide(0, 0, 1);
        for (int i = 0; i < 12; i++) {
            float a = TAU * i / 12f + hash(c.e.getId() + i);
            float r = (0.5f + 0.5f * hash(i * 7)) * S;
            float len = (1.2f + 1.2f * hash(i * 3)) * clamp01(t / 3f);
            Vector3f lc = new Quaternionf(q).rotateZ(-roll).conjugate().transform(new Vector3f(c.cam));
            float[] sd = axialSide(lc.x - Mth.cos(a) * r, lc.y - Mth.sin(a) * r, lc.z - d);
            streak(g, base, Mth.cos(a) * r, Mth.sin(a) * r, d, len, 0.03f, sd[0], sd[1], c.c(WHITE, 0.7f * A), c.c(col, 0f));
        }
        // 冲击环
        Matrix4f mr = new Matrix4f(base).translate(0, 0, d * 0.7f);
        float rr = 0.3f + t * 0.16f;
        ring(g, mr, rr, rr + 0.1f, c.c(WHITE, 0.7f * A), c.c(col, 0f), 40);
        // 掌印：外晕 → 色块 → 白芯
        Matrix4f mp = new Matrix4f(base).translate(0, 0, d).scale(S, S, S);
        palmShape(g, new Matrix4f(mp).scale(1.18f, 1.18f, 1f), c.c(col, 0.45f * A));
        palmShape(vc, mp, c.c(col, 0.6f * A));
        palmShape(vc, new Matrix4f(mp).translate(0, 0.02f, 0.001f).scale(0.78f, 0.78f, 1f), c.c(WHITE, 0.55f * A));
        disc(g, mp, 0, 0, 0.6f, c.c(col, 0.3f * A), c.c(col, 0f), 24);
        c.pop();
    }

    /** 手掌剪影（XY 平面，手指朝 +Y，大小约 1） */
    private static void palmShape(VertexConsumer vc, Matrix4f m, int c) {
        disc(vc, m, 0, -0.08f, 0.27f, c, c, 24);
        bar(vc, m, 0, 0.05f, 0, 0.25f, 0.13f, c);
        float[] fx = {-0.18f, -0.06f, 0.06f, 0.18f}, ft = {0.52f, 0.62f, 0.58f, 0.46f};
        for (int i = 0; i < 4; i++) {
            float y0 = 0.12f, y1 = ft[i];
            bar(vc, m, fx[i], (y0 + y1) * 0.5f, (float) Math.PI / 2f, (y1 - y0) * 0.5f, 0.05f, c);
            disc(vc, m, fx[i], y1, 0.05f, c, c, 10);
        }
        float tx0 = 0.2f, ty0 = -0.06f, tx1 = 0.4f, ty1 = 0.16f;
        bar(vc, m, (tx0 + tx1) * 0.5f, (ty0 + ty1) * 0.5f, (float) Math.atan2(ty1 - ty0, tx1 - tx0),
                Mth.sqrt((tx1 - tx0) * (tx1 - tx0) + (ty1 - ty0) * (ty1 - ty0)) * 0.5f, 0.055f, c);
        disc(vc, m, tx1, ty1, 0.055f, c, c, 10);
    }

    // ───────────────────────── 起死回生：天降光柱 + 莲台 + 光羽 ─────────────────────────

    private void revive(C c) {
        float t = c.t, A = c.env(3, 14);
        int col = c.col, jade = 0x7FF2D0;
        float h = c.h;
        VertexConsumer g = c.glow(), vc = c.toon();
        // 光柱自天而降
        float bottom = Mth.lerp(easeOut(t / 5f), 14f, 0f);
        if (!c.selfFP) {
            Vector3f top = new Vector3f(0, 14f, 0), bot = new Vector3f(0, bottom, 0);
            float pulse = 0.85f + 0.15f * Mth.sin(t * 0.35f);
            ribLerp(g, c.m(), line(top, bot, 6), 1.5f * pulse, 1.5f * pulse, c.c(col, 0.08f * A), c.c(col, 0.18f * A), c.cam);
            ribLerp(g, c.m(), line(top, bot, 6), 0.7f * pulse, 0.7f * pulse, c.c(col, 0.25f * A), c.c(col, 0.5f * A), c.cam);
            ribLerp(vc, c.m(), line(top, bot, 6), 0.16f, 0.2f, c.c(WHITE, 0.4f * A), c.c(WHITE, 0.85f * A), c.cam);
        }
        // 环绕光束（从内外看都成立）
        for (int i = 0; i < 8; i++) {
            float ang = TAU * i / 8f + t * 0.04f;
            float r = 0.85f;
            Vector3f a0 = new Vector3f(Mth.cos(ang) * r, Math.max(bottom, 0), Mth.sin(ang) * r);
            Vector3f a1 = new Vector3f(Mth.cos(ang) * r, Math.max(bottom, 0) + 2.6f, Mth.sin(ang) * r);
            float a = A * (0.5f + 0.5f * Mth.sin(t * 0.3f + i * 1.3f));
            ribSpindle(g, c.m(), a0, a1, 0.06f, 0.35f, c.c(col, 0f), c.c(col, 0.6f * a), c.cam);
        }
        // 足下莲台
        c.flat(0.03f);
        float s = easeOutBack(clamp01((t - 2f) / 6f));
        Matrix4f m = new Matrix4f(c.m()).rotateZ(t * 0.03f).scale(s, s, s);
        disc(g, m, 0, 0, 1.3f, c.c(col, 0.35f * A), c.c(col, 0f), 36);
        ring(vc, m, 1.0f, 1.05f, c.c(col, 0.95f * A), c.c(WHITE, 0.95f * A), 48);
        ring(vc, m, 0.2f, 0.24f, c.c(WHITE, 0.9f * A), c.c(col, 0.9f * A), 24);
        for (int k = 0; k < 8; k++) {
            float a = TAU * k / 8f;
            needle(vc, m, Mth.cos(a) * 0.26f, Mth.sin(a) * 0.26f, Mth.cos(a) * 0.96f, Mth.sin(a) * 0.96f, 0.17f, c.c(col, 0.6f * A), c.c(WHITE, 0.25f * A));
            float b = a + TAU / 16f;
            needle(vc, m, Mth.cos(b) * 0.3f, Mth.sin(b) * 0.3f, Mth.cos(b) * 0.7f, Mth.sin(b) * 0.7f, 0.09f, c.c(jade, 0.55f * A), c.c(WHITE, 0.2f * A));
        }
        c.pop();
        // 上升光羽
        for (int i = 0; i < 12; i++) {
            float ph = (hash(c.e.getId() + i * 11) + t * 0.025f) % 1f;
            float ang = hash(i * 5 + 2) * TAU, rr = 0.3f + 0.6f * hash(i * 3);
            Vector3f P = new Vector3f(Mth.cos(ang) * rr + 0.1f * Mth.sin(t * 0.2f + i), ph * h * 1.5f, Mth.sin(ang) * rr);
            if (c.selfFP && P.y > h * 0.7f) continue;
            float a = A * Mth.sin((float) Math.PI * ph);
            c.bb(P);
            Matrix4f mf = new Matrix4f(c.m()).rotateZ(0.5f * Mth.sin(t * 0.3f + i));
            needle(vc, mf, 0, -0.11f, 0, 0.11f, 0.04f, c.c(WHITE, a), c.c(col, 0.3f * a));
            disc(g, mf, 0, 0, 0.16f, c.c(col, 0.4f * a), c.c(col, 0f), 10);
            c.pop();
        }
        // 胸口的复苏闪光（治疗落下时）
        float k = t - 6f;
        if (k > 0 && k < 8 && !c.selfFP) {
            c.bb(0, h * 0.6f, 0);
            m = c.m();
            float f = 1f - k / 8f;
            disc(g, m, 0, 0, 0.8f * f, c.c(WHITE, f), c.c(col, 0f), 24);
            sparkle(g, m, 0, 0, 1.3f * f + 0.2f, k * 0.1f, c.c(WHITE, f), c.c(col, 0f));
            ring(g, m, 0.3f + k * 0.3f, 0.4f + k * 0.3f, c.c(col, 0.9f * f), c.c(col, 0f), 36);
            c.pop();
        }
    }

    // ═════════════════════════ 魔法·专业法术（v3 同画风） ═════════════════════════

    /** 平面闪电符（Z 字形三折），中心 (cx,cy)、朝向 ang、尺寸 s */
    private static void boltGlyph(VertexConsumer vc, Matrix4f m, float cx, float cy, float ang, float s, float w, int col) {
        float[][] p = {{-0.35f, 1f}, {0.25f, 0.12f}, {-0.25f, -0.12f}, {0.35f, -1f}};
        float ca = Mth.cos(ang), sa = Mth.sin(ang);
        for (int i = 0; i < 3; i++) {
            float x0 = cx + (p[i][0] * ca - p[i][1] * sa) * s, y0 = cy + (p[i][0] * sa + p[i][1] * ca) * s;
            float x1 = cx + (p[i + 1][0] * ca - p[i + 1][1] * sa) * s, y1 = cy + (p[i + 1][0] * sa + p[i + 1][1] * ca) * s;
            float hl = Mth.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0)) * 0.5f + w;
            bar(vc, m, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f, (float) Math.atan2(y1 - y0, x1 - x0), hl, w, col);
        }
    }

    // ───────────────────────── 轰雷剑：剑身聚雷 → 斜向雷光斩 ─────────────────────────

    private void thunderBlade(C c) {
        float t = c.t, h = c.h;
        int col = c.col, deep = 0x3A6BFF;
        VertexConsumer g = c.glow(), vc = c.toon();
        float fpK = c.selfFP ? 0.4f : 1f;
        int seed = (int) (t * 2f) * 41 + c.e.getId() * 13;
        float flick = ((int) (t * 2f) % 3 == 1) ? 0.55f : 1f;
        // 1) 聚雷：举剑，雷光自护手向剑尖爬满剑身，四周电弧向剑尖汇聚
        if (t < 7.5f) {
            float k = clamp01(t / 5f), a = clamp01((7.5f - t) / 1.5f) * fpK;
            Vector3f hilt = c.bodyPt(-0.36f, h * 0.98f, 0.12f);
            Vector3f tip = c.bodyPt(-0.3f, h * 0.98f + (c.variant == 1 ? 1.1f : 0.55f), -0.05f);
            Vector3f cur = new Vector3f(hilt).lerp(tip, easeOut(k));
            Vector3f[] blade = boltPts(hilt, cur, 6, 0.05f, seed);
            ribLerp(g, c.m(), blade, 0.2f, 0.3f, c.c(col, 0.5f * a * flick), c.c(WHITE, 0.6f * a * flick), c.cam);
            ribLerp(vc, c.m(), blade, 0.03f, 0.05f, c.c(WHITE, a), c.c(WHITE, a), c.cam);
            for (int i = 0; i < 4; i++) {
                float ang = hash(seed + i * 7) * TAU, rr = 1.3f - 1.0f * k;
                Vector3f from = new Vector3f(cur).add(Mth.cos(ang) * rr, (hash(seed + i * 3) - 0.3f) * rr, Mth.sin(ang) * rr);
                Vector3f[] arc = boltPts(from, cur, 5, 0.14f, seed + i * 31);
                ribLerp(g, c.m(), arc, 0.012f, 0.07f, c.c(deep, 0f), c.c(col, 0.7f * a * flick), c.cam);
                ribLerp(vc, c.m(), arc, 0.004f, 0.016f, c.c(WHITE, 0f), c.c(WHITE, 0.9f * a * flick), c.cam);
            }
            c.bb(cur);
            Matrix4f m = c.m();
            disc(g, m, 0, 0, 0.3f + 0.2f * k, c.c(WHITE, 0.8f * a), c.c(col, 0f), 18);
            sparkle(g, m, 0, 0, 0.45f + 0.45f * k * flick, t * 0.5f, c.c(WHITE, a), c.c(col, 0f));
            c.pop();
        }
        // 2) 雷光斩：右上 → 左下的新月刀光，雷纹沿刀弧窜动
        if (t > 5f) {
            float st = t - 5f;
            float sw = easeOut(clamp01(st / 2.2f));
            float fade = clamp01(1f - (st - 2.5f) / 4.5f);
            if (fade <= 0) return;
            float a = fade * (c.selfFP ? 0.7f : 1f);
            float zf = c.selfFP ? 1.15f : 0.8f, yc = h * 0.62f, tilt = -0.7f;
            float halfW = 1.4f, sag = 0.45f, depth = 0.4f;
            c.body();
            c.s.translate(0, yc, zf);
            c.s.mulPose(new Quaternionf().rotationZ(tilt));
            Matrix4f m = c.m();
            float t0 = -1f, t1 = -1f + 2f * sw, tail = Math.max(-1f, t1 - 2f * clamp01(st / 5f) - 0.2f);
            crescent(g, m, halfW, sag, 0.55f, depth, 0f, 1f, c.c(WHITE, 0.85f * a), c.c(col, 0f), Math.max(t0, tail), t1, 44);
            crescent(vc, m, halfW, sag, 0.2f, depth, 0f, 1f, c.c(WHITE, a), c.c(col, 0.75f * a), Math.max(t0, tail), t1, 44);
            crescent(vc, m, halfW, sag, 0.06f, depth, 0f, 1f, c.c(WHITE, a), c.c(WHITE, a), Math.max(t0, tail), t1, 44);
            c.pop();
            // 沿刀弧窜动的雷纹（锚点坐标手算，供面向相机的条带使用）
            Matrix4f L = new Matrix4f().rotateY(-c.yaw).translate(0, yc, zf).rotateZ(tilt);
            int n = 14;
            for (int b = 0; b < 2; b++) {
                Vector3f[] pts = new Vector3f[n + 1];
                for (int i = 0; i <= n; i++) {
                    float u = Mth.lerp(i / (float) n, Math.max(t0, tail), t1);
                    float base = 1f - u * u;
                    float j = (i == 0 || i == n) ? 0 : (hash(seed + b * 101 + i * 13) - 0.5f) * 0.22f;
                    pts[i] = L.transformPosition(new Vector3f(halfW * u, sag * base - 0.1f + j, depth * base + j * 0.5f));
                }
                ribLerp(g, c.m(), pts, 0.05f, 0.1f, c.c(col, 0.15f * a * flick), c.c(col, 0.7f * a * flick), c.cam);
                ribLerp(vc, c.m(), pts, 0.008f, 0.02f, c.c(WHITE, 0.2f * a * flick), c.c(WHITE, a * flick), c.cam);
            }
            // 刀尖火花
            float ue = t1, be = 1f - ue * ue;
            Vector3f E = L.transformPosition(new Vector3f(halfW * ue, sag * be, depth * be));
            c.bb(E);
            m = c.m();
            disc(g, m, 0, 0, 0.35f * fade, c.c(WHITE, 0.8f * a), c.c(col, 0f), 14);
            starburst(g, m, 7, 0.04f, 0.6f * fade, t * 0.3f, seed, c.c(WHITE, 0.8f * a), c.c(col, 0f));
            c.pop();
        }
    }

    // ───────────────────────── 轰雷剑标记：足下雷纹阵 + 头顶倒计时雷印 ─────────────────────────

    private void thunderMark(C c) {
        float t = c.t, h = c.h, A = c.env(4, 6) * (c.selfFP ? 0.4f : 1f);
        int col = c.col, deep = 0x2F5BFF;
        VertexConsumer g = c.glow(), vc = c.toon();
        int seed = (int) (t * 1.5f) * 29 + c.e.getId() * 5;
        float w = Math.max(0.7f, c.follow != null ? c.follow.getBbWidth() * 1.15f : 0.8f);
        float pulse = 0.75f + 0.25f * Mth.sin(t * 0.6f);
        float spin = easeOutBack(clamp01(t / 6f));
        c.flat(0.05f);
        Matrix4f m = c.m();
        ring(g, m, w * 0.86f * spin, w * spin, c.c(deep, 0f), c.c(col, 0.8f * A * pulse), 40);
        ring(vc, m, w * 0.965f * spin, w * spin, c.c(WHITE, 0.9f * A), c.c(WHITE, 0.9f * A), 40);
        ring(g, m, w * 0.55f * spin, w * 0.6f * spin, c.c(col, 0.55f * A), c.c(col, 0.55f * A), 32);
        c.s.mulPose(new Quaternionf().rotationZ(t * 0.08f));
        m = c.m();
        for (int k = 0; k < 3; k++) {
            float ang = TAU * k / 3f;
            float r = w * 0.78f * spin;
            boltGlyph(vc, m, Mth.cos(ang) * r, Mth.sin(ang) * r, ang, w * 0.12f, 0.018f, c.c(WHITE, 0.95f * A));
            boltGlyph(g, m, Mth.cos(ang) * r, Mth.sin(ang) * r, ang, w * 0.12f, 0.05f, c.c(col, 0.5f * A));
        }
        c.pop();
        // 随机窜上身体的电弧
        for (int i = 0; i < 2; i++) {
            int sd = seed + i * 97;
            if (hash(sd) > 0.55f) continue;
            float ang = hash(sd + 1) * TAU, ang2 = ang + (hash(sd + 2) - 0.5f) * 1.5f;
            Vector3f a = new Vector3f(Mth.cos(ang) * w, 0.05f, Mth.sin(ang) * w);
            Vector3f b = new Vector3f(Mth.cos(ang2) * w * 0.55f, h * (0.35f + 0.5f * hash(sd + 3)), Mth.sin(ang2) * w * 0.55f);
            Vector3f[] p = boltPts(a, b, 6, 0.16f, sd);
            ribLerp(g, c.m(), p, 0.1f, 0.04f, c.c(col, 0.6f * A), c.c(col, 0.1f * A), c.cam);
            ribLerp(vc, c.m(), p, 0.02f, 0.008f, c.c(WHITE, 0.95f * A), c.c(WHITE, 0.3f * A), c.cam);
        }
        // 头顶雷印：菱形 + 闪电符 + 倒计时弧
        float left = clamp01(1f - t / Math.max(1f, c.life - 8f));
        c.bb(0, h + 0.55f, 0);
        m = c.m();
        disc(g, m, 0, 0, 0.34f * pulse, c.c(col, 0.5f * A), c.c(col, 0f), 18);
        polyLine(vc, m, 4, 0.2f, 0f, 0.016f, c.c(WHITE, 0.95f * A));
        ring(g, m, 0.27f, 0.31f, c.c(col, 0.85f * A), c.c(col, 0.85f * A), (float) Math.PI / 2f, (float) Math.PI / 2f + TAU * left, 36);
        boltGlyph(vc, m, 0, 0, 0f, 0.1f, 0.016f, c.c(WHITE, A));
        c.pop();
    }

    // ───────────────────────── 轰雷剑引爆：天降落雷 ─────────────────────────

    private void thunderStrike(C c) {
        float t = c.t;
        int col = c.col;
        VertexConsumer g = c.glow(), vc = c.toon();
        float on = t < 7 ? 1f : clamp01(1f - (t - 7f) / 5f);
        float flick = ((int) (t * 2f) % 3 == 1) ? 0.4f : 1f;
        float a = on * flick;
        int seed = (int) (t * 2f) * 61 + c.e.getId() * 3;
        Vector3f top = new Vector3f((hash(c.e.getId()) - 0.5f) * 2f, 18f, (hash(c.e.getId() + 9) - 0.5f) * 2f);
        Vector3f cur = new Vector3f(top).lerp(new Vector3f(), clamp01(t / 1.5f));
        Vector3f[] main = boltPts(top, cur, 22, 1.1f, seed);
        if (a > 0) {
            ribLerp(g, c.m(), main, 0.55f, 0.5f, c.c(col, 0.5f * a), c.c(col, 0.6f * a), c.cam);
            ribLerp(vc, c.m(), main, 0.08f, 0.1f, c.c(WHITE, a), c.c(WHITE, a), c.cam);
            for (int b = 0; b < 6; b++) {
                int idx = 2 + (int) (hash(seed + b * 11) * (main.length - 4));
                Vector3f from = main[idx];
                Vector3f to = new Vector3f(from).add((hash(seed + b * 3) - 0.5f) * 4f, -1.5f - hash(seed + b * 5) * 2f, (hash(seed + b * 7) - 0.5f) * 4f);
                Vector3f[] br = boltPts(from, to, 5, 0.4f, seed + b * 19);
                ribLerp(g, c.m(), br, 0.2f, 0.02f, c.c(col, 0.5f * a), c.c(col, 0f), c.cam);
                ribLerp(vc, c.m(), br, 0.035f, 0.005f, c.c(WHITE, 0.9f * a), c.c(WHITE, 0.1f * a), c.cam);
            }
        }
        if (t < 1.5f) return;
        float k = t - 1.5f;
        float f6 = clamp01(1f - k / 6f), f8 = clamp01(1f - k / 8f);
        c.flat(0.06f);
        Matrix4f m = c.m();
        disc(g, m, 0, 0, 2.6f * f8 + 0.3f, c.c(WHITE, 0.9f * f6), c.c(col, 0f), 28);
        float r = 0.4f + 3.4f * easeOut(clamp01(k / 8f));
        ring(g, m, r - 0.35f, r, c.c(col, 0f), c.c(WHITE, 0.8f * f8), 48);
        ring(vc, m, r - 0.06f, r, c.c(WHITE, 0.9f * f8), c.c(col, 0.6f * f8), 48);
        c.pop();
        for (int i = 0; i < 6; i++) {
            float ang = TAU * i / 6f + hash(c.e.getId() + i) * 0.6f;
            float R = 0.6f + 2.4f * easeOut(clamp01(k / 4f));
            Vector3f[] arc = boltPts(new Vector3f(0, 0.06f, 0), new Vector3f(Mth.cos(ang) * R, 0.06f, Mth.sin(ang) * R), 6, 0.25f, seed + i * 37);
            ribLerp(g, c.m(), arc, 0.12f, 0.02f, c.c(col, 0.6f * f6 * flick), c.c(col, 0f), c.cam);
            ribLerp(vc, c.m(), arc, 0.025f, 0.004f, c.c(WHITE, 0.9f * f6 * flick), c.c(WHITE, 0f), c.cam);
        }
        c.bb(0, 0.9f, 0);
        m = c.m();
        starburst(g, m, 12, 0.1f, 2.2f * f6 + 0.2f, t * 0.1f, seed, c.c(WHITE, 0.9f * f6), c.c(col, 0f));
        sparkle(g, m, 0, 0, 1.6f * f6, 0.3f, c.c(WHITE, f6), c.c(col, 0f));
        c.pop();
    }

    // ───────────────────────── 光亮术：物品点亮的星芒 + 扩散光环 ─────────────────────────

    private void lumen(C c) {
        float t = c.t, A = c.env(0, 10) * (c.selfFP ? 0.45f : 1f);
        int col = c.col;
        VertexConsumer g = c.glow();
        Vector3f P = c.variant == 1 ? new Vector3f(0, 0.3f, 0)
                : c.selfFP ? c.bodyPt(-0.3f, c.h + 0.1f, 1.0f) : c.bodyPt(-0.36f, c.h + 0.35f, 0.1f);
        float k = easeOut(clamp01(t / 6f)), flash = clamp01(1f - t / 8f);
        c.bb(P);
        Matrix4f m = c.m();
        disc(g, m, 0, 0, 0.3f + 1.3f * flash * k, c.c(WHITE, 0.9f * flash * A), c.c(col, 0f), 24);
        disc(g, m, 0, 0, 0.45f, c.c(col, 0.5f * A), c.c(col, 0f), 20);
        sparkle(g, m, 0, 0, 0.6f + 1.5f * k * (0.4f + 0.6f * flash), t * 0.05f, c.c(WHITE, A), c.c(col, 0f));
        starburst(g, m, 10, 0.1f, 0.9f + 0.4f * k, t * 0.06f, 7, c.c(col, 0.5f * A), c.c(col, 0f));
        c.pop();
        for (int i = 0; i < 2; i++) {
            float rt = t - i * 4f;
            if (rt <= 0 || rt > 16) continue;
            float r = 0.3f + 6f * easeOut(rt / 16f), fa = clamp01(1f - rt / 16f);
            c.s.pushPose();
            c.s.translate(P.x, P.y, P.z);
            c.s.mulPose(new Quaternionf().rotationX((float) Math.PI / 2f));
            ring(g, c.m(), r - 0.3f, r, c.c(col, 0f), c.c(WHITE, 0.6f * fa * A), 56);
            c.pop();
        }
        for (int i = 0; i < 10; i++) {
            float ph = (t * 0.04f + hash(i * 13)) % 1f;
            Vector3f q = new Vector3f(P).add((hash(i * 7) - 0.5f) * 1.2f, ph * 1.6f - 0.2f, (hash(i * 11) - 0.5f) * 1.2f);
            c.bb(q);
            sparkle(g, c.m(), 0, 0, 0.12f * (1f - ph) + 0.03f, t * 0.1f + i, c.c(WHITE, (1f - ph) * A), c.c(col, 0f));
            c.pop();
        }
    }

    // ───────────────────────── 照明术：身前四枚浮空光球 ─────────────────────────

    private void lightOrbs(C c) {
        float t = c.t;
        int col = c.col;
        VertexConsumer g = c.glow(), vc = c.toon();
        float fpA = c.selfFP ? 0.5f : 1f;
        float[] xs = {0.9f, 0.3f, -0.3f, -0.9f};
        Vector3f chest = c.bodyPt(0, c.h * 0.65f, 0.3f);
        for (int i = 0; i < 4; i++) {
            float spawn = easeOutBack(clamp01((t - i * 1.5f) / 10f));
            if (spawn <= 0) continue;
            float bob = 0.08f * Mth.sin(t * 0.09f + i * 1.6f);
            float zf = (c.selfFP ? 1.15f : 0.75f) - 0.12f * Math.abs(xs[i]);
            Vector3f slot = c.bodyPt(xs[i], c.h + 0.15f + bob + (c.selfFP ? 0.2f : 0f), zf);
            Vector3f P = new Vector3f(chest).lerp(slot, spawn);
            float pulse = 0.85f + 0.15f * Mth.sin(t * 0.25f + i * 2.1f);
            c.bb(P);
            Matrix4f m = c.m();
            disc(g, m, 0, 0, 0.45f * pulse, c.c(col, 0.35f * fpA), c.c(col, 0f), 20);
            disc(g, m, 0, 0, 0.17f, c.c(WHITE, 0.95f * fpA), c.c(col, 0.4f * fpA), 16);
            disc(vc, m, 0, 0, 0.07f, c.c(WHITE, fpA), c.c(WHITE, fpA), 12);
            sparkle(g, m, 0, 0, 0.36f * pulse, t * 0.03f + i, c.c(WHITE, 0.8f * fpA), c.c(col, 0f));
            if (t < i * 1.5f + 8f) {
                float fl = clamp01(1f - (t - i * 1.5f) / 8f);
                disc(g, m, 0, 0, 0.9f * fl + 0.2f, c.c(WHITE, 0.7f * fl * fpA), c.c(col, 0f), 18);
            }
            c.pop();
            for (int j = 0; j < 2; j++) {
                float ang = t * 0.15f + j * (float) Math.PI + i;
                Vector3f q = new Vector3f(P).add(Mth.cos(ang) * 0.24f, Mth.sin(ang * 1.3f) * 0.06f, Mth.sin(ang) * 0.24f);
                c.bb(q);
                disc(g, c.m(), 0, 0, 0.05f, c.c(WHITE, 0.9f * fpA), c.c(col, 0f), 8);
                c.pop();
            }
        }
    }

    // ───────────────────────── 冻寒骨爪：飞掠的冰霜骨爪 + 三道爪痕 ─────────────────────────

    private void frostClaw(C c) {
        float t = c.t;
        Vector3f E = c.e.end();
        float len = E.length();
        if (len < 0.1f) return;
        Vector3f dir = new Vector3f(E).div(len);
        Vector3f S = beamStart(c, dir);
        boolean blight = c.variant == 1;
        int col = c.col, bone = 0xEAF6FF, dark = blight ? 0x5A2A86 : 0x2C7FB8;
        VertexConsumer g = c.glow(), vc = c.toon();
        float T = 4f, A = clamp01((c.life - t) / 6f);
        float head = easeOut(clamp01(t / T));
        Vector3f H = new Vector3f(S).lerp(E, head);
        Vector3f[] pp = perp(dir);
        Vector3f side = pp[0], up = pp[1].y < 0 ? new Vector3f(pp[1]).negate() : new Vector3f(pp[1]);
        // 寒雾拖尾
        if (t < T + 4) {
            float ta = clamp01(1f - (t - T) / 4f);
            Vector3f tailS = new Vector3f(S).lerp(E, Math.max(0, head - 0.5f));
            ribLerp(g, c.m(), line(tailS, H, 8), 0.02f, 0.45f, c.c(dark, 0f), c.c(col, 0.45f * ta), c.cam);
            ribLerp(vc, c.m(), line(tailS, H, 8), 0.005f, 0.05f, c.c(WHITE, 0f), c.c(WHITE, 0.55f * ta), c.cam);
        }
        // 骨爪：四根弯曲指骨张开飞来，抵达时合拢抓下
        if (t < T + 3) {
            float grip = clamp01((t - T + 1f) / 2.5f), ca = clamp01(1f - (t - T) / 3f);
            Vector3f palm = new Vector3f(H).sub(new Vector3f(dir).mul(0.4f));
            for (int f = 0; f < 4; f++) {
                float sp = (f - 1.5f) * 0.2f * (1f - 0.55f * grip);
                Vector3f base = new Vector3f(palm).add(new Vector3f(side).mul(sp)).add(new Vector3f(up).mul(f == 0 || f == 3 ? -0.03f : 0.02f));
                Vector3f ctrl = new Vector3f(base).add(new Vector3f(dir).mul(0.45f)).add(new Vector3f(up).mul(0.25f)).add(new Vector3f(side).mul(sp * 0.6f));
                Vector3f tip = new Vector3f(base).add(new Vector3f(dir).mul(0.6f - 0.25f * grip)).add(new Vector3f(up).mul(-0.05f - 0.3f * grip))
                        .add(new Vector3f(side).mul(sp * 0.4f));
                int n = 6;
                Vector3f[] pts = new Vector3f[n];
                float[] wg = new float[n], wt = new float[n];
                int[] cg = new int[n], ct = new int[n];
                for (int i = 0; i < n; i++) {
                    float u = i / (float) (n - 1);
                    pts[i] = bezier(base, ctrl, tip, u);
                    wt[i] = Mth.lerp(u, 0.05f, 0.006f);
                    wg[i] = wt[i] * 3f + 0.03f;
                    ct[i] = c.c(mix(bone, blight ? 0xC9B3F0 : col, u * 0.6f), ca);
                    cg[i] = c.c(u > 0.6f ? WHITE : col, 0.5f * ca);
                }
                rib(g, c.m(), pts, wg, cg, c.cam);
                rib(vc, c.m(), pts, wt, ct, c.cam);
            }
            c.bb(palm);
            Matrix4f m = c.m();
            disc(g, m, 0, 0, 0.35f, c.c(dark, 0.5f * ca), c.c(col, 0f), 16);
            disc(vc, m, 0, 0, 0.1f, c.c(bone, 0.9f * ca), c.c(col, 0.6f * ca), 12);
            c.pop();
        }
        // 命中：三道斜向爪痕 + 冰晶迸散 + 霜环
        if (t >= T) {
            float k = t - T, burst = clamp01(1f - k / 7f);
            c.bb(E);
            Matrix4f m = c.m();
            for (int i = 0; i < 3; i++) {
                float st = clamp01((k - i * 0.7f) / 2f);
                if (st <= 0) continue;
                float fa = clamp01(1f - (k - 3f - i * 0.5f) / 6f) * A;
                float off = (i - 1) * 0.28f;
                float x0 = -0.55f + off, y0 = 0.75f, x1 = 0.45f + off, y1 = -0.75f;
                float xe = Mth.lerp(easeOut(st), x0, x1), ye = Mth.lerp(easeOut(st), y0, y1);
                needle(g, m, x0, y0, xe, ye, 0.11f, c.c(col, 0.7f * fa), c.c(dark, 0f));
                needle(vc, m, x0, y0, xe, ye, 0.035f, c.c(WHITE, fa), c.c(bone, 0.4f * fa));
                if (blight) needle(g, m, x0 + 0.05f, y0, xe + 0.05f, ye, 0.06f, c.c(dark, 0.6f * fa), c.c(dark, 0f));
            }
            disc(g, m, 0, 0, 0.5f + 0.6f * burst, c.c(WHITE, 0.6f * burst * A), c.c(col, 0f), 20);
            starburst(g, m, 10, 0.05f, 1.1f * easeOut(clamp01(k / 3f)), 0.3f, c.e.getId(), c.c(WHITE, 0.8f * burst * A), c.c(col, 0f));
            c.pop();
            for (int i = 0; i < 8; i++) {
                Vector3f d = new Vector3f(hash(i * 5 + 1) - 0.5f, hash(i * 7 + 2) - 0.3f, hash(i * 11 + 3) - 0.5f);
                if (d.lengthSquared() < 1e-4f) d.set(0, 1, 0);
                d.normalize();
                Vector3f a0 = new Vector3f(E).add(new Vector3f(d).mul(0.15f + 0.6f * easeOut(clamp01(k / 6f))));
                Vector3f b0 = new Vector3f(E).add(new Vector3f(d).mul(0.4f + 1.4f * easeOut(clamp01(k / 6f))));
                ribSpindle(vc, c.m(), a0, b0, 0.05f, 0.3f, c.c(bone, 0.2f * burst * A), c.c(mix(bone, col, 0.4f), burst * A), c.cam);
            }
            float r = 0.3f + 1.6f * easeOut(clamp01(k / 8f));
            c.plane(E.x, E.y, E.z, dir.x, dir.y, dir.z);
            ring(g, c.m(), r - 0.15f, r, c.c(col, 0f), c.c(WHITE, 0.6f * burst * A), 40);
            c.pop();
            if (blight) {
                for (int i = 0; i < 6; i++) {
                    float ph = (k * 0.06f + hash(i * 17)) % 1f;
                    Vector3f q = new Vector3f(E).add((hash(i * 3) - 0.5f) * 0.9f, ph * 1.2f, (hash(i * 9) - 0.5f) * 0.9f);
                    c.bb(q);
                    disc(g, c.m(), 0, 0, 0.16f * (1f - ph), c.c(dark, 0.7f * (1f - ph) * A), c.c(dark, 0f), 10);
                    c.pop();
                }
            }
        }
    }
}

package com.zhushen.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhushen.space.network.FxEventPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 技艺几何特效渲染（纯客户端，<b>不使用粒子</b>）。
 * <p>
 * 服务端只广播一次 {@link FxEventPayload}，客户端在本地逐帧推进：
 * <ul>
 *   <li>BEAM：位置沿起点→终点插值，形成真正「飞过去」的弹体，命中瞬间胀开</li>
 *   <li>SLASH：以目标为中心的月牙刃面，快速拉出后收束</li>
 *   <li>FORMATION：地面阵纹（外环 + 同心内环 + 放射阵线），缓慢旋转</li>
 *   <li>CONE：由顶点向外张开的锥形冲击，末端带火舌截面</li>
 *   <li>ARC：折线电弧，每 2 tick 重新分叉一次</li>
 *   <li>ORB：蓄能球，聚拢→稳定呼吸</li>
 *   <li>IMPACT：命中点十字闪 + 正交双环，单帧极亮</li>
 * </ul>
 * 渲染在 {@code RenderLevelStageEvent.AFTER_PARTICLES}，使用 {@link RenderType#lightning()}
 * 的 additive 混合并保留深度测试，让方块正常遮挡特效。光影兼容性需游戏内确认。
 */
@EventBusSubscriber(modid = com.zhushen.space.ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientArtFx {

    private ClientArtFx() {
    }

    private static final class Fx {
        final FxEventPayload e;
        int age;
        Fx(FxEventPayload e) { this.e = e; }
    }
    private static final List<Fx> ACTIVE = new ArrayList<>();
    private static net.minecraft.client.multiplayer.ClientLevel world;

    private static void updateWorld(Minecraft mc) {
        if (world != mc.level) { ACTIVE.clear(); world = mc.level; }
    }

    /** 网络包入口 */
    public static void handle(FxEventPayload e) {
        Minecraft mc = Minecraft.getInstance();
        updateWorld(mc);
        if (mc.level == null) return;
        // 记录相机相对偏移：特效跟着世界走，但相机移动时由 render 重新计算
        ACTIVE.add(new Fx(e));
        if (ACTIVE.size() > 256) ACTIVE.remove(0);
    }

    /** 世界卸载 / 切维度时清空 */
    public static void clear() {
        ACTIVE.clear();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        updateWorld(mc);
        if (mc.isPaused()) return;
        if (mc.level == null) {
            ACTIVE.clear();
            return;
        }
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Fx f = ACTIVE.get(i);
            if (++f.age > f.e.life()) ACTIVE.remove(i);
        }
        // 生物闪电：附着在实体上的持续电弧由服务端按 tick 重发，这里只做一次性的
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        updateWorld(mc);
        if (mc.level == null) return;

        if (ACTIVE.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        VertexConsumer vc = mc.renderBuffers().bufferSource().getBuffer(RenderType.lightning());
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = pose.last().pose();

        for (Fx f : ACTIVE) {
            float p = Mth.clamp((f.age + partial) / Math.max(1, f.e.life()), 0f, 1f);
            Vec3 a = new Vec3(f.e.ax(), f.e.ay(), f.e.az());
            Vec3 b = new Vec3(f.e.bx(), f.e.by(), f.e.bz());
            int color = f.e.color();
            float power = f.e.power();
            switch (f.e.kind()) {
                case FxEventPayload.BEAM -> beam(m, vc, a, b, color, power, p);
                case FxEventPayload.ORB -> orb(m, vc, a, b, color, power, p);
                case FxEventPayload.SLASH -> slash(m, vc, a, b, color, power, p);
                case FxEventPayload.IMPACT -> impact(m, vc, a, b, color, power, p);
                case FxEventPayload.FORMATION -> formation(m, vc, a, b, color, power, p);
                case FxEventPayload.CONE -> cone(m, vc, a, b, color, power, p);
                case FxEventPayload.ARC -> arc(m, vc, a, b, color, power, f.age, f.e.seed());
                case FxEventPayload.FLASH -> flash(m, vc, a, color, power, p);
                default -> {
                }
            }
        }
        pose.popPose();
        mc.renderBuffers().bufferSource().endBatch(RenderType.lightning());
    }

    // ===== 各形状绘制 =====

    /** 弹体：头部在起点→终点之间插值推进，尾迹回拉；命中末段（p>0.85）胀开 */
    private static void beam(Matrix4f m, VertexConsumer vc, Vec3 a, Vec3 b, int color, float width, float p) {
        float travel = Mth.clamp(p / 0.85f, 0f, 1f);
        Vec3 head = a.lerp(b, ease(travel));
        Vec3 dir = b.subtract(a);
        if (dir.lengthSqr() < 1e-6) return;
        dir = dir.normalize();
        float burst = p > 0.85f ? (p - 0.85f) / 0.15f : 0f;
        float r = width * (1f + burst * 2.2f);
        int c = fade(color, 1f - burst * 0.6f);

        // 尾迹：沿路径回拉 6 段，逐段收细
        Vec3 tail = head.subtract(dir.scale(width * 12f * (1f - burst)));
        for (int i = 0; i < 6; i++) {
            float t0 = i / 6f, t1 = (i + 1) / 6f;
            float r0 = r * (1f + t0 * 1.4f) * (1f - t0 * 0.45f);
            float r1 = r * (1f + t1 * 1.4f) * (1f - t1 * 0.45f);
            Vec3 p0 = tail.lerp(head, t0), p1 = tail.lerp(head, t1);
            ribbon(m, vc, p0, p1, dir, r0, r1, fade(c, 1f - t1 * 0.75f));
        }
        // 弹体本体：八边形截面，快速自转
        billboard(m, vc, head, dir, r * 1.5f, c);
        billboard(m, vc, head.add(dir.scale(r * 0.6f)), dir, r * 0.7f, fade(0xFFFFFFFF, ((c >>> 24) & 255) / 255f * 0.4f));
    }

    /** 蓄能球：半径从小到大，末段呼吸 */
    private static void orb(Matrix4f m, VertexConsumer vc, Vec3 a, Vec3 b, int color, float radius, float p) {
        Vec3 dir = b.subtract(a);
        if (dir.lengthSqr() < 1e-6) return;
        dir = dir.normalize();
        float r = radius * ease(p) * (1f + 0.12f * Mth.sin(p * 24f));
        int c = fade(color, 0.55f + 0.45f * p);
        billboard(m, vc, a, dir, r * 1.4f, c);
        billboard(m, vc, a, dir, r * 0.7f, fade(0xFFFFFFFF, ((c >>> 24) & 255) / 255f * 0.73f));
        // 聚拢环：球体外两圈随时间收拢
        for (int i = 0; i < 2; i++) {
            float k = 1f - p;
            ring(m, vc, a, dir, r * (1.6f + i * 0.9f) * k, 0.02f * r, 20, fade(c, 0.5f * k));
        }
    }

    /** 刀光：以命中点为心的月牙刃面，沿攻击方向展开 */
    private static void slash(Matrix4f m, VertexConsumer vc, Vec3 c, Vec3 facing, int color, float radius, float p) {
        Vec3 f = facing.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : facing.normalize();
        Vec3 up = new Vec3(0, 1, 0);
        if (Math.abs(f.dot(up)) > 0.95) up = new Vec3(1, 0, 0);
        Vec3 u = f.cross(up).normalize();      // 侧向
        Vec3 v = f.cross(u).normalize();       // 上下
        // 月牙：以 f 为轴，绕 u-v 平面画一段弧，弧口朝前后收窄
        int seg = 16;
        float span = 2.35f;                     // 弧度跨度（约 135°）
        float grow = ease(p) * radius * 1.6f;
        for (int i = 0; i < seg; i++) {
            float t0 = i / (float) seg, t1 = (i + 1) / (float) seg;
            float a0 = -span / 2f + span * t0, a1 = -span / 2f + span * t1;
            Vec3 d0 = u.scale(Mth.cos(a0)).add(v.scale(Mth.sin(a0)));
            Vec3 d1 = u.scale(Mth.cos(a1)).add(v.scale(Mth.sin(a1)));
            float w0 = blade(t0) * grow, w1 = blade(t1) * grow;
            float o = radius * 0.25f + grow * 0.55f;
            quad(m, vc,
                    c.add(d0.scale(o)).add(f.scale(-w0)), c.add(d0.scale(o)).add(f.scale(w0)),
                    c.add(d1.scale(o)).add(f.scale(w1)), c.add(d1.scale(o)).add(f.scale(-w1)),
                    fade(color, (1f - p) * 0.95f));
        }
        // 中心亮点
        billboard(m, vc, c, f, radius * 0.5f * (1f - p * 0.5f), fade(color, (1f - p) * 0.6f));
    }

    private static float blade(float t) {
        // 中段最宽，两端收尖
        return 0.35f + 0.65f * Mth.sin((float) Math.PI * t);
    }

    /** 命中爆点：十字闪 + 正交双环，8 tick 内迅速衰减 */
    private static void impact(Matrix4f m, VertexConsumer vc, Vec3 c, Vec3 toward, int color, float power, float p) {
        float k = 1f - p;
        float r = power * (0.6f + ease(p) * 1.6f);
        int core = fade(color, k * k);
        // 十字闪（三轴细带）
        for (int axis = 0; axis < 3; axis++) {
            Vec3 d = switch (axis) {
                case 0 -> new Vec3(1, 0, 0);
                case 1 -> new Vec3(0, 1, 0);
                default -> new Vec3(0, 0, 1);
            };
            ribbon(m, vc, c.subtract(d.scale(r)), c.add(d.scale(r)), d, r * 0.10f, r * 0.10f, core);
        }
        // 双环
        Vec3 f = toward.subtract(c);
        f = f.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : f.normalize();
        ring(m, vc, c, f, r * 1.5f, r * 0.08f, 28, fade(color, k * 0.8f));
        ring(m, vc, c, f.cross(new Vec3(0, 1, 0)).normalize(), r * 1.1f, r * 0.06f, 24, fade(color, k * 0.6f));
        billboard(m, vc, c, f, r * 0.9f, fade(0xFFFFFFFF, k * k * 0.8f));
    }

    /** 地面阵纹：外环 + 同心内环 + 放射阵线 + 顶点八方，缓慢旋转 */
    private static void formation(Matrix4f m, VertexConsumer vc, Vec3 center, Vec3 facing, int color, float radius, float p) {
        Vec3 f = facing;
        f = f.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : f.normalize();
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 u = new Vec3(f.x, 0, f.z);
        u = u.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : u.normalize();
        Vec3 v = up.cross(u).normalize();
        f = u;
        Vec3 c = center;
        float grow = ease(Math.min(1f, p * 3f));            // 快速展开
        float fadeOut = p > 0.75f ? 1f - (p - 0.75f) / 0.25f : 1f;
        float spin = p * 0.6f;

        // 三层环
        ring(m, vc, c.add(0, 0.03, 0), up, radius * grow, radius * 0.045f, 48, fade(color, 0.95f * fadeOut));
        ring(m, vc, c.add(0, 0.04, 0), up, radius * 0.62f * grow, radius * 0.03f, 40, fade(color, 0.75f * fadeOut));
        ring(m, vc, c.add(0, 0.05, 0), up, radius * 0.3f * grow, radius * 0.025f, 32, fade(color, 0.6f * fadeOut));
        // 放射阵线 8 条
        for (int i = 0; i < 8; i++) {
            double ang = spin + Math.PI * 2 * i / 8;
            Vec3 d = u.scale(Math.cos(ang)).add(v.scale(Math.sin(ang)));
            ribbon(m, vc, c.add(0, 0.045, 0).add(d.scale(radius * 0.3f * grow)),
                    c.add(0, 0.045, 0).add(d.scale(radius * grow)), d,
                    radius * 0.02f, radius * 0.008f, fade(color, 0.7f * fadeOut));
        }
        // 顶点（面向敌人方向）八方符：四条短笔画
        for (int i = 0; i < 4; i++) {
            double ang = spin * 1.5 + Math.PI / 2 * i + Math.PI / 4;
            Vec3 d = u.scale(Math.cos(ang)).add(v.scale(Math.sin(ang)));
            Vec3 o = c.add(0, 0.06, 0).add(f.scale(radius * 0.78f * grow));
            ribbon(m, vc, o.subtract(d.scale(radius * 0.09f)), o.add(d.scale(radius * 0.09f)), d,
                    radius * 0.02f, radius * 0.02f, fade(0xFFFFFFFF, 0.85f * fadeOut));
        }
    }

    /** 锥形冲击：顶点向外张开，末端火舌截面 */
    private static void cone(Matrix4f m, VertexConsumer vc, Vec3 apex, Vec3 facing, int color, float length, float p) {
        Vec3 f = facing.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : facing.normalize();
        Vec3 up = new Vec3(0, 1, 0);
        if (Math.abs(f.dot(up)) > 0.95) up = new Vec3(1, 0, 0);
        Vec3 u = f.cross(up).normalize();
        Vec3 v = f.cross(u).normalize();
        float reach = ease(Math.min(1f, p * 1.35f)) * length;
        float fadeOut = p > 0.7f ? 1f - (p - 0.7f) / 0.3f : 1f;
        int seg = 20;
        for (int i = 0; i < seg; i++) {
            float t0 = i / (float) seg, t1 = (i + 1) / (float) seg;
            float a0 = (float) (Math.PI * 2 * t0), a1 = (float) (Math.PI * 2 * t1);
            // 末端截面环（火舌）
            float rEnd = reach * 0.42f;
            Vec3 endC = apex.add(f.scale(reach));
            Vec3 d0 = u.scale(Mth.cos(a0)).add(v.scale(Mth.sin(a0)));
            Vec3 d1 = u.scale(Mth.cos(a1)).add(v.scale(Mth.sin(a1)));
            quad(m, vc, endC.add(d0.scale(rEnd)), endC.add(d1.scale(rEnd)),
                    apex.add(d1.scale(rEnd * 0.06f)), apex.add(d0.scale(rEnd * 0.06f)),
                    fade(color, 0.55f * fadeOut));
            // 火舌前沿
            ribbon(m, vc, endC.add(d0.scale(rEnd * 1.15f)), endC.add(d1.scale(rEnd * 1.15f)), d0.cross(f),
                    length * 0.05f, length * 0.03f, fade(0xFFFFD9A0, 0.85f * fadeOut));
        }
        // 中轴炽核
        ribbon(m, vc, apex, apex.add(f.scale(reach)), f, length * 0.07f, length * 0.12f, fade(0xFFFFFFFF, 0.7f * fadeOut));
    }

    /** 折线电弧：每 2 tick 重新分叉 */
    private static void arc(Matrix4f m, VertexConsumer vc, Vec3 a, Vec3 b, int color, float power, int age, int eventSeed) {
        long seed = eventSeed + (age / 2);
        java.util.Random rnd = new java.util.Random(seed * 0x9E3779B9L);
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 0.01) return;
        Vec3 dir = d.normalize();
        Vec3 up = Math.abs(dir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = dir.cross(up).normalize();
        Vec3 v = dir.cross(u).normalize();
        int seg = Math.max(4, (int) (len * 1.6));
        Vec3 prev = a;
        float jitter = power * 0.35f;
        for (int i = 1; i <= seg; i++) {
            float t = i / (float) seg;
            Vec3 pt = a.lerp(b, t);
            if (i < seg) {
                double j = jitter * (1 - Math.abs(t - 0.5) * 0.6);
                pt = pt.add(u.scale((rnd.nextDouble() - 0.5) * j)).add(v.scale((rnd.nextDouble() - 0.5) * j));
            }
            ribbon(m, vc, prev, pt, dir, power * 0.06f, power * 0.05f, fade(color, 0.95f - t * 0.35f));
            // 分叉
            if (i > 1 && i < seg - 1 && rnd.nextInt(3) == 0) {
                Vec3 bt = pt.add(u.scale((rnd.nextDouble() - 0.5) * jitter * 2.2))
                        .add(v.scale((rnd.nextDouble() - 0.5) * jitter * 2.2))
                        .add(dir.scale(len * 0.12f));
                ribbon(m, vc, pt, bt, dir, power * 0.035f, power * 0.01f, fade(color, 0.6f));
            }
            prev = pt;
        }
        billboard(m, vc, a, dir, power * 0.35f, fade(color, 0.6f));
    }

    /** 单帧闪光（起手、破隐等） */
    private static void flash(Matrix4f m, VertexConsumer vc, Vec3 c, int color, float radius, float p) {
        float k = 1f - p;
        billboard(m, vc, c, new Vec3(0, 1, 0), radius * (1f + p), fade(color, k * 0.8f));
        ring(m, vc, c, new Vec3(0, 1, 0), radius * (1f + p * 2f), radius * 0.05f, 24, fade(color, k * 0.5f));
    }

    // ===== 基础几何工具 =====

    /** 面向相机的多边形（8 边，近似圆盘），用于球体 / 光点 */
    private static void billboard(Matrix4f m, VertexConsumer vc, Vec3 c, Vec3 dir, float r, int color) {
        Vec3 f = dir.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : dir.normalize();
        Vec3 up = Math.abs(f.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = f.cross(up).normalize();
        Vec3 v = f.cross(u).normalize();
        int n = 8;
        for (int i = 0; i < n; i++) {
            float a0 = (float) (Math.PI * 2 * i / n), a1 = (float) (Math.PI * 2 * (i + 1) / n);
            Vec3 p0 = c.add(u.scale(Mth.cos(a0) * r)).add(v.scale(Mth.sin(a0) * r));
            Vec3 p1 = c.add(u.scale(Mth.cos(a1) * r)).add(v.scale(Mth.sin(a1) * r));
            tri(m, vc, c, p0, p1, color);
        }
    }

    /** 环形带（绕 axis 轴的圆环） */
    private static void ring(Matrix4f m, VertexConsumer vc, Vec3 c, Vec3 axis, float radius, float thickness,
                             int seg, int color) {
        Vec3 n = axis.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : axis.normalize();
        Vec3 up = Math.abs(n.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = n.cross(up).normalize();
        Vec3 v = n.cross(u).normalize();
        for (int i = 0; i < seg; i++) {
            float a0 = (float) (Math.PI * 2 * i / seg), a1 = (float) (Math.PI * 2 * (i + 1) / seg);
            Vec3 d0 = u.scale(Mth.cos(a0)).add(v.scale(Mth.sin(a0)));
            Vec3 d1 = u.scale(Mth.cos(a1)).add(v.scale(Mth.sin(a1)));
            quad(m, vc,
                    c.add(d0.scale(radius - thickness)), c.add(d0.scale(radius + thickness)),
                    c.add(d1.scale(radius + thickness)), c.add(d1.scale(radius - thickness)), color);
        }
    }

    /** 沿 path 的带状面（用于尾迹 / 电弧 / 阵线），截面朝向 side */
    private static void ribbon(Matrix4f m, VertexConsumer vc, Vec3 p0, Vec3 p1, Vec3 side, float r0, float r1, int color) {
        Vec3 d = p1.subtract(p0);
        if (d.lengthSqr() < 1e-9) return;
        d = d.normalize();
        Vec3 s = side.subtract(d.scale(side.dot(d)));
        if (s.lengthSqr() < 1e-9) s = d.cross(new Vec3(0, 1, 0));
        if (s.lengthSqr() < 1e-9) s = new Vec3(1, 0, 0);
        s = s.normalize();
        Vec3 n = d.cross(s).normalize();
        quad(m, vc, p0.add(n.scale(r0)), p0.subtract(n.scale(r0)),
                p1.subtract(n.scale(r1)), p1.add(n.scale(r1)), color);
        quad(m, vc, p0.add(s.scale(r0)), p0.subtract(s.scale(r0)),
                p1.subtract(s.scale(r1)), p1.add(s.scale(r1)), color);
    }

    private static void quad(Matrix4f m, VertexConsumer vc, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
        vc.addVertex(m, (float) a.x, (float) a.y, (float) a.z).setColor(color);
        vc.addVertex(m, (float) b.x, (float) b.y, (float) b.z).setColor(color);
        vc.addVertex(m, (float) c.x, (float) c.y, (float) c.z).setColor(color);
        vc.addVertex(m, (float) d.x, (float) d.y, (float) d.z).setColor(color);
    }

    private static void tri(Matrix4f m, VertexConsumer vc, Vec3 a, Vec3 b, Vec3 c, int color) {
        // lightning uses QUADS: duplicate the last vertex for a degenerate quad.
        quad(m, vc, a, b, c, c, color);
    }

    private static int fade(int argb, float k) {
        int a = (int) Mth.clamp(((argb >>> 24) & 255) * k, 0, 255);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static float ease(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return 1f - (1f - t) * (1f - t) * (1f - t); // easeOutCubic
    }

}

package com.zhushen.space.screen;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 专长页边框内的术式小游戏（纯客户端视觉，无任何判定 / 奖励）。
 * <ul>
 *   <li>左半（无量空处侧）：按住左键 → 「苍」（吸引：蓝白光球随鼠标，光粒被吸入）；
 *       按住右键蓄力 → 「赫」（排斥：红光球，松开朝鼠标方向射出，未拖动则射向「苍」）；
 *       「赫」撞上「苍」 → 「茈」（虚式：紫色巨大球体沿赫的方向推进，白闪 + 冲击环）。</li>
 *   <li>右半（伏魔御厨子侧）：左键 → 「解」单道斩击；右键 → 「捌」连斩（按住持续）。</li>
 * </ul>
 */
public final class JjkDomainGame {
    private static final int BLUE = 0xFF3FA8FF, BLUE_CORE = 0xFFE8F6FF;
    private static final int RED = 0xFFFF2A3A, RED_CORE = 0xFFFFE0E0;
    private static final int PURPLE = 0xFF9A3CFF, PURPLE_CORE = 0xFFF4E8FF;

    private int x0, y0, x1, y1, mid;

    // —— 苍 ——
    private boolean blueHeld;
    private long blueStart, blueReleased = -1;
    private float bx, by;
    // —— 赫 ——
    private boolean redCharging;
    private long redStart;
    private float rcx, rcy;
    private final List<float[]> reds = new ArrayList<>();      // x,y,vx,vy,r,born
    // —— 茈 ——
    private final List<float[]> purples = new ArrayList<>();   // x,y,vx,vy,born
    private long flashAt = -1;
    // —— 斩击 ——
    private final List<float[]> slashes = new ArrayList<>();   // cx,cy,ang,len,born,width
    private boolean cleaveHeld;
    private long nextCleave;
    private float mx, my;
    // —— 粒子 ——
    private final List<float[]> parts = new ArrayList<>();     // x,y,vx,vy,born,life,col

    private long last = -1;

    public void setBounds(int x0, int y0, int x1, int y1) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        this.mid = (x0 + x1) / 2;
    }

    public boolean inside(double x, double y) {
        return x >= x0 && x < x1 && y >= y0 && y < y1;
    }

    public void reset() {
        blueHeld = redCharging = cleaveHeld = false;
        blueReleased = -1;
        reds.clear(); purples.clear(); slashes.clear(); parts.clear();
    }

    public boolean press(double x, double y, int button) {
        if (!inside(x, y) || (button != 0 && button != 1)) return false;
        long now = ZsAnim.nowMs();
        mx = (float) x; my = (float) y;
        if (x < mid) {
            if (button == 0) {
                blueHeld = true; blueStart = now; blueReleased = -1;
                bx = mx; by = my;
                ZsTheme.click(0.6f);
            } else {
                redCharging = true; redStart = now; rcx = mx; rcy = my;
                ZsTheme.click(1.6f);
            }
        } else {
            if (button == 0) {
                slash(mx, my, 40 + rnd() * 40, 2);
                ZsTheme.click(1.9f);
            } else {
                cleaveHeld = true; nextCleave = now;
            }
        }
        return true;
    }

    public void release(double x, double y, int button) {
        long now = ZsAnim.nowMs();
        if (button == 0 && blueHeld) {
            blueHeld = false;
            blueReleased = now;
        }
        if (button == 1 && redCharging) {
            redCharging = false;
            float c = Math.min(1f, (now - redStart) / 500f);
            float dx = (float) x - rcx, dy = (float) y - rcy;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 8) {
                if (blueAlive(now)) { dx = bx - rcx; dy = by - rcy; }
                else { dx = -1; dy = 0; }
                len = Math.max(0.001f, (float) Math.sqrt(dx * dx + dy * dy));
            }
            float sp = 0.35f + 0.25f * c;
            reds.add(new float[]{rcx, rcy, dx / len * sp, dy / len * sp, 4 + 4 * c, now});
            for (int i = 0; i < 14; i++) spark(rcx, rcy, 0.15f, 350, RED);
            ZsTheme.click(0.5f);
        }
        if (button == 1) cleaveHeld = false;
    }

    private boolean blueAlive(long now) {
        return blueHeld || (blueReleased >= 0 && now - blueReleased < 1400);
    }

    private float blueAlpha(long now) {
        return blueHeld ? 1f : blueReleased < 0 ? 0f : 1f - ZsAnim.clamp01((now - blueReleased) / 1400f);
    }

    private float blueRadius(long now) {
        return 4 + 6 * Math.min(1f, (now - blueStart) / 600f);
    }

    private static float rnd() {
        return (float) Math.random();
    }

    private void slash(float cx, float cy, float len, float w) {
        float ang = (float) (rnd() * Math.PI);
        slashes.add(new float[]{cx, cy, ang, len, ZsAnim.nowMs(), w});
        for (int i = 0; i < 5; i++) spark(cx, cy, 0.12f, 300, i % 2 == 0 ? 0xFFFFFFFF : RED);
    }

    private void spark(float x, float y, float speed, float life, int col) {
        double a = rnd() * Math.PI * 2;
        float s = speed * (0.4f + rnd());
        parts.add(new float[]{x, y, (float) Math.cos(a) * s, (float) Math.sin(a) * s, ZsAnim.nowMs(), life, col});
    }

    // ===================== 更新 + 绘制 =====================

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        long now = ZsAnim.nowMs();
        float dt = last < 0 ? 16 : Math.min(50, now - last);
        last = now;
        mx = mouseX; my = mouseY;

        g.enableScissor(x0, y0, x1, y1);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);

        // 苍：跟随鼠标，吸入光粒
        if (blueHeld) {
            bx += (mx - bx) * Math.min(1f, dt / 60f);
            by += (my - by) * Math.min(1f, dt / 60f);
            if (rnd() < 0.8f) {
                double a = rnd() * Math.PI * 2;
                float r = 26 + rnd() * 20;
                float px = bx + (float) Math.cos(a) * r, py = by + (float) Math.sin(a) * r;
                parts.add(new float[]{px, py, (bx - px) / 380f, (by - py) / 380f, now, 380, rnd() < 0.5f ? BLUE : BLUE_CORE});
            }
        }
        // 赫蓄力
        if (redCharging) {
            float c = Math.min(1f, (now - redStart) / 500f);
            if (rnd() < 0.6f) spark(rcx, rcy, 0.05f + 0.1f * c, 260, RED);
        }
        // 捌：按住持续连斩
        if (cleaveHeld && now >= nextCleave) {
            nextCleave = now + 45;
            float a = (float) (rnd() * Math.PI * 2), r = rnd() * 34;
            slash(mx + (float) Math.cos(a) * r, my + (float) Math.sin(a) * r, 22 + rnd() * 40, 1 + (rnd() < 0.3f ? 1 : 0));
            if (rnd() < 0.35f) ZsTheme.click(1.7f + rnd() * 0.3f);
        }

        // 赫飞行 + 与苍碰撞 → 茈
        for (Iterator<float[]> it = reds.iterator(); it.hasNext(); ) {
            float[] r = it.next();
            r[0] += r[2] * dt; r[1] += r[3] * dt;
            if (rnd() < 0.7f) parts.add(new float[]{r[0], r[1], -r[2] * 0.2f + (rnd() - 0.5f) * 0.05f,
                    -r[3] * 0.2f + (rnd() - 0.5f) * 0.05f, now, 300, rnd() < 0.5f ? RED : 0xFFFF8A60});
            if (blueAlive(now)) {
                float dx = r[0] - bx, dy = r[1] - by, br = blueRadius(now);
                if (dx * dx + dy * dy < (r[4] + br + 4) * (r[4] + br + 4)) {
                    float sp = (float) Math.sqrt(r[2] * r[2] + r[3] * r[3]);
                    float k = 0.28f / Math.max(0.001f, sp);
                    purples.add(new float[]{(r[0] + bx) / 2, (r[1] + by) / 2, r[2] * k, r[3] * k, now});
                    flashAt = now;
                    blueHeld = false; blueReleased = now - 1400;
                    for (int i = 0; i < 40; i++) spark(bx, by, 0.3f, 600, i % 3 == 0 ? 0xFFFFFFFF : PURPLE);
                    ZsTheme.click(0.4f);
                    it.remove();
                    continue;
                }
            }
            if (r[0] < x0 - 20 || r[0] > x1 + 20 || r[1] < y0 - 20 || r[1] > y1 + 20 || now - r[5] > 4000) it.remove();
        }

        // —— 粒子 ——
        for (Iterator<float[]> it = parts.iterator(); it.hasNext(); ) {
            float[] p = it.next();
            float ph = (now - p[4]) / p[5];
            if (ph >= 1) { it.remove(); continue; }
            p[0] += p[2] * dt; p[1] += p[3] * dt;
            int c = JjkStyle.alpha((int) p[6] | 0xFF000000, 1 - ph);
            g.fill((int) p[0], (int) p[1], (int) p[0] + 1, (int) p[1] + 1, c);
        }

        // —— 斩击：一瞬划开（60ms）→ 白芯红晕 → 留下暗色切口渐隐 ——
        for (Iterator<float[]> it = slashes.iterator(); it.hasNext(); ) {
            float[] s = it.next();
            float age = now - s[4];
            if (age > 900) { it.remove(); continue; }
            float gr = Math.min(1f, age / 60f);
            float hl = s[3] / 2 * gr;
            float cx = (float) Math.cos(s[2]), sy = (float) Math.sin(s[2]);
            float ax = s[0] - cx * hl, ay = s[1] - sy * hl, ex = s[0] + cx * hl, ey = s[1] + sy * hl;
            if (age < 320) {
                float a = 1 - age / 320f;
                JjkStyle.line(g, ax, ay, ex, ey, s[5] + 5, JjkStyle.alpha(RED, a * 0.35f));
                JjkStyle.line(g, ax, ay, ex, ey, s[5] + 2, JjkStyle.alpha(0xFFFF6070, a * 0.7f));
                JjkStyle.line(g, ax, ay, ex, ey, s[5], JjkStyle.alpha(0xFFFFFFFF, a));
                // 切开的空间两侧错位（上下各一条细亮边）
                float nx = -sy * 2, ny = cx * 2;
                JjkStyle.line(g, ax + nx, ay + ny, ex + nx, ey + ny, 1, JjkStyle.alpha(0xFFFFB0B8, a * 0.5f));
                JjkStyle.line(g, ax - nx, ay - ny, ex - nx, ey - ny, 1, JjkStyle.alpha(0xFFFFB0B8, a * 0.5f));
            } else {
                float a = 1 - (age - 320) / 580f;
                JjkStyle.line(g, ax, ay, ex, ey, 1, JjkStyle.alpha(0xFF3A0006, a * 0.9f));
            }
        }

        // —— 苍 ——
        float ba = blueAlpha(now);
        if (ba > 0.01f) {
            float br = blueRadius(now);
            float pul = 1 + 0.08f * (float) Math.sin(now / 60.0);
            glow(g, bx, by, br * 2.6f * pul, BLUE, ba * 0.18f);
            glow(g, bx, by, br * 1.6f * pul, BLUE, ba * 0.35f);
            JjkStyle.disk(g, bx, by, br * pul, 1, JjkStyle.alpha(BLUE, ba * 0.85f));
            JjkStyle.disk(g, bx, by, br * 0.55f, 1, JjkStyle.alpha(BLUE_CORE, ba));
            // 向内收缩的点环（引力）
            for (int i = 0; i < 3; i++) {
                float ph = ((now / 700f) + i / 3f) % 1f;
                float rr = br * (3.4f - 2.4f * ph);
                JjkStyle.ring(g, bx, by, rr, rr, now / 300f * (i % 2 == 0 ? 1 : -1), 28, JjkStyle.alpha(BLUE_CORE, ba * ph));
            }
        }
        // —— 赫蓄力 ——
        if (redCharging) {
            float c = Math.min(1f, (now - redStart) / 500f);
            float rr = 4 + 4 * c;
            glow(g, rcx, rcy, rr * 2.4f, RED, 0.25f);
            JjkStyle.disk(g, rcx, rcy, rr, 1, JjkStyle.alpha(RED, 0.9f));
            JjkStyle.disk(g, rcx, rcy, rr * 0.5f, 1, RED_CORE);
            // 向外扩张的脉冲环（斥力）
            for (int i = 0; i < 2; i++) {
                float ph = ((now / 400f) + i / 2f) % 1f;
                float r2 = rr * (1 + 2.2f * ph);
                JjkStyle.ring(g, rcx, rcy, r2, r2, 0, 24, JjkStyle.alpha(0xFFFF8080, 1 - ph));
            }
            // 瞄准线
            JjkStyle.line(g, rcx, rcy, mx, my, 1, JjkStyle.alpha(RED, 0.25f));
        }
        // —— 赫飞行 ——
        for (float[] r : reds) {
            glow(g, r[0], r[1], r[4] * 2.4f, RED, 0.25f);
            JjkStyle.disk(g, r[0], r[1], r[4], 1, JjkStyle.alpha(RED, 0.95f));
            JjkStyle.disk(g, r[0], r[1], r[4] * 0.5f, 1, RED_CORE);
        }
        // —— 茈：虚式，边推进边膨胀，抹消经过的一切 ——
        for (Iterator<float[]> it = purples.iterator(); it.hasNext(); ) {
            float[] p = it.next();
            float age = now - p[4];
            p[0] += p[2] * dt; p[1] += p[3] * dt;
            float rad = 30 * ZsAnim.easeOutCubic(Math.min(1f, age / 350f));
            if (p[0] < x0 - 80 || p[0] > x1 + 80 || p[1] < y0 - 80 || p[1] > y1 + 80) { it.remove(); continue; }
            // 抹消轨迹：沿来路留下黑色虚空带
            float sp = (float) Math.sqrt(p[2] * p[2] + p[3] * p[3]);
            float ux = p[2] / sp, uy = p[3] / sp;
            float tl = Math.min(age * sp, 400);
            JjkStyle.line(g, p[0] - ux * tl, p[1] - uy * tl, p[0], p[1], rad * 1.6f, 0x66000000);
            JjkStyle.line(g, p[0] - ux * tl, p[1] - uy * tl, p[0], p[1], 1, JjkStyle.alpha(PURPLE, 0.5f));
            glow(g, p[0], p[1], rad * 1.9f, PURPLE, 0.18f);
            glow(g, p[0], p[1], rad * 1.35f, 0xFFC070FF, 0.3f);
            JjkStyle.disk(g, p[0], p[1], rad, 1, JjkStyle.alpha(PURPLE, 0.9f));
            JjkStyle.disk(g, p[0], p[1], rad * 0.6f, 1, JjkStyle.alpha(PURPLE_CORE, 0.95f));
            // 表面电弧
            for (int i = 0; i < 6; i++) {
                double a = now / 90.0 + i * Math.PI / 3 + JjkStyle.hash(i, now / 70) * 0.8;
                float r1 = rad * 0.9f, r2 = rad * (1.2f + JjkStyle.hash(i + 7, now / 70) * 0.5f);
                JjkStyle.line(g, p[0] + (float) Math.cos(a) * r1, p[1] + (float) Math.sin(a) * r1,
                        p[0] + (float) Math.cos(a) * r2, p[1] + (float) Math.sin(a) * r2, 1, 0xFFE8D0FF);
            }
            if (rnd() < 0.9f) spark(p[0], p[1], 0.2f, 400, rnd() < 0.5f ? PURPLE : 0xFFFFFFFF);
        }
        // 茈生成瞬间：白闪 + 冲击环
        if (flashAt >= 0) {
            float age = now - flashAt;
            if (age < 180) g.fill(x0, y0, x1, y1, JjkStyle.alpha(0xFFFFFFFF, 0.55f * (1 - age / 180f)));
            if (age < 600 && !purples.isEmpty()) {
                float[] p = purples.get(purples.size() - 1);
                float r = 10 + age * 0.25f;
                JjkStyle.ring(g, p[0], p[1], r, r, 0, 60, JjkStyle.alpha(0xFFE8D0FF, 1 - age / 600f));
                JjkStyle.ring(g, p[0], p[1], r * 0.8f, r * 0.8f, 0.05f, 60, JjkStyle.alpha(PURPLE, 1 - age / 600f));
            }
        }

        g.pose().popPose();
        g.disableScissor();
    }

    private static void glow(GuiGraphics g, float x, float y, float r, int col, float a) {
        JjkStyle.disk(g, x, y, r, 1, JjkStyle.alpha(col, a));
    }
}

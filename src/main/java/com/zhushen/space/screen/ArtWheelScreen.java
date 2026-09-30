package com.zhushen.space.screen;

import com.zhushen.space.client.ClientArtData;
import com.zhushen.space.client.ClientSetup;
import com.zhushen.space.client.ClientWillpower;
import com.zhushen.space.data.ArtSkill;
import com.zhushen.space.common.PoolEffects;
import com.zhushen.space.network.ArtActionPayload;
import com.zhushen.space.network.WillpowerActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * 动作轮盘（默认 Z，按住显示、松开关闭）：收纳「主动使用但不占技能栏 / 按键」的效果。
 * 新增此类效果只需在 {@link #ENTRIES} 中注册一个 {@link Entry}。
 */
public class ArtWheelScreen extends Screen {

    /**
     * 轮盘条目：label 名称；value 状态文字；visible 是否显示；
     * click(button) 点击（0 左键 / 1 右键）；scroll(dir) 滚轮（可为 null）。
     */
    public record Entry(Supplier<String> label, Supplier<String> value, BooleanSupplier visible,
                        IntConsumer click, IntConsumer scroll) {}

    public static final List<Entry> ENTRIES = new ArrayList<>();

    private static String tr(String k) { return Component.translatable(k).getString(); }

    static {
        // 意志力：强撑（昏迷时）/ 意志加持（下一次检定 +9）
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.will"),
                () -> tr(ClientWillpower.armedCheck() ? "screen.zhushenspace.wheel.armed" : "screen.zhushenspace.wheel.ready"),
                () -> true, b -> PacketDistributor.sendToServer(new WillpowerActionPayload(0)), null));
        // 意志守御（下一次受击 +9 护甲 / 韧性）
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.guard"),
                () -> tr(ClientWillpower.armedGuard() ? "screen.zhushenspace.wheel.armed" : "screen.zhushenspace.wheel.ready"),
                () -> true, b -> PacketDistributor.sendToServer(new WillpowerActionPayload(1)), null));
        // ===== 能量池基础用法 =====
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.boost"),
                () -> tr(ClientArtData.flag(PoolEffects.F_BOOST) ? "screen.zhushenspace.wheel.on" : "screen.zhushenspace.wheel.off"),
                () -> anyPool(), b -> art(10, 0, 0), null));
        ENTRIES.add(poolEntry("screen.zhushenspace.wheel.sense", "magic", PoolEffects.F_SENSE, 11));
        ENTRIES.add(poolEntry("screen.zhushenspace.wheel.spider", "chakra", PoolEffects.F_SPIDER, 12));
        ENTRIES.add(poolEntry("screen.zhushenspace.wheel.water", "chakra", PoolEffects.F_WATER, 13));
        ENTRIES.add(poolEntry("screen.zhushenspace.wheel.sight", "dao", PoolEffects.F_SIGHT, 14));
        // 休息（所有人可用）：短休不限次数；长休每 24 小时一次（也可睡床）。休息中再次点击取消
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.rest"),
                () -> tr(ClientArtData.flag(PoolEffects.F_REST) ? "screen.zhushenspace.wheel.on" : "screen.zhushenspace.wheel.ready"),
                () -> true, b -> art(15, 0, 0), null));
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.long_rest"),
                () -> tr(ClientArtData.flag(PoolEffects.F_REST) ? "screen.zhushenspace.wheel.on" : "screen.zhushenspace.wheel.ready"),
                () -> true, b -> art(16, 0, 0), null));
        // 冥想：有能量池时可用，与短休 / 打坐互相独立（各自判定恢复）
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.meditate"),
                () -> tr(ClientArtData.flag(PoolEffects.F_MEDITATE) ? "screen.zhushenspace.wheel.on" : "screen.zhushenspace.wheel.ready"),
                () -> anyPool(), b -> art(17, 0, 0), null));
        // 卧倒 / 爬起来
        ENTRIES.add(new Entry(() -> tr(com.zhushen.space.client.ClientCondition.prone()
                        ? "screen.zhushenspace.wheel.stand" : "screen.zhushenspace.wheel.prone"),
                () -> tr(com.zhushen.space.client.ClientCondition.prone() ? "screen.zhushenspace.wheel.on" : "screen.zhushenspace.wheel.ready"),
                () -> true, b -> art(18, 0, 0), null));
        // 扑灭火焰（燃烧时显示）
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.extinguish"),
                () -> String.valueOf(com.zhushen.space.client.ClientCondition.points(com.zhushen.space.data.StatusType.BURN)),
                () -> com.zhushen.space.client.ClientCondition.points(com.zhushen.space.data.StatusType.BURN) > 0,
                b -> art(19, 0, 0), null));
        // 急救（止血 / 处理开放性创口）：对准星处触及范围内的目标，否则对自己
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.first_aid"),
                () -> String.valueOf(com.zhushen.space.client.ClientCondition.points(com.zhushen.space.data.StatusType.BLEED)),
                () -> true, b -> art(22, 0, 0), null));
        // 留手：点击开关，滚轮 ±5%
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.holdback"),
                () -> ClientArtData.holdback() < 0 ? tr("screen.zhushenspace.wheel.off") : ClientArtData.holdback() + "%",
                () -> true, b -> art(3, 0, ClientArtData.holdback() < 0 ? 50 : -1),
                d -> art(3, 0, Math.max(1, Math.min(100, (ClientArtData.holdback() < 0 ? 100 : ClientArtData.holdback()) + d * 5)))));
        // 增幅：关 / 用满
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.amplify"),
                () -> tr(ClientArtData.amplify() ? "screen.zhushenspace.wheel.full" : "screen.zhushenspace.wheel.off"),
                () -> true, b -> art(4, 0, ClientArtData.amplify() ? 0 : 1), null));
        // 八阵图元素（习得后显示）
        ArtSkill ef = ArtSkill.EIGHT_FORMATION;
        ENTRIES.add(new Entry(() -> tr(ef.ability.nameKey()),
                () -> tr(ef.optionKey(Math.min(ef.options.length - 1, ClientArtData.current(ef)))),
                () -> ClientArtData.owns(ef), b -> art(5, ef.ordinal(), b == 1 ? -1 : 1), d -> art(5, ef.ordinal(), d)));
    }

    private static boolean anyPool() {
        for (var p : com.zhushen.space.client.ClientEnergyData.pools())
            if (!p.id().equals("neili") && !p.id().equals("willpower")) return true;
        return false;
    }

    private static Entry poolEntry(String key, String pool, int flag, int action) {
        return new Entry(() -> tr(key),
                () -> tr(ClientArtData.flag(flag) ? "screen.zhushenspace.wheel.on" : "screen.zhushenspace.wheel.cost1"),
                () -> com.zhushen.space.client.ClientEnergyData.hasPool(pool), b -> art(action, 0, 0), null);
    }

    private static void art(int action, int art, int value) {
        PacketDistributor.sendToServer(new ArtActionPayload(action, art, value));
    }

    private final long openedAt = System.currentTimeMillis();
    /** 指针（撞针）当前角度，平滑跟随选中的弹膛 */
    private double needle = Double.NaN;
    private int lastHover = -1;

    public ArtWheelScreen() { super(Component.translatable("screen.zhushenspace.wheel")); }

    @Override
    public boolean isPauseScreen() { return false; }

    private List<Entry> visible() {
        List<Entry> l = new ArrayList<>();
        for (Entry e : ENTRIES) if (e.visible().getAsBoolean()) l.add(e);
        return l;
    }

    // ===== 左轮弹巢布局 =====
    // 弹巢（圆柱）外半径 R；弹膛（每个条目）沿半径 rc 均匀分布，弹膛半径 cr；中心是退壳星与选中条目的说明。

    /** 布局：{弹膛半径, 弹膛所在圆半径}；条目多或屏幕小时自动缩小弹膛 */
    private int[] layout(int n) {
        int m = Math.max(6, n);
        int max = Math.min(width, height) / 2 - 16; // 弹膛外缘不超出屏幕
        int cr = 24;
        int rc = (int) Math.ceil(m * (cr * 2 + 5) / (2 * Math.PI));
        if (rc + cr > max) {
            cr = Math.max(12, (int) ((max - 5.0 * m / (2 * Math.PI)) / (1 + m / Math.PI)));
            rc = (int) Math.ceil(m * (cr * 2 + 5) / (2 * Math.PI));
        }
        rc = Math.max(rc, cr + 28);
        return new int[]{cr, rc};
    }

    private int chamberR(int n) { return layout(n)[0]; }

    private int ringR(int n) { return layout(n)[1]; }

    private double angleOf(int i, int n) {
        return -Math.PI / 2 + i * (Math.PI * 2 / n);
    }

    /** 开场转轮动画的旋转偏移（像甩开弹巢后旋转、逐渐停下） */
    private double spin() {
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 420f);
        float e = 1f - (1f - t) * (1f - t) * (1f - t);
        return (1f - e) * Math.PI * 0.9;
    }

    /** 按鼠标方向选择弹膛：离中心超过退壳星即可选中，不必精确点在弹膛上 */
    private int hovered(double mx, double my, int n) {
        if (n == 0) return -1;
        double dx = mx - width / 2.0, dy = my - height / 2.0;
        double dist = Math.sqrt(dx * dx + dy * dy);
        int rc = ringR(n), cr = chamberR(n);
        if (dist < Math.max(18, rc - cr - 14) || dist > rc + cr + 40) return -1;
        double a = Math.atan2(dy, dx) + Math.PI / 2;
        double step = Math.PI * 2 / n;
        int i = (int) Math.floor((a + step / 2) / step);
        return Math.floorMod(i, n);
    }

    private static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    /** 圆环（外半径 ro，内半径 ri） */
    private static void ring(GuiGraphics g, int cx, int cy, int ro, int ri, int color) {
        for (int dy = -ro; dy <= ro; dy++) {
            int xo = (int) Math.sqrt(Math.max(0, ro * ro - dy * dy));
            if (Math.abs(dy) >= ri) {
                g.fill(cx - xo, cy + dy, cx + xo + 1, cy + dy + 1, color);
            } else {
                int xi = (int) Math.sqrt(Math.max(0, ri * ri - dy * dy));
                g.fill(cx - xo, cy + dy, cx - xi, cy + dy + 1, color);
                g.fill(cx + xi + 1, cy + dy, cx + xo + 1, cy + dy + 1, color);
            }
        }
    }

    /** 金属质感圆盘：由外到内逐层变亮，左上方带高光 */
    private static void metalDisc(GuiGraphics g, int cx, int cy, int r, int dark, int light, float a) {
        int steps = Math.min(10, Math.max(4, r / 6));
        for (int k = 0; k < steps; k++) {
            float t = k / (float) (steps - 1);
            int rr = Math.round(r * (1f - t * 0.55f));
            disc(g, cx, cy, rr, fade(lerp(dark, light, t * 0.8f), a));
        }
        // 高光：偏左上的淡色圆
        disc(g, cx - r / 4, cy - r / 4, Math.max(2, r / 3), fade(0x18FFFFFF, a));
    }

    private static int lerp(int c1, int c2, float t) {
        int a1 = c1 >>> 24, r1 = c1 >> 16 & 0xFF, g1 = c1 >> 8 & 0xFF, b1 = c1 & 0xFF;
        int a2 = c2 >>> 24, r2 = c2 >> 16 & 0xFF, g2 = c2 >> 8 & 0xFF, b2 = c2 & 0xFF;
        return (Math.round(a1 + (a2 - a1) * t) << 24) | (Math.round(r1 + (r2 - r1) * t) << 16)
                | (Math.round(g1 + (g2 - g1) * t) << 8) | Math.round(b1 + (b2 - b1) * t);
    }

    private static int fade(int argb, float a) {
        int al = Math.round((argb >>> 24) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x70000008);
    }

    private void drawScaled(GuiGraphics g, String text, int cx, int y, float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, -font.width(text) / 2, 0, color, true);
        g.pose().popPose();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        List<Entry> es = visible();
        int n = es.size();
        long now = System.currentTimeMillis();
        float in = Math.min(1f, (now - openedAt) / 180f);
        float ease = 1f - (1f - in) * (1f - in);
        int cx = width / 2, cy = height / 2;
        int cr = chamberR(n), rc = ringR(n);
        int R = (int) ((rc + cr + 9) * (0.85f + 0.15f * ease));
        int hv = hovered(mx, my, n);
        if (hv != lastHover) {
            if (hv >= 0) ZsTheme.click(1.8f);
            lastHover = hv;
        }
        double spin = spin();

        // --- 弹巢本体：外缘暗边 + 金属渐变 ---
        disc(g, cx, cy, R + 3, fade(0xE0050507, ease));
        metalDisc(g, cx, cy, R, 0xF0202228, 0xF05A5E68, ease);
        ring(g, cx, cy, R, R - 2, fade(0xFF0C0D10, ease));
        ring(g, cx, cy, R - 3, R - 4, fade(0x40FFFFFF, ease));
        // 外缘凹槽（弹膛之间的减重槽）
        for (int i = 0; i < n; i++) {
            double a = angleOf(i, n) + Math.PI / n + spin;
            int fx = cx + (int) Math.round(Math.cos(a) * (R - 1));
            int fy = cy + (int) Math.round(Math.sin(a) * (R - 1));
            disc(g, fx, fy, Math.max(4, cr / 3), fade(0xF0121317, ease));
            disc(g, fx - (int) Math.round(Math.cos(a)), fy - (int) Math.round(Math.sin(a)), Math.max(2, cr / 3 - 2), fade(0x30FFFFFF, ease));
        }

        // --- 弹膛 ---
        for (int i = 0; i < n; i++) {
            double a = angleOf(i, n) + spin;
            int px = cx + (int) Math.round(Math.cos(a) * rc * (0.6f + 0.4f * ease));
            int py = cy + (int) Math.round(Math.sin(a) * rc * (0.6f + 0.4f * ease));
            boolean sel = hv == i;
            // 膛口：深色倒角
            disc(g, px, py, cr + 2, fade(0xFF0A0A0C, ease));
            disc(g, px, py, cr, fade(sel ? 0xFF3A2A12 : 0xFF141418, ease));
            // 子弹底火（弹壳底面）：黄铜圆盘 + 底火
            int br = cr - 3;
            int brass = sel ? 0xFFF3C766 : 0xFF9C7A3C, brassDark = sel ? 0xFFB07A22 : 0xFF5E4822;
            disc(g, px, py, br, fade(brassDark, ease));
            disc(g, px - 1, py - 1, br - 2, fade(brass, ease));
            ring(g, px, py, br - 4, br - 5, fade(sel ? 0x90FFF2C0 : 0x40000000, ease));
            disc(g, px, py, Math.max(2, br / 4), fade(sel ? 0xFFFFE9A8 : 0xFF7A6030, ease));
            if (sel) {
                // 选中：发光描边
                float pulse = (float) (0.6 + 0.4 * Math.sin(now / 140.0));
                ring(g, px, py, cr + 4, cr + 2, fade(0xFFFFD27A, ease * pulse));
            }
            // 文字：名称 / 状态
            Entry e = es.get(i);
            String label = e.label().get(), value = e.value().get();
            float ls = Math.min(1f, (br * 2f - 2) / Math.max(1, font.width(label)));
            float vs = Math.min(0.8f, (br * 2f - 4) / Math.max(1, font.width(value)));
            drawScaled(g, label, px, py - (int) (8 * ls) + 1, ls, sel ? 0xFF2A1A06 : 0xFFF1EBDD);
            drawScaled(g, value, px, py + 2, vs, sel ? 0xFF4A2A00 : 0xFFE8C77E);
        }

        // --- 中心：转轴与退壳星 ---
        int hub = Math.max(16, rc - cr - 10);
        disc(g, cx, cy, hub, fade(0xF0121317, ease));
        metalDisc(g, cx, cy, hub - 2, 0xF0262830, 0xF04C505A, ease);
        // 指针（撞针方向）：平滑转向选中的弹膛
        if (hv >= 0) {
            double target = angleOf(hv, n);
            if (Double.isNaN(needle)) needle = target;
            double diff = Math.atan2(Math.sin(target - needle), Math.cos(target - needle));
            needle += diff * 0.35;
            for (int k = hub - 6; k < hub + 3; k++) {
                int nx = cx + (int) Math.round(Math.cos(needle) * k), ny = cy + (int) Math.round(Math.sin(needle) * k);
                disc(g, nx, ny, 1, fade(0xFFFFD27A, ease));
            }
        }
        // 说明文字
        if (hv >= 0) {
            Entry e = es.get(hv);
            String label = e.label().get();
            float s1 = Math.min(1.25f, (hub * 2f - 10) / Math.max(1, font.width(label)));
            drawScaled(g, label, cx, cy - (int) (10 * s1), s1, 0xFFFFE9B8);
            String value = e.value().get();
            float s2 = Math.min(1f, (hub * 2f - 10) / Math.max(1, font.width(value)));
            drawScaled(g, value, cx, cy + 3, s2, 0xFFE8C77E);
        } else {
            String hint = Component.translatable("screen.zhushenspace.wheel.hint").getString();
            float s1 = Math.min(0.9f, (hub * 2f - 8) / Math.max(1, font.width(hint)));
            drawScaled(g, hint, cx, cy - 3, s1, 0xFFBBB2C8);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        List<Entry> es = visible();
        int hv = hovered(mx, my, es.size());
        if (hv < 0) return super.mouseClicked(mx, my, button);
        es.get(hv).click().accept(button);
        ZsTheme.click(1.2f);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        List<Entry> es = visible();
        int hv = hovered(mx, my, es.size());
        if (hv >= 0 && es.get(hv).scroll() != null) {
            es.get(hv).scroll().accept(sy > 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (ClientSetup.ART_WHEEL.matches(key, scan)) { onClose(); return true; }
        return super.keyReleased(key, scan, mods);
    }
}

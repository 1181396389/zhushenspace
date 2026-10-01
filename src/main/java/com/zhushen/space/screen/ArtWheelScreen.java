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
        // 全力防御：开启期间防御再加一次基础防御，发起攻击即解除
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.full_defense"),
                () -> tr(ClientArtData.flag(PoolEffects.F_FULL_DEF) ? "screen.zhushenspace.wheel.on" : "screen.zhushenspace.wheel.off"),
                () -> true, b -> art(23, 0, 0), null));
        // ===== 能量池基础用法 =====
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.boost"),
                () -> tr(ClientArtData.flag(PoolEffects.F_BOOST) ? "screen.zhushenspace.wheel.on" : "screen.zhushenspace.wheel.off"),
                () -> anyPool(), b -> art(10, 0, 0), null));
        ENTRIES.add(poolEntry("screen.zhushenspace.wheel.sense", "magic", PoolEffects.F_SENSE, 11));
        ENTRIES.add(poolEntry("screen.zhushenspace.wheel.spider", "chakra", PoolEffects.F_SPIDER, 12));
        ENTRIES.add(poolEntry("screen.zhushenspace.wheel.water", "chakra", PoolEffects.F_WATER, 13));
        ENTRIES.add(poolEntry("screen.zhushenspace.wheel.sight", "dao", PoolEffects.F_SIGHT, 14));
        // 念动力场：开启后每次受到攻击花 1 点念动力，防御 + 有效敏捷
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.tk_field"),
                () -> tr(ClientArtData.flag(PoolEffects.F_TK_FIELD) ? "screen.zhushenspace.wheel.on" : "screen.zhushenspace.wheel.off"),
                () -> com.zhushen.space.client.ClientEnergyData.hasPool("telekinesis"), b -> art(24, 0, 0), null));
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
    private int lastHover = -1;
    /** 指针（撞针）角度，平滑跟随选中的弹膛 */
    private double needle = Double.NaN;
    /** 开场转轮时已经发出的棘轮声次数 */
    private int ratchet;
    /** 击发闪光：弹膛序号与时刻 */
    private int flashIdx = -1;
    private long flashAt;

    private static final int PLATE_H = 22;
    /** 光源方向：左上 */
    private static final double LIGHT = -Math.PI * 3 / 4;

    public ArtWheelScreen() { super(Component.translatable("screen.zhushenspace.wheel")); }

    @Override
    public boolean isPauseScreen() { return false; }

    private List<Entry> visible() {
        List<Entry> l = new ArrayList<>();
        for (Entry e : ENTRIES) if (e.visible().getAsBoolean()) l.add(e);
        return l;
    }

    // ===== 左轮弹巢布局 =====
    // 弹巢（圆柱）外半径 R；弹膛（每个条目一个）沿半径 rc 均匀分布，弹膛半径 cr；
    // 中心是退壳星与指针，弹巢下方的铭牌显示选中条目的名称与状态。

    /** {弹膛半径, 弹膛所在圆半径}；条目多或屏幕小时自动缩小 */
    private int[] layout(int n) {
        int m = Math.max(6, n);
        int budget = Math.min(width / 2, (height - PLATE_H - 16) / 2) - 16; // rc + cr + 外缘
        int cr = 24;
        int rc = (int) Math.ceil(m * (cr * 2 + 6) / (2 * Math.PI));
        if (rc + cr > budget) {
            cr = Math.max(12, (int) ((budget - 6.0 * m / (2 * Math.PI)) / (1 + m / Math.PI)));
            rc = (int) Math.ceil(m * (cr * 2 + 6) / (2 * Math.PI));
        }
        rc = Math.max(rc, cr + 30);
        return new int[]{cr, rc};
    }

    private int centerY() {
        return (height - PLATE_H - 8) / 2 + 2;
    }

    private double angleOf(int i, int n) {
        return -Math.PI / 2 + i * (Math.PI * 2 / n);
    }

    /** 开场转轮：像甩出弹巢后拨动，转过几个弹膛后减速停下 */
    private double spin(int n) {
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 520f);
        float e = 1f - (float) Math.pow(1f - t, 3);
        return (1f - e) * (Math.PI * 2 / Math.max(1, n)) * 5;
    }

    /** 按鼠标方向选择弹膛：离开中心退壳星即可选中，不必精确点在弹膛上 */
    private int hovered(double mx, double my, int n) {
        if (n == 0) return -1;
        int[] l = layout(n);
        double dx = mx - width / 2.0, dy = my - centerY();
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < Math.max(14, l[1] - l[0] - 12) * 0.7 || dist > l[1] + l[0] + 60) return -1;
        double a = Math.atan2(dy, dx) + Math.PI / 2;
        double step = Math.PI * 2 / n;
        return Math.floorMod((int) Math.floor((a + step / 2) / step), n);
    }

    private static void sound(net.minecraft.sounds.SoundEvent e, float pitch, float vol) {
        net.minecraft.client.Minecraft.getInstance().getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(e, pitch, vol));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x58000006);
    }

    private void text(GuiGraphics g, String s, float cx, float y, float scale, int color, boolean shadow) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, s, -font.width(s) / 2, 0, color, shadow);
        g.pose().popPose();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        List<Entry> es = visible();
        int n = es.size();
        if (n == 0) return;
        long now = System.currentTimeMillis();
        float in = Math.min(1f, (now - openedAt) / 200f);
        float ease = 1f - (1f - in) * (1f - in) * (1f - in);
        float cx = width / 2f, cy = centerY();
        int[] l = layout(n);
        float cr = l[0], rc = l[1];
        float R = rc + cr + 9;
        float scale = 0.82f + 0.18f * ease;
        int hv = hovered(mx, my, n);
        if (hv != lastHover) {
            if (hv >= 0) sound(net.minecraft.sounds.SoundEvents.LEVER_CLICK, 1.9f, 0.18f);
            lastHover = hv;
        }
        double spin = spin(n);
        int ticks = (int) Math.floor(spin / (Math.PI * 2 / n));
        if (now - openedAt < 520 && ticks != ratchet) {
            ratchet = ticks;
            sound(net.minecraft.sounds.SoundEvents.LEVER_CLICK, 2.0f, 0.10f);
        }

        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(scale, scale, 1f);
        g.pose().translate(-cx, -cy, 0);

        // ---------- 弹巢本体 ----------
        ZsShapes.glow(g, cx + 2, cy + 4, R - 2, 16, ZsShapes.fade(0xA0000000, ease));      // 投影
        ZsShapes.disc(g, cx, cy, R, ZsShapes.fade(0xFF4A4F59, ease), ZsShapes.fade(0xFF1E2126, ease));
        // 车削纹：若干极细的同心圆
        for (int k = 0; k < 7; k++) {
            float rr = R * (0.30f + 0.095f * k);
            ZsShapes.ring(g, cx, cy, rr, rr + 0.5f, ZsShapes.fade(0x16FFFFFF, ease), ZsShapes.fade(0x05FFFFFF, ease));
        }
        // 外缘倒角（受光）与内侧暗线
        ZsShapes.litRing(g, cx, cy, R - 3.5f, R, ZsShapes.fade(0xFF121418, ease), ZsShapes.fade(0xFF9EA4B0, ease), LIGHT, false);
        ZsShapes.ring(g, cx, cy, R - 4.4f, R - 3.5f, ZsShapes.fade(0x00000000, ease), ZsShapes.fade(0x70000000, ease));
        // 减重槽：弹膛之间靠近外缘的凹槽
        for (int i = 0; i < n; i++) {
            double a = angleOf(i, n) + Math.PI / n + spin;
            float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
            float r0 = rc + cr * 0.55f, r1 = R - 5.5f;
            if (r1 <= r0 + 1) continue;
            float w = Math.max(2.2f, cr * 0.22f);
            ZsShapes.line(g, cx + cs * r0, cy + sn * r0, cx + cs * r1, cy + sn * r1, w, ZsShapes.fade(0xE00E1013, ease), ZsShapes.fade(0xE00E1013, ease));
            ZsShapes.disc(g, cx + cs * r0, cy + sn * r0, w / 2, ZsShapes.fade(0xE00E1013, ease));
            ZsShapes.disc(g, cx + cs * r1, cy + sn * r1, w / 2, ZsShapes.fade(0xE00E1013, ease));
            // 槽壁高光（背光一侧）
            float ox = -sn * w * 0.35f, oy = cs * w * 0.35f;
            float lit = (float) ((Math.cos(a + Math.PI / 2 - LIGHT) + 1) / 2);
            ZsShapes.line(g, cx + cs * r0 + ox, cy + sn * r0 + oy, cx + cs * r1 + ox, cy + sn * r1 + oy, 0.7f,
                    ZsShapes.fade(0x50FFFFFF, ease * lit), ZsShapes.fade(0x50FFFFFF, ease * lit));
        }

        // ---------- 弹膛与子弹 ----------
        for (int i = 0; i < n; i++) {
            double a = angleOf(i, n) + spin;
            float px = cx + (float) Math.cos(a) * rc, py = cy + (float) Math.sin(a) * rc;
            boolean sel = hv == i;
            float dim = hv >= 0 && !sel ? 0.78f : 1f;
            // 膛口：深孔 + 凹面倒角
            ZsShapes.disc(g, px, py, cr + 1.2f, ZsShapes.fade(0xFF050506, ease), ZsShapes.fade(0xFF0B0C0E, ease));
            ZsShapes.litRing(g, px, py, cr - 0.4f, cr + 1.6f, ZsShapes.fade(0xFF0A0B0D, ease), ZsShapes.fade(0xFF7A808B, ease), LIGHT, true);
            // 子弹底面：黄铜（选中时弹出并发亮）
            float br = (cr - 2.6f) * (sel ? 1.04f : 0.97f);
            int hi = sel ? 0xFFFFE7A6 : 0xFFD6AE5E, mid = sel ? 0xFFF0C25C : 0xFFA9823A, lo = sel ? 0xFF9C6A1C : 0xFF5C4520;
            ZsShapes.disc(g, px, py, br, ZsShapes.fade(ZsShapes.lerp(hi, mid, 0.35f), ease * dim), ZsShapes.fade(lo, ease * dim));
            ZsShapes.disc(g, px - br * 0.22f, py - br * 0.22f, br * 0.55f, ZsShapes.fade(0x40FFFFFF, ease * dim), ZsShapes.fade(0x00FFFFFF, 0));
            ZsShapes.litRing(g, px, py, br - 1.4f, br, ZsShapes.fade(lo, ease * dim), ZsShapes.fade(hi, ease * dim), LIGHT, false);
            ZsShapes.ring(g, px, py, br * 0.80f - 0.5f, br * 0.80f, ZsShapes.fade(0x00000000, ease), ZsShapes.fade(0x55000000, ease * dim));
            if (sel) {
                float pulse = (float) (0.65 + 0.35 * Math.sin(now / 150.0));
                ZsShapes.glow(g, px, py, cr + 1.6f, 6f, ZsShapes.fade(0xC0FFC45A, ease * pulse));
            }
            if (i == flashIdx && now - flashAt < 320) {
                float t = (now - flashAt) / 320f;
                ZsShapes.ring(g, px, py, cr * (0.4f + t), cr * (0.4f + t) + 2.5f * (1 - t),
                        ZsShapes.fade(0xFFFFF4D0, 1 - t), ZsShapes.fade(0x00FFF4D0, 0));
                ZsShapes.disc(g, px, py, br * (1 - t), ZsShapes.fade(0xA0FFFFFF, 1 - t), ZsShapes.fade(0x00FFFFFF, 0));
            }
        }

        // ---------- 中心：退壳星 + 转轴 ----------
        float hub = Math.max(12, rc - cr - 7);
        ZsShapes.disc(g, cx, cy, hub + 2.5f, ZsShapes.fade(0xFF101215, ease), ZsShapes.fade(0xFF15171B, ease));
        ZsShapes.litRing(g, cx, cy, hub + 1f, hub + 2.5f, ZsShapes.fade(0xFF0B0C0E, ease), ZsShapes.fade(0xFF5E636D, ease), LIGHT, true);
        double starRot = -Math.PI / 2 + spin * 0.6;
        ZsShapes.star(g, cx + 0.8f, cy + 1.2f, hub, hub * 0.6f, 6, starRot, ZsShapes.fade(0x80000000, ease), ZsShapes.fade(0x40000000, ease));
        ZsShapes.star(g, cx, cy, hub, hub * 0.6f, 6, starRot, ZsShapes.fade(0xFF8C929E, ease), ZsShapes.fade(0xFF3C4048, ease));
        ZsShapes.star(g, cx - hub * 0.06f, cy - hub * 0.06f, hub * 0.72f, hub * 0.44f, 6, starRot,
                ZsShapes.fade(0x30FFFFFF, ease), ZsShapes.fade(0x00FFFFFF, 0));

        // 指针（撞针）：黄铜锥形，平滑转向选中的弹膛
        if (hv >= 0) {
            double target = angleOf(hv, n) + spin;
            if (Double.isNaN(needle)) needle = target;
            needle += Math.atan2(Math.sin(target - needle), Math.cos(target - needle)) * 0.3;
            float cs = (float) Math.cos(needle), sn = (float) Math.sin(needle);
            float tip = rc - cr - 3.5f, base = hub * 0.2f, w = Math.max(2.4f, hub * 0.2f);
            float bx = cx + cs * base, by = cy + sn * base, tx = cx + cs * tip, ty = cy + sn * tip;
            ZsShapes.glow(g, tx, ty, 1f, 5f, ZsShapes.fade(0x90FFC45A, ease));
            ZsShapes.tri(g, tx, ty, ZsShapes.fade(0xFFFFF0C0, ease),
                    bx - sn * w, by + cs * w, ZsShapes.fade(0xFFB8862E, ease), bx + sn * w, by - cs * w, ZsShapes.fade(0xFFE6BE62, ease));
        }
        // 中心销：带一字槽的螺丝
        float pin = Math.max(3.5f, hub * 0.3f);
        ZsShapes.disc(g, cx, cy, pin, ZsShapes.fade(0xFF9AA0AB, ease), ZsShapes.fade(0xFF3A3E46, ease));
        ZsShapes.litRing(g, cx, cy, pin - 1f, pin, ZsShapes.fade(0xFF22252A, ease), ZsShapes.fade(0xFFD0D5DE, ease), LIGHT, false);
        double slot = starRot + Math.PI / 4;
        ZsShapes.line(g, cx - (float) Math.cos(slot) * pin * 0.7f, cy - (float) Math.sin(slot) * pin * 0.7f,
                cx + (float) Math.cos(slot) * pin * 0.7f, cy + (float) Math.sin(slot) * pin * 0.7f, Math.max(0.8f, pin * 0.22f),
                ZsShapes.fade(0xE0181A1E, ease), ZsShapes.fade(0xE0181A1E, ease));

        // ---------- 弹膛上的字（刻在弹壳底面上） ----------
        for (int i = 0; i < n; i++) {
            double a = angleOf(i, n) + spin;
            float px = cx + (float) Math.cos(a) * rc, py = cy + (float) Math.sin(a) * rc;
            boolean sel = hv == i;
            float br = (cr - 2.6f) * (sel ? 1.04f : 0.97f);
            Entry e = es.get(i);
            String label = e.label().get(), value = e.value().get();
            float ls = Math.min(sel ? 0.95f : 0.85f, (br * 1.8f) / Math.max(1, font.width(label)));
            float vs = Math.min(0.7f, (br * 1.5f) / Math.max(1, font.width(value)));
            int a8 = (int) (255 * ease * (hv >= 0 && !sel ? 0.8f : 1f));
            if (a8 < 8) continue;
            text(g, label, px, py - 8 * ls + 0.5f, ls, (a8 << 24) | (sel ? 0x1E1204 : 0x2A1C08), false);
            text(g, value, px, py + 1.5f, vs, (a8 << 24) | (sel ? 0x5A3100 : 0x4E3610), false);
        }
        g.pose().popPose();

        // ---------- 铭牌 ----------
        float plateY = cy + R * scale + 8;
        String title, sub, hint;
        if (hv >= 0) {
            Entry e = es.get(hv);
            title = e.label().get();
            sub = e.value().get();
            hint = Component.translatable(e.scroll() != null ? "screen.zhushenspace.wheel.hint_scroll" : "screen.zhushenspace.wheel.hint_click").getString();
        } else {
            title = Component.translatable("screen.zhushenspace.wheel").getString();
            sub = "";
            hint = Component.translatable("screen.zhushenspace.wheel.hint_aim").getString();
        }
        int tw = font.width(title) + (sub.isEmpty() ? 0 : font.width("  " + sub));
        float hs = 0.62f;
        float pw = Math.max(tw, font.width(hint) * hs) + 26;
        float px0 = cx - pw / 2;
        ZsShapes.roundRect(g, px0, plateY, pw, PLATE_H, 5, ZsShapes.fade(0xE6262A31, ease), ZsShapes.fade(0xE60F1114, ease));
        ZsShapes.roundRectOutline(g, px0, plateY, pw, PLATE_H, 5, 0.8f, ZsShapes.fade(0x90C9A45C, ease));
        ZsShapes.line(g, px0 + 6, plateY + 1.2f, px0 + pw - 6, plateY + 1.2f, 0.5f, ZsShapes.fade(0x40FFFFFF, ease), ZsShapes.fade(0x40FFFFFF, ease));
        // 两侧铆钉
        for (float rx : new float[]{px0 + 6, px0 + pw - 6}) {
            ZsShapes.disc(g, rx, plateY + PLATE_H / 2f, 1.8f, ZsShapes.fade(0xFFE0C27A, ease), ZsShapes.fade(0xFF6A5024, ease));
        }
        int a8 = (int) (255 * ease);
        if (a8 >= 8) {
            float tx = cx - tw / 2f;
            g.drawString(font, title, (int) tx, (int) plateY + 3, (a8 << 24) | 0xF3EDE0, true);
            if (!sub.isEmpty()) g.drawString(font, "  " + sub, (int) tx + font.width(title), (int) plateY + 3, (a8 << 24) | 0xE8C77E, true);
            text(g, hint, cx, plateY + 13.5f, hs, (a8 << 24) | 0x9D96A8, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        List<Entry> es = visible();
        int hv = hovered(mx, my, es.size());
        if (hv < 0) return super.mouseClicked(mx, my, button);
        es.get(hv).click().accept(button);
        flashIdx = hv;
        flashAt = System.currentTimeMillis();
        // 击发：扳机的金属撞击声
        sound(net.minecraft.sounds.SoundEvents.IRON_TRAPDOOR_CLOSE, 1.6f, 0.35f);
        sound(net.minecraft.sounds.SoundEvents.LEVER_CLICK, 1.2f, 0.4f);
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

package com.zhushen.space.screen;

import com.zhushen.space.client.ClientFeatData;
import com.zhushen.space.data.FeatType;
import com.zhushen.space.network.CommitFeatsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 专长界面 —— 咒术回战风格（全屏，逐帧程序动画）。
 * <pre>
 * ┌──────────── 領域展開 ────────────┐
 * │ 無量空処（五条悟）  ⚡  伏魔御廚子（宿傩） │  ← 上 64%：两个领域左右对撞，中缝为锯齿闪电 + 火花 + 冲击波
 * ├───────────────┬─────────────────┤
 * │ 虎杖悠仁（黑闪）  │ 伏黑惠（十种影法术）  │  ← 下 36%：铺垫区块
 * └───────────────┴─────────────────┘
 * </pre>
 * 打开时两个领域从左右两侧推进并在中线相撞（白闪 + 雷鸣）。
 */
public class FeatScreen extends Screen {

    private final Screen parent;
    private long openedAt = -1;
    private boolean clashSound;
    /** 待确认的掩码（包含已习得） */
    private int pending;
    private long commitUntil;
    private int lastOwned = ClientFeatData.owned();

    private int topH, cw, ch;
    private final int[][] cards = new int[FeatType.COUNT][2];

    public FeatScreen(Screen parent) {
        super(Component.translatable("screen.zhushenspace.feats.title"));
        this.parent = parent;
        this.pending = ClientFeatData.owned();
    }

    @Override
    protected void init() {
        if (openedAt < 0) openedAt = ZsAnim.nowMs();
        topH = (int) (height * 0.64f);
        int half = width / 2;
        cw = Math.max(78, Math.min(128, (half - 44) / 2));
        ch = 38;
        int gy = 36;
        int gap = 8;
        int[] line = new int[4];
        for (FeatType f : FeatType.values()) {
            int i = line[f.line.ordinal()]++;
            int x, y;
            switch (f.line) {
                case GOJO -> { x = 14 + (i % 2) * (cw + gap); y = gy + (i / 2) * (ch + gap); }
                case SUKUNA -> { x = width - 14 - 2 * cw - gap + (i % 2) * (cw + gap); y = gy + (i / 2) * (ch + gap); }
                case ITADORI -> { x = 14 + i * (cw + gap); y = topH + 20; }
                default -> { x = half + 14 + i * (cw + gap); y = topH + 20; }
            }
            cards[f.ordinal()][0] = x;
            cards[f.ordinal()][1] = y;
        }
    }

    // ===== 逻辑 =====

    private boolean dirty() {
        return pending != ClientFeatData.owned();
    }

    private boolean owned(FeatType f) {
        return (ClientFeatData.owned() & f.bit()) != 0;
    }

    private boolean picked(FeatType f) {
        return (pending & f.bit()) != 0;
    }

    private boolean available(FeatType f) {
        if (picked(f)) return false;
        FeatType p = f.prerequisite();
        return (p == null || picked(p)) && ClientFeatData.free(pending) >= f.cost;
    }

    private void toggle(FeatType f) {
        if (owned(f)) return;
        if (picked(f)) {
            // 取消：连同依赖它的后续专长一起取消
            int m = pending & ~f.bit();
            for (FeatType o : FeatType.values()) {
                if (o.line == f.line && o.ordinal() > f.ordinal() && !owned(o)) m &= ~o.bit();
            }
            pending = m;
            sound(SoundEvents.UI_BUTTON_CLICK.value(), 0.8f);
        } else if (available(f)) {
            pending |= f.bit();
            sound(f.line == FeatType.Line.SUKUNA ? SoundEvents.PLAYER_ATTACK_SWEEP
                    : f.line == FeatType.Line.GOJO ? SoundEvents.AMETHYST_BLOCK_CHIME
                    : f.line == FeatType.Line.ITADORI ? SoundEvents.PLAYER_ATTACK_CRIT
                    : SoundEvents.WOLF_AMBIENT, 1.2f);
        } else {
            sound(SoundEvents.VILLAGER_NO, 1.0f);
        }
    }

    private void sound(net.minecraft.sounds.SoundEvent e, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(e, pitch, 0.6f));
    }

    // ===== 渲染 =====

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = ZsAnim.nowMs();
        // 服务端数据变化（确认回包 / 指令）时：若本地无未确认改动则跟随
        int ownedNow = ClientFeatData.owned();
        if (ownedNow != lastOwned) {
            if (pending == lastOwned || (pending & ownedNow) != ownedNow) pending = ownedNow;
            lastOwned = ownedNow;
        }
        float t = now / 1000f;
        float intro = ZsAnim.easeOutCubic((now - openedAt) / 650f);
        if (!clashSound && now - openedAt > 480) {
            clashSound = true;
            sound(SoundEvents.TRIDENT_THUNDER.value(), 1.3f);
        }
        int half = width / 2;
        float push = (float) Math.sin(t * 0.9) * width * 0.03f;
        int seamMin = (int) (half - width * 0.03f) - 6, seamMax = (int) (half + width * 0.03f) + 6;

        g.fill(0, 0, width, height, 0xFF000000);

        // —— 左：无量空处 ——
        int offL = (int) (-(1 - intro) * half);
        g.enableScissor(0, 0, Math.max(0, half + (int) push + offL + 4), topH);
        g.pose().pushPose();
        g.pose().translate(offL, 0, 0);
        drawInfiniteVoid(g, t, now, half);
        g.pose().popPose();
        g.disableScissor();

        // —— 右：伏魔御厨子（逐行按锯齿中缝裁切） ——
        int offR = (int) ((1 - intro) * half);
        g.enableScissor(Math.min(width, seamMin + offR), 0, width, topH);
        g.pose().pushPose();
        g.pose().translate(offR, 0, 0);
        drawShrine(g, t, now, half, seamMin);
        g.pose().popPose();
        g.disableScissor();

        // —— 中缝：领域对撞 ——
        if (intro > 0.7f) drawClash(g, t, now, push);

        // —— 下：虎杖 / 伏黑 ——
        float bottomIn = ZsAnim.easeOutCubic((now - openedAt - 250) / 500f);
        g.pose().pushPose();
        g.pose().translate(0, (1 - bottomIn) * (height - topH), 0);
        drawItadori(g, t, now, 0, topH, half, height - topH);
        drawMegumi(g, t, now, half, topH, width - half, height - topH);
        // 区块分隔：上下粗墨线 + 符纸竖线
        g.fill(0, topH, width, topH + 2, INK_LINE);
        g.fill(0, topH + 2, width, topH + 3, 0x55FFFFFF);
        g.fill(half - 1, topH, half + 1, height, INK_LINE);
        g.pose().popPose();

        // 撞击白闪
        long since = now - openedAt;
        if (since > 450 && since < 900) {
            float a = 1 - (since - 450) / 450f;
            g.fill(0, 0, width, height, JjkStyle.alpha(0xFFFFFFFF, a * 0.85f));
        }

        // —— 顶栏 ——
        drawHeader(g, mx, my, t, now);

        // —— 专长卡片 ——
        List<FormattedCharSequence> tip = null;
        if (intro >= 1f) {
            for (FeatType f : FeatType.values()) {
                int x = cards[f.ordinal()][0], y = cards[f.ordinal()][1];
                if (f.line == FeatType.Line.ITADORI || f.line == FeatType.Line.MEGUMI) {
                    y += (int) ((1 - bottomIn) * (height - topH));
                }
                boolean hover = ZsTheme.over(mx, my, x, y, cw, ch);
                drawCard(g, f, x, y, hover, now);
                if (hover) tip = tooltip(f);
            }
        }
        if (tip != null) g.renderTooltip(font, tip, mx, my);
    }

    private static final int INK_LINE = 0xFF000000;

    private void drawHeader(GuiGraphics g, int mx, int my, float t, long now) {
        g.fillGradient(0, 0, width, 30, 0xE0000000, 0x00000000);
        // 标题：領域展開（蓝红错位 + 轻微抖动）
        String head = this.title.getString();
        float s = 1.5f;
        int tw = (int) (font.width(head) * s);
        float jx = (float) (JjkStyle.hash(now / 50, 1) - 0.5f) * 1.5f;
        g.pose().pushPose();
        g.pose().translate(width / 2f - tw / 2f + jx, 6, 50);
        g.pose().scale(s, s, 1);
        g.drawString(font, head, -1, 0, 0xAA6FD3FF, false);
        g.drawString(font, head, 1, 0, 0xAAE0253A, false);
        g.drawString(font, head, 0, 0, 0xFFFFFFFF, false);
        g.pose().popPose();
        // 两侧领域名（竖排大字水印感：横向淡字）
        g.drawString(font, Component.translatable("screen.zhushenspace.feats.void"), 14, 22, 0xCC9FDFFF, true);
        Component shrine = Component.translatable("screen.zhushenspace.feats.shrine");
        g.drawString(font, shrine, width - 14 - font.width(shrine), 22, 0xCCFF8080, true);
        // 点数
        int free = ClientFeatData.free(pending);
        Component pts = Component.translatable("screen.zhushenspace.feats.points", free, ClientFeatData.total());
        g.drawString(font, pts, 8, 6, free > 0 ? 0xFFFFE08A : 0xFFB0B0B0, true);
        // 按钮：确认 / 重置 / 返回
        int bx = width - 8 - 36;
        JjkStyle.button(g, font, mx, my, bx, 4, 36, 14, Component.translatable("screen.zhushenspace.feats.back"), 0xFFB0B0B0, true);
        JjkStyle.button(g, font, mx, my, bx - 40, 4, 36, 14, Component.translatable("screen.zhushenspace.feats.reset"), JjkStyle.GOJO, dirty());
        JjkStyle.button(g, font, mx, my, bx - 80, 4, 36, 14, Component.translatable("screen.zhushenspace.feats.confirm"), JjkStyle.SUKUNA, dirty());
    }

    /** 无量空处：深空 + 向外奔流的无限信息 + 旋转的无下限环 + 巨大的六眼 */
    private void drawInfiniteVoid(GuiGraphics g, float t, long now, int half) {
        g.fillGradient(0, 0, half + 40, topH, 0xFF02030A, 0xFF0A1030);
        float cx = half * 0.52f, cy = topH * 0.56f;
        // 奔流的星点（从六眼中心向外加速）
        for (int i = 0; i < 160; i++) {
            float ang = JjkStyle.hash(i, 7) * 6.2832f;
            float ph = (t * (0.25f + JjkStyle.hash(i, 3) * 0.5f) + JjkStyle.hash(i, 5)) % 1f;
            float r = ph * ph * half * 0.9f;
            int x = (int) (cx + Math.cos(ang) * r), y = (int) (cy + Math.sin(ang) * r * 0.8f);
            int a = (int) (40 + 200 * ph);
            int col = (a << 24) | (i % 5 == 0 ? 0x6FD3FF : i % 7 == 0 ? 0xB08CFF : 0xFFFFFF);
            int sz = ph > 0.7f ? 2 : 1;
            g.fill(x, y, x + sz, y + sz, col);
            if (ph > 0.5f) JjkStyle.line(g, x, y, x - (float) Math.cos(ang) * 4 * ph, y - (float) Math.sin(ang) * 3 * ph, 1, (a / 3 << 24) | 0xFFFFFF);
        }
        // 无下限：层层旋转的点环
        for (int k = 0; k < 4; k++) {
            float rr = 26 + k * 16 + (float) Math.sin(t * 1.3 + k) * 2;
            JjkStyle.ring(g, cx, cy, rr * 1.25f, rr * 0.7f, t * (k % 2 == 0 ? 0.4f : -0.3f) + k, 40 + k * 14,
                    JjkStyle.alpha(k % 2 == 0 ? 0xFF6FD3FF : 0xFFFFFFFF, 0.55f - k * 0.1f));
        }
        // 六眼：虹膜（外蓝内浅）+ 瞳孔 + 高光；每 6 秒眨眼
        float blinkPh = (now % 6000) / 6000f;
        float sy = blinkPh > 0.96f ? Math.abs((blinkPh - 0.98f) / 0.02f) : 1f;
        float r = Math.min(30, topH * 0.18f);
        JjkStyle.disk(g, cx, cy, r + 3, sy, 0x3A6FD3FF);
        JjkStyle.disk(g, cx, cy, r, sy, 0x884FA8FF);
        JjkStyle.disk(g, cx, cy, r * 0.72f, sy, 0x88A9DFFF);
        JjkStyle.disk(g, cx, cy, r * 0.42f, sy, 0x99D8F4FF);
        JjkStyle.disk(g, cx, cy, r * 0.2f, sy, 0xAA0A1030);
        JjkStyle.disk(g, cx - r * 0.35f, cy - r * 0.35f * sy, r * 0.12f, sy, 0xCCFFFFFF);
        // 虹膜放射纹
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12 + t * 0.2;
            JjkStyle.line(g, cx + (float) Math.cos(a) * r * 0.45f, cy + (float) Math.sin(a) * r * 0.45f * sy,
                    cx + (float) Math.cos(a) * r * 0.95f, cy + (float) Math.sin(a) * r * 0.95f * sy, 1, 0x40FFFFFF);
        }
    }

    /** 伏魔御厨子：猩红天幕 + 血池倒影 + 骨山神龛（四只红眼）+ 不断落下的「解」斩痕 */
    private void drawShrine(GuiGraphics g, float t, long now, int half, int seamMin) {
        g.fillGradient(seamMin - 10, 0, width + 10, topH, 0xFF2A0610, 0xFF0C0205);
        float cx = half + half * 0.5f;
        int base = topH - 14;
        // 血池
        g.fill(seamMin - 10, base, width, topH, 0xFF3A0610);
        for (int i = 0; i < 6; i++) {
            int yy = base + 2 + i * 2;
            int off = (int) (Math.sin(t * 2 + i) * 6);
            g.fill((int) cx - 70 + off + i * 3, yy, (int) cx + 70 + off - i * 3, yy + 1, 0x55FF4050);
        }
        // 骨堆
        for (int i = 0; i < 28; i++) {
            float bx = cx - 64 + JjkStyle.hash(i, 11) * 128;
            float by = base - 2 - JjkStyle.hash(i, 13) * (14 - Math.abs(bx - cx) / 8);
            JjkStyle.disk(g, bx, by, 2.2f, 1, 0xFFD8CFC0);
            g.fill((int) bx - 1, (int) by, (int) bx, (int) by + 1, INK_LINE);
            g.fill((int) bx + 1, (int) by, (int) bx + 2, (int) by + 1, INK_LINE);
        }
        // 神龛：台基 + 立柱 + 多层屋檐
        int sw = 72, top = base - 60;
        g.fill((int) cx - sw / 2, base - 18, (int) cx + sw / 2, base - 12, JjkStyle.INK);
        g.fill((int) cx - 26, top + 20, (int) cx - 20, base - 18, JjkStyle.INK);
        g.fill((int) cx + 20, top + 20, (int) cx + 26, base - 18, JjkStyle.INK);
        g.fill((int) cx - 18, top + 24, (int) cx + 18, base - 18, 0xFF1A0206); // 口（神龛之口）
        for (int k = 0; k < 3; k++) {
            int w = sw + 20 - k * 22, y = top + 18 - k * 9;
            g.fill((int) cx - w / 2, y, (int) cx + w / 2, y + 5, JjkStyle.INK);
            g.fill((int) cx - w / 2 - 4, y + 3, (int) cx - w / 2, y + 5, JjkStyle.INK); // 翘角
            g.fill((int) cx + w / 2, y + 3, (int) cx + w / 2 + 4, y + 5, JjkStyle.INK);
            g.fill((int) cx - w / 2, y + 5, (int) cx + w / 2, y + 6, 0x66E0253A);
        }
        // 宿傩四眼（两两上下，缓慢开合）
        float open = 0.6f + 0.4f * (float) Math.abs(Math.sin(t * 0.7));
        for (int i = 0; i < 4; i++) {
            float ex = cx + (i % 2 == 0 ? -8 : 8), ey = top + 32 + (i / 2) * 8;
            JjkStyle.disk(g, ex, ey, 2.6f, open, 0xFFE0253A);
            g.fill((int) ex, (int) ey - 1, (int) ex + 1, (int) ey + 2, 0xFFFFE0A0);
        }
        // 斩痕（「解」）：随机位置、随机角度，闪现后消散
        for (int i = 0; i < 9; i++) {
            long cyc = (now + i * 173) / 520;
            float ph = ((now + i * 173) % 520) / 520f;
            float sx = seamMin + 10 + JjkStyle.hash(i, cyc) * (width - seamMin - 20);
            float sy = 10 + JjkStyle.hash(i + 50, cyc) * (topH - 30);
            float ang = (JjkStyle.hash(i + 99, cyc) - 0.5f) * 1.6f + 0.6f;
            float len = 24 + JjkStyle.hash(i + 7, cyc) * 40;
            float grow = Math.min(1f, ph * 4f);
            float a = 1 - ph;
            float ex = sx + (float) Math.cos(ang) * len * grow, ey = sy + (float) Math.sin(ang) * len * grow;
            JjkStyle.line(g, sx, sy, ex, ey, 3, JjkStyle.alpha(0xFFE0253A, a * 0.6f));
            JjkStyle.line(g, sx, sy, ex, ey, 1, JjkStyle.alpha(0xFFFFFFFF, a));
        }
    }

    private float seamX(int y, float t, long now, float push) {
        return width / 2f + push + (JjkStyle.hash(y / 5, now / 70) - 0.5f) * 7
                + (float) Math.sin(y * 0.08 + t * 3) * 3;
    }

    /** 领域对撞：锯齿闪电中缝 + 蓝红辉光 + 双向火花 + 周期冲击波 */
    private void drawClash(GuiGraphics g, float t, long now, float push) {
        // 右侧领域补边：中缝右侧到裁切线之间的空隙用宿傩底色逐行填满
        int step = 3;
        float prevX = seamX(0, t, now, push);
        for (int y = 0; y < topH; y += step) {
            float x = seamX(y, t, now, push);
            int xi = (int) x;
            g.fill(xi, y, (int) (width / 2f + width * 0.03f) + 8, y + step, 0xFF22050D);
            g.fill(xi - 6, y, xi, y + step, 0x406FD3FF);
            g.fill(xi - 3, y, xi, y + step, 0x806FD3FF);
            g.fill(xi, y, xi + 3, y + step, 0x80E0253A);
            g.fill(xi + 3, y, xi + 7, y + step, 0x40E0253A);
            JjkStyle.line(g, prevX, y - step, x, y, 2, 0xFFFFFFFF);
            prevX = x;
        }
        // 火花：蓝向左、红向右
        for (int i = 0; i < 46; i++) {
            long cyc = (now + i * 61) / 650;
            float ph = ((now + i * 61) % 650) / 650f;
            int y0 = (int) (JjkStyle.hash(i, cyc) * topH);
            float x0 = seamX(y0, t, now, push);
            int dir = i % 2 == 0 ? -1 : 1;
            float x = x0 + dir * ph * (18 + JjkStyle.hash(i, cyc + 1) * 30);
            float y = y0 - ph * 8 + ph * ph * 22;
            int col = dir < 0 ? 0x6FD3FF : 0xFF5A5A;
            JjkStyle.line(g, x, y, x - dir * 3, y + 1, 1, ((int) (255 * (1 - ph)) << 24) | col);
        }
        // 冲击波：每 2.4 秒在中缝某点炸开一圈
        long cyc = now / 2400;
        float ph = (now % 2400) / 2400f;
        if (ph < 0.4f) {
            float k = ph / 0.4f;
            int y0 = (int) (topH * (0.25f + JjkStyle.hash(3, cyc) * 0.5f));
            float x0 = seamX(y0, t, now, push);
            JjkStyle.ring(g, x0, y0, 6 + k * 60, 4 + k * 40, 0, 80, JjkStyle.alpha(0xFFFFFFFF, 1 - k));
            JjkStyle.ring(g, x0 - 2, y0, 4 + k * 52, 3 + k * 34, 0, 60, JjkStyle.alpha(0xFF6FD3FF, 1 - k));
            JjkStyle.ring(g, x0 + 2, y0, 4 + k * 52, 3 + k * 34, 0.05f, 60, JjkStyle.alpha(0xFFE0253A, 1 - k));
        }
    }

    /** 虎杖悠仁：暗底 + 上升的橙色咒力 + 周期性「黑闪」（黑色闪电 + 红色描边 + 画面一震） */
    private void drawItadori(GuiGraphics g, float t, long now, int x, int y, int w, int h) {
        g.fillGradient(x, y, x + w, y + h, 0xFF1C0E08, 0xFF0A0504);
        for (int i = 0; i < 26; i++) {
            float ph = (t * (0.4f + JjkStyle.hash(i, 21) * 0.4f) + JjkStyle.hash(i, 22)) % 1f;
            int px = x + (int) (JjkStyle.hash(i, 23) * w + Math.sin(t * 2 + i) * 4);
            int py = y + h - (int) (ph * h);
            g.fill(px, py, px + 2, py + 3, JjkStyle.alpha(JjkStyle.ITADORI, (1 - ph) * 0.7f));
        }
        g.drawString(font, Component.translatable("screen.zhushenspace.feats.itadori"), x + 8, y + 6, 0xFFFFB080, true);
        // 黑闪
        long cyc = now / 2800;
        float ph = (now % 2800) / 2800f;
        if (ph < 0.16f) {
            float k = ph / 0.16f;
            float bx = x + w * (0.55f + JjkStyle.hash(1, cyc) * 0.35f);
            float by = y + h * (0.35f + JjkStyle.hash(2, cyc) * 0.4f);
            g.fill(x, y, x + w, y + h, JjkStyle.alpha(0xFFFF2020, (1 - k) * 0.25f));
            for (int b = 0; b < 7; b++) {
                float ang = b * 0.9f + JjkStyle.hash(b, cyc) * 0.6f;
                float px = bx, py = by;
                for (int s = 0; s < 4; s++) {
                    float len = 8 + JjkStyle.hash(b * 10 + s, cyc) * 10;
                    float a2 = ang + (JjkStyle.hash(b * 7 + s, cyc) - 0.5f) * 1.2f;
                    float nx = px + (float) Math.cos(a2) * len * (0.4f + k), ny = py + (float) Math.sin(a2) * len * (0.4f + k);
                    JjkStyle.line(g, px, py, nx, ny, 4, JjkStyle.alpha(0xFFFF2030, 1 - k * 0.5f));
                    JjkStyle.line(g, px, py, nx, ny, 2, 0xFF000000);
                    px = nx;
                    py = ny;
                }
            }
            String bf = Component.translatable("screen.zhushenspace.feats.black_flash").getString();
            g.pose().pushPose();
            g.pose().translate(bx, by - 18, 60);
            float s = 1.2f + k * 0.6f;
            g.pose().scale(s, s, 1);
            g.drawString(font, bf, -font.width(bf) / 2 + 1, 0, 0xFFFF2030, false);
            g.drawString(font, bf, -font.width(bf) / 2, 0, 0xFF000000, false);
            g.pose().popPose();
        }
    }

    /** 伏黑惠：纯黑底 + 翻涌的影之波 + 从影中探出的玉犬（白 / 黑），眼睛闪光 */
    private void drawMegumi(GuiGraphics g, float t, long now, int x, int y, int w, int h) {
        g.fillGradient(x, y, x + w, y + h, 0xFF0A1020, 0xFF020308);
        g.drawString(font, Component.translatable("screen.zhushenspace.feats.megumi"), x + 8, y + 6, 0xFF9FB8E8, true);
        // 玉犬（在右下，随影子起伏）
        float bob = (float) Math.sin(t * 1.5) * 2;
        dog(g, x + w - 58, y + h - 16 + (int) bob, 0xFFEDEDED, now, 0);
        dog(g, x + w - 30, y + h - 14 - (int) bob, 0xFF111418, now, 1);
        // 影之波
        for (int layer = 0; layer < 3; layer++) {
            int col = layer == 0 ? 0xFF000000 : layer == 1 ? 0xCC05070D : 0x991B2A4A;
            for (int px = x; px < x + w; px += 2) {
                float wv = (float) Math.sin(px * 0.05 + t * (1.2 + layer * 0.5) + layer) * 3
                        + (float) Math.sin(px * 0.11 - t * 2) * 1.5f;
                int top = y + h - 8 - layer * 3 + (int) wv;
                g.fill(px, top, px + 2, y + h, col);
                if (layer == 2) g.fill(px, top, px + 2, top + 1, 0x664E6FA8);
            }
        }
    }

    private void dog(GuiGraphics g, int x, int y, int col, long now, int seed) {
        int o = col == 0xFFEDEDED ? 0xFF303030 : 0xFF4E6FA8;
        g.fill(x - 1, y - 1, x + 15, y + 9, o);
        g.fill(x, y, x + 14, y + 8, col);        // 身
        g.fill(x + 9, y - 7, x + 17, y + 1, o);
        g.fill(x + 10, y - 6, x + 16, y, col);   // 头
        g.fill(x + 10, y - 9, x + 12, y - 6, col); // 耳
        g.fill(x + 14, y - 9, x + 16, y - 6, col);
        g.fill(x + 16, y - 3, x + 19, y - 1, col); // 吻
        boolean blink = (now + seed * 900) % 4000 < 150;
        if (!blink) g.fill(x + 13, y - 4, x + 15, y - 3, seed == 0 ? 0xFF4EA0FF : 0xFFFFE070);
        g.fill(x - 3, y + 1, x, y + 3, col); // 尾
        g.fill(x - 2 - (int) (Math.sin(now / 150.0) * 1.5), y - 1, x - 1, y + 1, col);
    }

    private void drawCard(GuiGraphics g, FeatType f, int x, int y, boolean hover, long now) {
        int accent, bg;
        String glyph;
        switch (f.line) {
            case GOJO -> { accent = JjkStyle.GOJO; bg = 0xE00B1630; glyph = "∞"; }
            case SUKUNA -> { accent = JjkStyle.SUKUNA; bg = 0xE01A0508; glyph = "斬"; }
            case ITADORI -> { accent = JjkStyle.ITADORI; bg = 0xE024120A; glyph = "拳"; }
            default -> { accent = JjkStyle.MEGUMI; bg = 0xE0080C18; glyph = "影"; }
        }
        boolean own = owned(f), pick = picked(f) && !own, avail = available(f);
        boolean locked = !own && !pick && !avail;
        float p = ZsAnim.pulse(1100);
        if (avail) g.fill(x - 2, y - 2, x + cw + 2, y + ch + 2, JjkStyle.alpha(accent, 0.15f + 0.3f * p));
        if (own) g.fill(x - 2, y - 2, x + cw + 2, y + ch + 2, JjkStyle.alpha(accent, 0.35f));
        g.fill(x, y, x + cw, y + ch, locked ? 0xE0101010 : bg);
        // 主题纹样
        if (f.line == FeatType.Line.SUKUNA && !locked) {
            g.fill(x + cw - 10, y + 4, x + cw - 3, y + 6, INK_LINE);  // 纹身线
            g.fill(x + cw - 8, y + 8, x + cw - 3, y + 10, INK_LINE);
        } else if (f.line == FeatType.Line.GOJO && !locked) {
            int sx = x + (int) ((now / 12) % (cw + 20)) - 10;
            if (sx > x && sx < x + cw - 3) g.fill(sx, y + 1, sx + 3, y + ch - 1, 0x22FFFFFF); // 扫光
        }
        int edge = locked ? 0xFF3A3A3A : hover ? 0xFFFFFFFF : accent;
        g.renderOutline(x, y, cw, ch, edge);
        g.fill(x, y, x + 3, y + ch, locked ? 0xFF3A3A3A : accent);
        // 字形
        g.pose().pushPose();
        g.pose().translate(x + 8, y + 12, 0);
        g.pose().scale(1.6f, 1.6f, 1);
        g.drawString(font, glyph, 0, 0, locked ? 0xFF505050 : JjkStyle.alpha(accent, 0.9f), false);
        g.pose().popPose();
        int tx = x + 26;
        Component name = Component.translatable("feat.zhushenspace." + f.key);
        String n = font.plainSubstrByWidth(name.getString(), cw - 30);
        g.drawString(font, n, tx, y + 6, locked ? 0xFF707070 : 0xFFFFFFFF, true);
        // 消耗：勾玉点
        for (int i = 0; i < f.cost; i++) {
            int dx = tx + i * 7;
            g.fill(dx, y + 18, dx + 5, y + 23, locked ? 0xFF404040 : accent);
            g.fill(dx + 1, y + 19, dx + 2, y + 20, 0x88FFFFFF);
        }
        Component st = Component.translatable(own ? "screen.zhushenspace.feats.state.owned"
                : pick ? "screen.zhushenspace.feats.state.pending"
                : avail ? "screen.zhushenspace.feats.state.available"
                : "screen.zhushenspace.feats.state.locked");
        g.drawString(font, st, tx, y + 27, own ? 0xFFFFE08A : pick ? 0xFF9CFF9C : avail ? JjkStyle.alpha(accent, 0.7f + 0.3f * p) : 0xFF606060, false);
    }

    private List<FormattedCharSequence> tooltip(FeatType f) {
        List<FormattedCharSequence> l = new ArrayList<>();
        l.add(Component.translatable("feat.zhushenspace." + f.key).withStyle(s -> s.withColor(0xFFFFFF).withBold(true)).getVisualOrderText());
        l.add(Component.translatable("screen.zhushenspace.feats.cost", f.cost).getVisualOrderText());
        FeatType pre = f.prerequisite();
        if (pre != null) l.add(Component.translatable("screen.zhushenspace.feats.requires",
                Component.translatable("feat.zhushenspace." + pre.key)).getVisualOrderText());
        l.addAll(font.split(Component.translatable("feat.zhushenspace." + f.key + ".desc").withStyle(s -> s.withColor(0xB8B8C8).withItalic(true)), 200));
        l.addAll(font.split(Component.translatable("feat.zhushenspace." + f.key + ".effect").withStyle(s -> s.withColor(0x9CFF9C)), 200));
        return l;
    }

    // ===== 交互 =====

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        int bx = width - 8 - 36;
        if (ZsTheme.over(mx, my, bx, 4, 36, 14)) { onClose(); return true; }
        if (ZsTheme.over(mx, my, bx - 40, 4, 36, 14) && dirty()) {
            pending = ClientFeatData.owned();
            sound(SoundEvents.UI_BUTTON_CLICK.value(), 0.7f);
            return true;
        }
        if (ZsTheme.over(mx, my, bx - 80, 4, 36, 14) && dirty()) {
            PacketDistributor.sendToServer(new CommitFeatsPayload(pending));
            commitUntil = ZsAnim.nowMs() + 1500;
            sound(SoundEvents.TRIDENT_THUNDER.value(), 1.6f);
            return true;
        }
        if (ZsAnim.nowMs() - openedAt < 650) return true;
        for (FeatType f : FeatType.values()) {
            if (ZsTheme.over(mx, my, cards[f.ordinal()][0], cards[f.ordinal()][1], cw, ch)) {
                toggle(f);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

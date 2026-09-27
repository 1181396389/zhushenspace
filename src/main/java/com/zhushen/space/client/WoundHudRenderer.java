package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.screen.ZsAnim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 战斗模式伤势 HUD（未来风全息面板）：
 * <ul>
 *   <li>仅在战斗模式开启时渲染，开启瞬间由左向右「扫描展开」</li>
 *   <li>顶行：完好生命值大号数字（数值平滑滚动）+ 上限；受伤/治疗时数字闪红/闪白</li>
 *   <li>三条伤势条：B 冲击 / L 严重 / A 恶性，条长按各自伤势占生命上限比例填充，带流光与前沿亮线</li>
 *   <li>伤势条右侧竖排合计条：把生命上限切成「完好 | B | L | A」四段，一眼看出距离昏迷还有多远</li>
 *   <li>昏迷（完好归零且 L 未清）时整块面板变红、闪烁警告并轻微抖动；完好 ≤ 25% 时黄色预警</li>
 *   <li>斜切角、扫描线、边角刻度等装饰，位置与缩放可在「主神空间界面设置」中拖拽调整</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public class WoundHudRenderer {

    // 未缩放基准尺寸
    public static final int W = 112;
    public static final int H = 58;
    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 2.0f;

    // 配色（青色全息主色；伤势三档沿用面板配色）
    private static final int CYAN = 0xFF4FE3FF;
    private static final int CYAN_DIM = 0xFF1E6F86;
    private static final int PANEL = 0xC0061018;
    private static final int PANEL_LINE = 0x5A4FE3FF;
    private static final int TEXT = 0xFFE6FBFF;
    private static final int TEXT_SUB = 0xFF8FC7D6;
    private static final int COL_B = 0xFFF5D76E;
    private static final int COL_L = 0xFFE8873A;
    private static final int COL_A = 0xFFE05C6E;
    private static final int WARN_YELLOW = 0xFFFFD34D;
    private static final int WARN_RED = 0xFFFF3B4A;

    /** 上次完好生命值与变化时刻（驱动数字闪光） */
    private static int lastIntact = Integer.MIN_VALUE;
    private static long lastChangeAt;
    private static int lastDir;

    /** 计算面板位置与尺寸（位置来自配置，默认屏幕右上角，并夹紧在屏幕内） */
    public static float[] layout(int screenW, int screenH, float scale) {
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        float w = W * scale;
        float h = H * scale;
        float x = cfg.woundX < 0 ? screenW - w - 6 : clamp(cfg.woundX, 0, Math.max(0, screenW - w));
        float y = cfg.woundY < 0 ? 6 : clamp(cfg.woundY, 0, Math.max(0, screenH - h));
        return new float[]{x, y, w, h};
    }

    // ===== HUD 事件 =====

    /** 游戏内 HUD：战斗模式下渲染伤势面板 */
    @SubscribeEvent
    public static void onHudRender(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (!CombatModeClient.combatMode()) return;
        GuiGraphics g = event.getGuiGraphics();
        int maxHp = ClientHealthData.maxHp(mc.player.getMaxHealth());
        float open = ZsAnim.easeOutCubic((ZsAnim.nowMs() - CombatModeClient.combatSince()) / 320f);
        render(g, mc.font, g.guiWidth(), g.guiHeight(), maxHp,
                ClientHealthData.b(), ClientHealthData.l(), ClientHealthData.a(), open);
    }

    /** 界面设置预览：使用真实数据（无伤势时给一组示例值） */
    public static void renderPreview(GuiGraphics g, Font font, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        int maxHp = ClientHealthData.maxHp(mc.player != null ? mc.player.getMaxHealth() : 20f);
        if (ClientHealthData.hasWounds()) {
            render(g, font, screenW, screenH, maxHp,
                    ClientHealthData.b(), ClientHealthData.l(), ClientHealthData.a(), 1f);
        } else {
            render(g, font, screenW, screenH, maxHp, 4, 2, 1, 1f);
        }
    }

    // ===== 绘制 =====

    public static void render(GuiGraphics g, Font font, int screenW, int screenH,
                              int maxHp, int b, int l, int a, float open) {
        float scale = ClientUiConfig.get().woundScale;
        float[] pos = layout(screenW, screenH, scale);
        int total = b + l + a;
        int intact = Math.max(0, maxHp - total);
        boolean unconscious = intact <= 0 && l > 0;
        boolean danger = !unconscious && maxHp > 0 && intact <= Math.max(1, maxHp / 4);
        long now = ZsAnim.nowMs();

        // 数值变化闪光：受伤 → 红，治疗 → 白
        if (lastIntact == Integer.MIN_VALUE) lastIntact = intact;
        if (intact != lastIntact) {
            lastDir = intact < lastIntact ? -1 : 1;
            lastIntact = intact;
            lastChangeAt = now;
        }
        float flash = 1 - ZsAnim.clamp01((now - lastChangeAt) / 450f);

        // 昏迷：全局抖动
        float shakeX = 0, shakeY = 0;
        if (unconscious) {
            shakeX = (float) Math.sin(now / 23.0) * 1.2f;
            shakeY = (float) Math.cos(now / 31.0) * 0.8f;
        }

        g.pose().pushPose();
        g.pose().translate(pos[0] + shakeX, pos[1] + shakeY, 0);
        g.pose().scale(scale, scale, 1f);

        int accent = unconscious ? WARN_RED : danger ? WARN_YELLOW : CYAN;
        int accentDim = ZsAnim.withAlpha(accent, 0.45f);

        // 展开遮罩：由左向右扫描出现
        int reveal = Math.round(W * open);
        // 用剪裁做扫描展开（剪裁需要屏幕坐标：把缩放/位移考虑进去）
        int sx0 = Math.round(pos[0] + shakeX), sy0 = Math.round(pos[1] + shakeY);
        g.enableScissor(sx0, sy0, sx0 + Math.round(reveal * scale) + 1, sy0 + Math.round(H * scale) + 1);

        // --- 底板：斜切角面板 ---
        panel(g, 0, 0, W, H, PANEL, accentDim);
        // 扫描线（每 3px 一条暗线，整体缓慢下移）
        int scan = (int) ((now / 40) % 3);
        for (int y = scan; y < H; y += 3) {
            g.fill(1, y, W - 1, y + 1, 0x14000000);
        }
        // 移动亮带
        float sweep = ZsAnim.phase(2600);
        int sweepY = Math.round(sweep * (H + 8)) - 4;
        g.fill(1, Math.max(1, sweepY), W - 1, Math.min(H - 1, sweepY + 2), ZsAnim.withAlpha(accent, 0.08f));

        // --- 顶行：标题 + 完好生命值 ---
        String title = Component.translatable("hud.zhushenspace.wound.title").getString();
        g.pose().pushPose();
        g.pose().translate(6, 4, 0);
        g.pose().scale(0.7f, 0.7f, 1f);
        g.drawString(font, title, 0, 0, ZsAnim.withAlpha(accent, 0.85f), false);
        g.pose().popPose();
        // 角落刻度
        ticks(g, accent);

        // 平滑滚动的完好值（大号数字）
        float shown = ZsAnim.tween(ZsAnim.key(77, 1, 1), intact, 10f);
        String big = String.valueOf(Math.round(shown));
        String cap = "/" + maxHp;
        int numColor = TEXT;
        if (flash > 0) numColor = ZsAnim.lerpColor(TEXT, lastDir < 0 ? WARN_RED : 0xFFFFFFFF, flash);
        if (unconscious) numColor = ZsAnim.lerpColor(WARN_RED, TEXT, ZsAnim.pulse(500));
        g.pose().pushPose();
        g.pose().translate(W - 6 - font.width(cap) - font.width(big) * 1.6f, 3, 0);
        g.pose().scale(1.6f, 1.6f, 1f);
        g.drawString(font, big, 0, 0, numColor, true);
        g.pose().popPose();
        g.drawString(font, cap, W - 6 - font.width(cap), 9, TEXT_SUB, true);
        // 顶行分隔线（带流动短亮段）
        g.fill(6, 19, W - 6, 20, ZsAnim.withAlpha(accent, 0.35f));
        int dash = 6 + Math.round(ZsAnim.phase(1800) * (W - 12 - 14));
        g.fill(dash, 19, dash + 14, 20, accent);

        // --- 三条伤势条 ---
        int barX = 16, barW = W - 16 - 22, rowY = 24, rowH = 7, rowGap = 3;
        woundRow(g, font, "B", b, maxHp, COL_B, barX, rowY, barW, rowH, 1);
        woundRow(g, font, "L", l, maxHp, COL_L, barX, rowY + rowH + rowGap, barW, rowH, 2);
        woundRow(g, font, "A", a, maxHp, COL_A, barX, rowY + 2 * (rowH + rowGap), barW, rowH, 3);

        // --- 右侧竖排合计条：完好 | B | L | A ---
        int colX = W - 16, colY = 24, colW = 8, colH = 3 * rowH + 2 * rowGap;
        g.fill(colX - 1, colY - 1, colX + colW + 1, colY + colH + 1, ZsAnim.withAlpha(accent, 0.35f));
        g.fill(colX, colY, colX + colW, colY + colH, 0xAA04090E);
        if (maxHp > 0) {
            float unit = colH / (float) maxHp;
            int cy = colY;
            int hA = Math.round(Math.min(a, maxHp) * unit);
            int hL = Math.round(Math.min(l, Math.max(0, maxHp - a)) * unit);
            int hB = Math.round(Math.min(b, Math.max(0, maxHp - a - l)) * unit);
            int hI = Math.max(0, colH - hA - hL - hB);
            // 自上而下：完好（青） → B → L → A（底部最重）
            if (hI > 0) g.fill(colX, cy, colX + colW, cy + hI, ZsAnim.withAlpha(accent, 0.55f));
            cy += hI;
            if (hB > 0) g.fill(colX, cy, colX + colW, cy + hB, COL_B);
            cy += hB;
            if (hL > 0) g.fill(colX, cy, colX + colW, cy + hL, COL_L);
            cy += hL;
            if (hA > 0) g.fill(colX, cy, colX + colW, cy + hA, COL_A);
            // 昏迷阈值线：完好归零处
            g.fill(colX - 2, colY + hI, colX + colW + 2, colY + hI + 1, ZsAnim.withAlpha(0xFFFFFFFF, 0.7f));
        }

        // --- 底行：状态文字 ---
        String status;
        int statusColor;
        if (unconscious) {
            status = Component.translatable("hud.zhushenspace.wound.unconscious").getString();
            statusColor = ZsAnim.lerpColor(WARN_RED, 0xFFFFFFFF, ZsAnim.pulse(400));
        } else if (danger) {
            status = Component.translatable("hud.zhushenspace.wound.critical").getString();
            statusColor = ZsAnim.lerpColor(WARN_YELLOW, TEXT, ZsAnim.pulse(900));
        } else if (total > 0) {
            status = Component.translatable("hud.zhushenspace.wound.wounded", total).getString();
            statusColor = TEXT_SUB;
        } else {
            status = Component.translatable("hud.zhushenspace.wound.ok").getString();
            statusColor = ZsAnim.withAlpha(accent, 0.8f);
        }
        g.pose().pushPose();
        g.pose().translate(6, H - 9, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, status, 0, 0, statusColor, true);
        g.pose().popPose();

        // 昏迷：红色警告边框呼吸 + 全屏内框闪
        if (unconscious) {
            float p = ZsAnim.pulse(500);
            g.fill(0, 0, W, H, ZsAnim.withAlpha(WARN_RED, 0.10f + 0.12f * p));
            outline(g, -1, -1, W + 2, H + 2, ZsAnim.withAlpha(WARN_RED, 0.5f + 0.5f * p));
        } else if (flash > 0 && lastDir < 0) {
            g.fill(0, 0, W, H, ZsAnim.withAlpha(WARN_RED, 0.18f * flash));
        }

        g.disableScissor();
        // 扫描前沿亮线
        if (open < 1f) {
            g.fill(reveal, 0, reveal + 1, H, accent);
        }
        g.pose().popPose();
    }

    /** 一条伤势行：左侧字母标签，中间条形（流光 + 前沿亮线），右侧数值 */
    private static void woundRow(GuiGraphics g, Font font, String label, int value, int maxHp, int color,
                                 int x, int y, int w, int h, int idx) {
        // 标签
        g.pose().pushPose();
        g.pose().translate(x - 10, y - 0.5f, 0);
        g.pose().scale(0.85f, 0.85f, 1f);
        g.drawString(font, label, 0, 0, value > 0 ? color : CYAN_DIM, true);
        g.pose().popPose();

        // 槽
        g.fill(x, y, x + w, y + h, 0xAA04090E);
        g.fill(x, y + h - 1, x + w, y + h, ZsAnim.withAlpha(color, 0.25f));
        // 刻度（每 1/4）
        for (int i = 1; i < 4; i++) {
            int tx = x + w * i / 4;
            g.fill(tx, y, tx + 1, y + h, 0x2A4FE3FF);
        }
        // 填充（平滑）
        float frac = maxHp > 0 ? Math.min(1f, value / (float) maxHp) : 0f;
        float shown = ZsAnim.tween(ZsAnim.key(78, idx, 0), frac, 9f);
        int fw = Math.round(w * shown);
        if (fw > 0) {
            g.fill(x, y + 1, x + fw, y + h - 1, ZsAnim.withAlpha(color, 0.75f));
            // 流光
            float ph = ZsAnim.phase(1400 + idx * 200L);
            int gx = x + Math.round(ph * (fw + 6)) - 6;
            int gx0 = Math.max(x, gx), gx1 = Math.min(x + fw, gx + 6);
            if (gx1 > gx0) g.fill(gx0, y + 1, gx1, y + h - 1, ZsAnim.withAlpha(0xFFFFFFFF, 0.28f));
            // 前沿
            g.fill(x + fw - 1, y, x + fw, y + h, 0xFFFFFFFF);
        }
        // 数值（右对齐到条末）
        if (value > 0) {
            String s = String.valueOf(value);
            g.pose().pushPose();
            g.pose().translate(x + w - font.width(s) * 0.8f - 1, y, 0);
            g.pose().scale(0.8f, 0.8f, 1f);
            g.drawString(font, s, 0, 0, TEXT, true);
            g.pose().popPose();
        }
    }

    /** 斜切角面板：底色 + 描边（右上、左下切角） */
    private static void panel(GuiGraphics g, int x, int y, int w, int h, int bg, int line) {
        int c = 6; // 切角尺寸
        // 主体（切掉右上与左下角）
        g.fill(x, y, x + w - c, y + h - c, bg);
        g.fill(x + c, y + h - c, x + w, y + h, bg);
        g.fill(x + w - c, y + c, x + w, y + h - c, bg);
        // 斜边填充
        for (int i = 0; i < c; i++) {
            g.fill(x + w - c + i, y + c - i, x + w - c + i + 1, y + c, bg);
            g.fill(x + i, y + h - c + i, x + i + 1, y + h - c + i + 1, bg);
            g.fill(x + i + 1, y + h - c + i, x + c, y + h - c + i + 1, bg);
        }
        // 描边
        g.fill(x, y, x + w - c, y + 1, line);                 // 上
        g.fill(x + w - 1, y + c, x + w, y + h, line);         // 右
        g.fill(x + c, y + h - 1, x + w, y + h, line);         // 下
        g.fill(x, y, x + 1, y + h - c, line);                 // 左
        for (int i = 0; i < c; i++) {
            g.fill(x + w - c + i, y + c - i - 1, x + w - c + i + 1, y + c - i, line);
            g.fill(x + i, y + h - c + i, x + i + 1, y + h - c + i + 1, line);
        }
        // 角落加粗亮块
        g.fill(x, y, x + 10, y + 2, line);
        g.fill(x, y, x + 2, y + 10, line);
        g.fill(x + w - 10, y + h - 2, x + w, y + h, line);
        g.fill(x + w - 2, y + h - 10, x + w, y + h, line);
    }

    /** 顶边右侧小刻度（装饰） */
    private static void ticks(GuiGraphics g, int accent) {
        int c = ZsAnim.withAlpha(accent, 0.5f);
        for (int i = 0; i < 6; i++) {
            int tx = W - 40 + i * 4;
            g.fill(tx, 1, tx + 1, i % 2 == 0 ? 4 : 3, c);
        }
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }
}

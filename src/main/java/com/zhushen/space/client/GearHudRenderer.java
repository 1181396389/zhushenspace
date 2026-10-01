package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.common.AdaptationManager;
import com.zhushen.space.data.GearSlot;
import com.zhushen.space.data.PlayerGearData;
import com.zhushen.space.item.MahoragaWheelItem;
import com.zhushen.space.network.SyncAdaptPayload;
import com.zhushen.space.screen.ZsAnim;
import com.zhushen.space.screen.ZsShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 装备 HUD（位置由 {@link HudLayout} 排进右侧栏，可能整栏等比缩小）：
 * <ul>
 *   <li>魔虚罗法阵（仅战斗模式且头戴法阵时）：紧凑卡片——标题行小法轮随适应转动；每种现象一行：
 *       名称、八格转动刻度（转满一圈 = 完全适应）、当前降低比例；最近遇到的排在最上面，
 *       即将遗忘时行下出现倒计时细线，遗忘中刻度闪烁倒退。最多显示 {@link #MAX_ROWS} 行，其余合并为「还有 N 种」。</li>
 *   <li>穿脱进度：穿戴中 / 解下中的装备一行一个（环形进度 + 物品图标 + 名称 + 剩余秒数），已解开的装备提示可以取下。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class GearHudRenderer {
    private GearHudRenderer() {}

    private static final int GOLD = 0xFFE8C77E, GOLD_DARK = 0xFF9A6A22, GOLD_HOT = 0xFFFFF1C2;
    private static final int INK = 0xFF1A1208, PANEL_TOP = 0xD0140F0A, PANEL_BOTTOM = 0xC0201810;
    private static final int TEXT = 0xFFF3EBDD, SUB = 0xFFB7A98F, DIM = 0xFF6E6252, CYAN = 0xFF8FE3FF, FADE_RED = 0xFFE58A6A;

    public static final int ADAPT_W = 124, GEAR_W = 124;
    private static final int HEAD_H = 17, ROW_H = 11, MORE_H = 9, PAD_B = 3;
    public static final int MAX_ROWS = 4;
    private static final int GEAR_ROW = 16, GEAR_GAP = 2;
    /** 遗忘倒计时细线：最后 15 秒出现 */
    private static final long GRACE_WARN_MS = 15000;

    // ===== 尺寸（HudLayout 排版用） =====

    private static boolean adaptDonning() {
        ClientGearData.View head = ClientGearData.get(GearSlot.vanillaKey(EquipmentSlot.HEAD));
        return head != null && head.state() != PlayerGearData.WORN;
    }

    public static int adaptHeight() {
        int n = ClientAdaptData.entries().size();
        int rows = adaptDonning() ? 1 : Math.max(1, Math.min(MAX_ROWS, n));
        return HEAD_H + 1 + rows * ROW_H + (!adaptDonning() && n > MAX_ROWS ? MORE_H : 0) + PAD_B;
    }

    private static List<ClientGearData.View> gearRows() {
        List<ClientGearData.View> rows = new ArrayList<>();
        for (ClientGearData.View v : ClientGearData.all()) if (v.state() != PlayerGearData.WORN) rows.add(v);
        return rows;
    }

    public static int gearHeight() {
        int n = gearRows().size();
        return n == 0 ? 0 : n * (GEAR_ROW + GEAR_GAP) - GEAR_GAP;
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        GuiGraphics g = e.getGuiGraphics();
        HudLayout.ensure(g.guiWidth(), g.guiHeight());
        Font font = mc.font;
        if (HudLayout.adapt != null) {
            float[] r = HudLayout.adapt;
            float in = ZsAnim.easeOutCubic(ZsAnim.clamp01((ZsAnim.nowMs() - CombatModeClient.combatSince()) / 300f));
            g.pose().pushPose();
            g.pose().translate(r[0] + (1 - in) * 24, r[1], 0);
            g.pose().scale(HudLayout.columnScale, HudLayout.columnScale, 1f);
            renderAdaptPanel(g, font, mc.player.getId());
            g.pose().popPose();
        }
        if (HudLayout.gear != null) {
            float[] r = HudLayout.gear;
            g.pose().pushPose();
            g.pose().translate(r[0], r[1], 0);
            g.pose().scale(HudLayout.columnScale, HudLayout.columnScale, 1f);
            renderGearList(g, font);
            g.pose().popPose();
        }
    }

    // ===== 穿脱进度 =====

    private static void renderGearList(GuiGraphics g, Font font) {
        long now = ZsAnim.nowMs();
        int y = 0, rw = GEAR_W;
        for (ClientGearData.View v : gearRows()) {
            ZsShapes.roundRect(g, 0, y, rw, GEAR_ROW, 4, PANEL_TOP, PANEL_BOTTOM);
            ItemStack st = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(v.item())));
            float cx = 9, cy = y + 8;
            boolean loose = v.state() == PlayerGearData.LOOSE;
            boolean donning = v.state() == PlayerGearData.DONNING;
            int col = loose ? CYAN : donning ? GOLD : 0xFFFF9C7A;
            // 环形进度：穿戴中逐渐填满，解下中逐渐清空
            ZsShapes.ring(g, cx, cy, 6.3f, 7.3f, 0x40FFFFFF, 0x40FFFFFF);
            float p = loose ? 1f : v.progress(now);
            float shown = donning || loose ? p : 1f - p;
            if (shown > 0) ZsShapes.arc(g, cx, cy, 6.1f, 7.5f, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * shown, col, col, col, col, true);
            g.pose().pushPose();
            g.pose().translate(cx - 5, cy - 5, 0);
            g.pose().scale(0.625f, 0.625f, 1f);
            g.renderItem(st, 0, 0);
            g.pose().popPose();
            String right = loose ? Component.translatable("hud.zhushenspace.gear.take_off").getString()
                    : Component.translatable("hud.zhushenspace.gear.seconds", v.secondsLeft(now)).getString();
            int rwid = font.width(right);
            int rx = rw - 4 - rwid;
            g.drawString(font, right, rx, y + 4, col, true);
            if (!loose) { // ▲ 穿戴 / ▼ 解下
                float tx = rx - 6, ty = y + 8;
                if (donning) ZsShapes.tri(g, tx - 2.5f, ty + 1.8f, col, tx, ty - 2.2f, col, tx + 2.5f, ty + 1.8f, col);
                else ZsShapes.tri(g, tx - 2.5f, ty - 1.8f, col, tx + 2.5f, ty - 1.8f, col, tx, ty + 2.2f, col);
                rx -= 10;
            }
            String name = trim(font, st.getHoverName().getString(), rx - 19 - 3);
            g.drawString(font, name, 19, y + 4, TEXT, true);
            y += GEAR_ROW + GEAR_GAP;
        }
    }

    // ===== 魔虚罗法阵 =====

    private static void renderAdaptPanel(GuiGraphics g, Font font, int selfId) {
        long now = ZsAnim.nowMs();
        List<SyncAdaptPayload.Entry> list = ClientAdaptData.entries();
        ClientGearData.View head = ClientGearData.get(GearSlot.vanillaKey(EquipmentSlot.HEAD));
        boolean donning = head != null && head.state() != PlayerGearData.WORN;
        int W = ADAPT_W, H = adaptHeight();

        float flash = 1f - ZsAnim.clamp01((now - ClientAdaptData.selfTurnMs()) / 1000f);
        ZsShapes.roundRect(g, 0, 0, W, H, 4, PANEL_TOP, PANEL_BOTTOM);
        ZsShapes.roundRectOutline(g, 0, 0, W, H, 4, 0.8f, ZsShapes.lerp(0x70E8C77E, 0xFFFFF1C2, flash));

        // 标题行：小法轮 + 「适应」 + 现象数 + B 级徽记
        float wx = 10, wy = 8.5f;
        if (flash > 0) ZsShapes.glow(g, wx, wy, 6f, 6f * flash + 1.5f, ZsShapes.fade(GOLD_HOT, 0.6f * flash));
        drawWheel(g, wx, wy, 5.6f, ClientAdaptData.turns(selfId) * 45f, donning ? 0.45f : 1f);
        if (donning && head != null) {
            float p = head.progress(now);
            ZsShapes.arc(g, wx, wy, 7.6f, 8.6f, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * p, CYAN, CYAN, CYAN, CYAN, true);
        }
        g.drawString(font, Component.translatable("hud.zhushenspace.adapt.short"), 21, 5, GOLD, true);
        ZsShapes.roundRect(g, W - 14, 4, 10, 9, 2, 0xFFE8C77E, 0xFFB58A3A);
        g.drawString(font, "B", W - 11, 5, INK, false);
        if (!donning && !list.isEmpty()) {
            String cnt = Component.translatable("hud.zhushenspace.adapt.count", list.size()).getString();
            g.drawString(font, cnt, W - 18 - font.width(cnt), 5, SUB, false);
        }
        ZsShapes.line(g, 4, HEAD_H, W - 4, HEAD_H, 0.8f, 0x90E8C77E, 0x10E8C77E);

        int ry = HEAD_H + 1;
        if (donning && head != null) {
            String s = Component.translatable("hud.zhushenspace.adapt.donning", head.secondsLeft(now)).getString();
            g.drawString(font, trim(font, s, W - 8), 4, ry + 2, CYAN, false);
            return;
        }
        if (list.isEmpty()) {
            g.drawString(font, trim(font, Component.translatable("hud.zhushenspace.adapt.none").getString(), W - 8), 4, ry + 2, SUB, false);
            return;
        }
        final int pip = 3, pipGap = 1, pips = AdaptationManager.MAX_TURNS;
        final int pctW = 17;
        int pipsW = pips * pip + (pips - 1) * pipGap;
        int pipX = W - 4 - pctW - 3 - pipsW;
        boolean blink = (now / 260) % 2 == 0;
        for (int i = 0; i < Math.min(MAX_ROWS, list.size()); i++) {
            SyncAdaptPayload.Entry en = list.get(i);
            long grace = ClientAdaptData.graceLeftMs(en);
            boolean decaying = en.grace() <= 0 || grace <= 0;
            boolean full = en.turns() >= pips;
            float rf = ClientAdaptData.rowFlash(en);
            if (rf > 0) ZsShapes.roundRect(g, 2, ry, W - 4, ROW_H, 2, ZsShapes.fade(GOLD_HOT, 0.28f * rf), ZsShapes.fade(GOLD, 0.12f * rf));
            else if (full) ZsShapes.roundRect(g, 2, ry, W - 4, ROW_H, 2, 0x22FFE9A8, 0x10FFE9A8);

            int lc = decaying ? DIM : full ? GOLD_HOT : TEXT;
            String label = trim(font, AdaptationManager.label(en.label()).getString(), pipX - 4 - 3);
            g.drawString(font, label, 4, ry + 2, lc, !decaying);

            // 八格刻度
            int py = ry + 3;
            for (int k = 0; k < pips; k++) {
                int px = pipX + k * (pip + pipGap);
                g.fill(px, py, px + pip, py + 5, 0x38FFFFFF);
                if (k < en.turns()) {
                    boolean last = k == en.turns() - 1;
                    if (decaying && last && !blink) continue; // 遗忘中：最后一格闪烁
                    int top = full ? GOLD_HOT : GOLD, bot = full ? GOLD : GOLD_DARK;
                    ZsShapes.quad(g, px, py, top, px, py + 5, bot, px + pip, py + 5, bot, px + pip, py, top);
                } else if (k == en.turns() && !decaying) {
                    int fh = Math.round(5 * ZsAnim.clamp01(en.progress()));
                    if (fh > 0) g.fill(px, py + 5 - fh, px + pip, py + 5, en.progress() >= 1f ? 0xFFFFF1C2 : 0xA0E8C77E);
                }
            }
            if (full && !decaying) { // 完全适应：刻度上掠过一道高光
                float sweep = (now % 1600) / 1600f;
                float sx = pipX - 4 + (pipsW + 8) * sweep;
                ZsShapes.quad(g, sx, py, 0x00FFFFFF, sx + 3, py, 0x90FFFFFF, sx + 2, py + 5, 0x90FFFFFF, sx - 1, py + 5, 0x00FFFFFF);
            }
            // 降低比例（小号数字）
            int pct = (int) Math.round(en.turns() * AdaptationManager.PER_TURN * 100);
            String ps = pct + "%";
            int pc = decaying ? FADE_RED : full ? GOLD_HOT : pct > 0 ? GOLD : DIM;
            g.pose().pushPose();
            g.pose().translate(W - 4 - font.width(ps) * 0.75f, ry + 3, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.drawString(font, ps, 0, 0, pc, true);
            g.pose().popPose();
            // 即将遗忘：倒计时细线
            if (!decaying && grace < GRACE_WARN_MS) {
                float f = grace / (float) GRACE_WARN_MS;
                ZsShapes.line(g, 4, ry + ROW_H - 0.5f, 4 + (W - 8) * f, ry + ROW_H - 0.5f, 0.7f, 0xA0E58A6A, 0x30E58A6A);
            }
            ry += ROW_H;
        }
        if (list.size() > MAX_ROWS) {
            String more = Component.translatable("hud.zhushenspace.adapt.more", list.size() - MAX_ROWS).getString();
            g.pose().pushPose();
            g.pose().translate(W - 4 - font.width(more) * 0.75f, ry + 1.5f, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.drawString(font, more, 0, 0, SUB, false);
            g.pose().popPose();
        }
    }

    /** 八柄法轮：外轮 + 八根轮辐（伸出轮外的握柄） + 轮毂 */
    static void drawWheel(GuiGraphics g, float cx, float cy, float r, float angleDeg, float alpha) {
        int gold = ZsShapes.fade(GOLD, alpha), dark = ZsShapes.fade(GOLD_DARK, alpha), ink = ZsShapes.fade(INK, alpha * 0.9f);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(angleDeg + i * 45 - 90);
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            ZsShapes.line(g, cx + c * r * 0.2f, cy + s * r * 0.2f, cx + c * r * 1.12f, cy + s * r * 1.12f, Math.max(1.2f, r * 0.2f), ink, ink);
            ZsShapes.line(g, cx + c * r * 0.2f, cy + s * r * 0.2f, cx + c * r * 1.1f, cy + s * r * 1.1f, Math.max(0.7f, r * 0.108f), gold, dark);
            ZsShapes.disc(g, cx + c * r * 1.18f, cy + s * r * 1.18f, r * 0.13f, gold, dark);
        }
        ZsShapes.ring(g, cx, cy, r * 0.68f, r * 0.92f, ink, ink);
        ZsShapes.ring(g, cx, cy, r * 0.72f, r * 0.88f, gold, dark);
        ZsShapes.disc(g, cx, cy, r * 0.26f, gold, dark);
        ZsShapes.disc(g, cx, cy, r * 0.11f, ink);
    }

    private static String trim(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        String ell = "…";
        while (!s.isEmpty() && font.width(s + ell) > maxW) s = s.substring(0, s.length() - 1);
        return s + ell;
    }
}

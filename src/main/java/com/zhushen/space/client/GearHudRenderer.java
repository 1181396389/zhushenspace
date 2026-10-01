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
 * 装备 HUD（右侧，不遮挡准星）：
 * <ul>
 *   <li>穿脱进度：穿戴中 / 解下中的装备（物品图标 + 环形进度 + 剩余秒数），已解开的装备提示可以取下。</li>
 *   <li>魔虚罗法阵（仅战斗模式且头戴法阵时）：左侧八柄法轮随适应转动（回弹 + 金色闪光），
 *       右侧每种现象一行——名称、四格转动刻度、下一格的累积进度与当前降低比例。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class GearHudRenderer {
    private GearHudRenderer() {}

    private static final int GOLD = 0xFFE8C77E, GOLD_DARK = 0xFF9A6A22, GOLD_HOT = 0xFFFFF1C2;
    private static final int INK = 0xFF1A1208, PANEL_TOP = 0xD0140F0A, PANEL_BOTTOM = 0xC0201810;
    private static final int TEXT = 0xFFF3EBDD, SUB = 0xFFB7A98F, DIM = 0xFF6E6252, CYAN = 0xFF8FE3FF;
    private static final int PW = 150;

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        GuiGraphics g = e.getGuiGraphics();
        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        boolean combat = CombatModeClient.combatMode();
        // 战斗模式下右下角是竖排迷你物品栏（约 h-173 以下）
        int bottom = combat ? h - 180 : h - 6;

        if (combat && mc.player.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof MahoragaWheelItem) {
            int top = renderAdaptPanel(g, font, w, bottom, mc.player.getId());
            bottom = top - 4;
        }
        renderGearList(g, font, w, bottom);
    }

    // ===== 穿脱进度 =====

    private static void renderGearList(GuiGraphics g, Font font, int w, int bottom) {
        long now = ZsAnim.nowMs();
        List<ClientGearData.View> rows = new ArrayList<>();
        for (ClientGearData.View v : ClientGearData.all()) {
            if (v.state() != PlayerGearData.WORN) rows.add(v);
        }
        if (rows.isEmpty()) return;
        int rowH = 20, rw = 132;
        int y = bottom - rows.size() * (rowH + 2);
        for (ClientGearData.View v : rows) {
            int x = w - rw - 4;
            ZsShapes.roundRect(g, x, y, rw, rowH, 4, PANEL_TOP, PANEL_BOTTOM);
            ItemStack st = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(v.item())));
            float cx = x + 11, cy = y + 10;
            boolean loose = v.state() == PlayerGearData.LOOSE;
            boolean donning = v.state() == PlayerGearData.DONNING;
            int col = loose ? CYAN : donning ? GOLD : 0xFFFF9C7A;
            // 环形进度
            ZsShapes.ring(g, cx, cy, 8.4f, 9.6f, 0x50FFFFFF, 0x50FFFFFF);
            float p = loose ? 1f : v.progress(now);
            if (p > 0) ZsShapes.arc(g, cx, cy, 8.2f, 9.8f, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * p, col, col, col, col, true);
            g.pose().pushPose();
            g.pose().translate(cx - 6, cy - 6, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.renderItem(st, 0, 0);
            g.pose().popPose();
            GearSlot gs = GearSlot.ofKey(v.key());
            String state = Component.translatable(loose ? "hud.zhushenspace.gear.loose"
                    : donning ? "hud.zhushenspace.gear.donning" : "hud.zhushenspace.gear.doffing").getString();
            String name = trim(font, st.getHoverName().getString(), rw - 30 - font.width(state) - 4);
            g.drawString(font, name, x + 24, y + 2, TEXT, true);
            g.drawString(font, state, x + rw - 4 - font.width(state), y + 2, col, true);
            String sub = gs == null ? "" : Component.translatable(gs.nameKey()).getString();
            String right = loose ? Component.translatable("hud.zhushenspace.gear.take_off").getString()
                    : Component.translatable("hud.zhushenspace.gear.seconds", v.secondsLeft(now)).getString();
            g.drawString(font, sub, x + 24, y + 11, SUB, false);
            g.drawString(font, right, x + rw - 4 - font.width(right), y + 11, loose ? CYAN : SUB, false);
            y += rowH + 2;
        }
    }

    // ===== 魔虚罗法阵 =====

    /** 返回面板顶部 y */
    private static int renderAdaptPanel(GuiGraphics g, Font font, int w, int bottom, int selfId) {
        long now = ZsAnim.nowMs();
        List<SyncAdaptPayload.Entry> list = ClientAdaptData.entries();
        ClientGearData.View head = ClientGearData.get(GearSlot.vanillaKey(EquipmentSlot.HEAD));
        boolean ready = head == null || head.state() == PlayerGearData.WORN;
        int rows = Math.max(1, list.size());
        int rowH = 19;
        int ph = 18 + rows * rowH + 4;
        ph = Math.max(ph, 46);
        int x = w - PW - 4, y = bottom - ph;
        float in = ZsAnim.easeOutCubic(ZsAnim.clamp01((now - CombatModeClient.combatSince()) / 300f));
        x += (int) ((1 - in) * 40);

        float flash = 1f - ZsAnim.clamp01((now - ClientAdaptData.selfTurnMs()) / 1000f);
        ZsShapes.roundRect(g, x, y, PW, ph, 5, PANEL_TOP, PANEL_BOTTOM);
        ZsShapes.roundRectOutline(g, x, y, PW, ph, 5, 1f, ZsShapes.lerp(0x80E8C77E, 0xFFFFF1C2, flash));
        // 金色斜纹装饰
        ZsShapes.line(g, x + 40, y + 15, x + PW - 6, y + 15, 1f, 0x90E8C77E, 0x10E8C77E);

        // 法轮
        float wx = x + 21, wy = y + 23;
        float ang = ClientAdaptData.turns(selfId) * 45f;
        if (flash > 0) ZsShapes.glow(g, wx, wy, 13f, 9f * flash + 2f, ZsShapes.fade(GOLD_HOT, 0.65f * flash));
        drawWheel(g, wx, wy, 13f, ang, ready ? 1f : 0.45f);
        if (!ready && head != null) {
            float p = head.progress(now);
            ZsShapes.arc(g, wx, wy, 16.5f, 18f, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * p, CYAN, CYAN, CYAN, CYAN, true);
        }

        // 标题
        g.drawString(font, Component.translatable("hud.zhushenspace.adapt.title"), x + 42, y + 4, GOLD, true);
        String rank = "B";
        ZsShapes.roundRect(g, x + PW - 17, y + 3, 12, 10, 2, 0xFFE8C77E, 0xFFB58A3A);
        g.drawString(font, rank, x + PW - 14, y + 4, INK, false);

        int ry = y + 19;
        int tx = x + 42, tw = PW - 46;
        if (!ready && head != null) {
            String s = Component.translatable("hud.zhushenspace.adapt.donning", head.secondsLeft(now)).getString();
            g.drawString(font, trim(font, s, tw), tx, ry + 2, CYAN, false);
            return y;
        }
        if (list.isEmpty()) {
            for (var line : font.split(Component.translatable("hud.zhushenspace.adapt.none"), tw)) {
                g.drawString(font, line, tx, ry + 2, SUB, false);
                ry += 10;
            }
            return y;
        }
        for (SyncAdaptPayload.Entry en : list) {
            String label = trim(font, AdaptationManager.label(en.label()).getString(), tw - 26);
            g.drawString(font, label, tx, ry, TEXT, true);
            int pct = (int) Math.round(en.turns() * AdaptationManager.PER_TURN * 100);
            String ps = pct > 0 ? "-" + pct + "%" : "0%";
            boolean max = en.turns() >= AdaptationManager.MAX_TURNS;
            g.drawString(font, ps, x + PW - 5 - font.width(ps), ry, max ? GOLD_HOT : pct > 0 ? GOLD : DIM, true);
            // 四格刻度 + 下一格进度
            int by = ry + 11;
            int cell = (tw - 3 * 2) / AdaptationManager.MAX_TURNS;
            for (int i = 0; i < AdaptationManager.MAX_TURNS; i++) {
                int cx = tx + i * (cell + 2);
                g.fill(cx, by, cx + cell, by + 4, 0x50FFFFFF);
                if (i < en.turns()) {
                    ZsShapes.quad(g, cx, by, GOLD_HOT, cx, by + 4, GOLD_DARK, cx + cell, by + 4, GOLD_DARK, cx + cell, by, GOLD_HOT);
                } else if (i == en.turns()) {
                    int fw = (int) (cell * ZsAnim.clamp01(en.progress()));
                    if (fw > 0) g.fill(cx, by, cx + fw, by + 4, en.progress() >= 1f ? 0xFFFFF1C2 : 0xC0E8C77E);
                }
            }
            ry += rowH;
        }
        return y;
    }

    /** 八柄法轮：外轮 + 八根轮辐（伸出轮外的握柄） + 轮毂 */
    static void drawWheel(GuiGraphics g, float cx, float cy, float r, float angleDeg, float alpha) {
        int gold = ZsShapes.fade(GOLD, alpha), dark = ZsShapes.fade(GOLD_DARK, alpha), ink = ZsShapes.fade(INK, alpha * 0.9f);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(angleDeg + i * 45 - 90);
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            ZsShapes.line(g, cx + c * r * 0.2f, cy + s * r * 0.2f, cx + c * r * 1.12f, cy + s * r * 1.12f, 2.6f, ink, ink);
            ZsShapes.line(g, cx + c * r * 0.2f, cy + s * r * 0.2f, cx + c * r * 1.1f, cy + s * r * 1.1f, 1.4f, gold, dark);
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

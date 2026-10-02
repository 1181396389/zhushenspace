package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.network.SyncLoadPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.List;
import java.util.Locale;

/**
 * 负重（客户端）：同步数据、重度负重时禁止疾跑（本地预测）、背包界面下方的负重条、状态标签。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientLoad {
    private ClientLoad() {}

    /** 0 = 轻，1 = 中度，2 = 重度，3 = 超载 */
    private static float weight, light = 10, medium = 22, heavy = 34;
    private static boolean exempt = true, has;

    public static void update(SyncLoadPayload p) {
        weight = p.weight();
        light = p.light();
        medium = p.medium();
        heavy = p.heavy();
        exempt = p.exempt();
        has = true;
    }

    public static int tier() {
        if (!has || exempt) return 0;
        return weight > heavy ? 3 : weight > medium ? 2 : weight > light ? 1 : 0;
    }

    private static final int[] COLORS = {0xFF7FE0A0, 0xFFFFE070, 0xFFFF9A3C, 0xFFFF4040};
    private static final String[] KEYS = {"light", "medium", "heavy", "over"};

    static String fmt(float v) {
        return Math.abs(v - Math.round(v)) < 0.05f ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    /** HUD 状态标签：中度负重及以上 */
    static void chip(List<ClientCondition.Chip> out) {
        int t = tier();
        if (t == 0) return;
        String text = Component.translatable("hud.zhushenspace.load." + KEYS[t]).getString();
        float fill = heavy > 0 ? Math.min(1f, weight / heavy) : -1;
        float mark = heavy > 0 ? Math.min(1f, medium / heavy) : -1;
        out.add(new ClientCondition.Chip(text, COLORS[t], fill, mark, t == 3));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        has = false;
        exempt = true;
        weight = 0;
    }

    // ===== 重度负重：不能疾跑 =====

    @SubscribeEvent
    public static void onTickPre(ClientTickEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || tier() < 2) return;
        if (p.isSprinting()) p.setSprinting(false);
        mc.options.keySprint.setDown(false);
    }

    @SubscribeEvent
    public static void onTickPost(ClientTickEvent.Post e) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && tier() >= 2 && p.isSprinting()) p.setSprinting(false);
    }

    // ===== 背包界面：负重条 =====

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Render.Post e) {
        if (!(e.getScreen() instanceof InventoryScreen inv) || !has || exempt) return;
        renderBar(e.getGuiGraphics(), Minecraft.getInstance().font, inv.getGuiLeft(), inv.getGuiTop() + inv.getYSize() + 3,
                inv.getXSize(), e.getMouseX(), e.getMouseY());
    }

    private static void renderBar(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        int h = 20;
        if (y + h > g.guiHeight() - 1) y = g.guiHeight() - 1 - h;
        int t = tier();
        int col = COLORS[t];
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        // 底板
        g.fill(x + 1, y, x + w - 1, y + h, 0xD0101418);
        g.fill(x, y + 1, x + w, y + h - 1, 0xD0101418);
        g.fill(x + 1, y, x + w - 1, y + 1, 0x40FFFFFF);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, 0x60000000);
        // 文字：负重 12.3 / 19 kg · 轻负重
        String label = Component.translatable("screen.zhushenspace.load.label").getString();
        String nums = fmt(weight) + " / " + fmt(t == 0 ? light : t == 1 ? medium : heavy) + " kg";
        String tierName = Component.translatable("hud.zhushenspace.load." + KEYS[t]).getString();
        g.drawString(font, label, x + 5, y + 3, 0xFFB8C4CC, false);
        int lx = x + 5 + font.width(label) + 4;
        g.drawString(font, nums, lx, y + 3, 0xFFFFFFFF, false);
        g.drawString(font, tierName, x + w - 5 - font.width(tierName), y + 3, col, false);
        // 刻度条：0 ~ 重度上限（超载时满格脉动）；轻 / 中度阈值刻线
        int bx = x + 5, bw = w - 10, by = y + 13, bh = 4;
        g.fill(bx, by, bx + bw, by + bh, 0xFF2A3036);
        float top = Math.max(heavy, 0.001f);
        float f = Mth.clamp(weight / top, 0f, 1f);
        int fw = Math.round(bw * f);
        // 分段着色：轻（绿）→ 中（黄）→ 重（橙）
        int l1 = Math.round(bw * Mth.clamp(light / top, 0f, 1f)), l2 = Math.round(bw * Mth.clamp(medium / top, 0f, 1f));
        seg(g, bx, by, bh, 0, Math.min(fw, l1), COLORS[0]);
        seg(g, bx, by, bh, l1, Math.min(fw, l2), COLORS[1]);
        seg(g, bx, by, bh, l2, fw, COLORS[2]);
        if (t == 3) {
            float pulse = 0.5f + 0.5f * Mth.sin((System.currentTimeMillis() % 100000L) / 160f);
            int a = (int) (90 + 120 * pulse);
            g.fill(bx, by, bx + bw, by + bh, (a << 24) | (COLORS[3] & 0xFFFFFF));
        }
        g.fill(bx, by, bx + fw, by + 1, 0x50FFFFFF); // 高光
        g.fill(bx + l1, by - 1, bx + l1 + 1, by + bh + 1, 0xFFE8EEF2);
        g.fill(bx + l2, by - 1, bx + l2 + 1, by + bh + 1, 0xFFE8EEF2);
        g.pose().popPose();
        if (mx >= x && mx < x + w && my >= y && my < y + h) {
            List<Component> tip = List.of(
                    Component.translatable("screen.zhushenspace.load.tip_title").withStyle(s -> s.withColor(0xFFE0A0)),
                    Component.translatable("screen.zhushenspace.load.tip_limits", fmt(light), fmt(medium), fmt(heavy)),
                    Component.translatable("screen.zhushenspace.load.effect." + KEYS[t]).withStyle(s -> s.withColor(col & 0xFFFFFF)),
                    Component.translatable("screen.zhushenspace.load.tip_note").withStyle(s -> s.withColor(0x808890)));
            g.renderComponentTooltip(font, tip, mx, my);
        }
    }

    private static void seg(GuiGraphics g, int bx, int by, int bh, int from, int to, int color) {
        if (to > from) g.fill(bx + from, by, bx + to, by + bh, color);
    }
}

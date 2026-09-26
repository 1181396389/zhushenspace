package com.zhushen.space.screen;

import com.zhushen.space.client.ClientProgressData;
import com.zhushen.space.data.PlayerCurrencyData;
import com.zhushen.space.network.BranchExchangePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 主神空间货币界面：支线 S/A/B/C/D 五个槽位。
 *
 * 按住拖拽到相邻等级槽位即可兑换：
 * - 拖到高一级槽位：拼合（3 个低级 → 1 个高级）
 * - 拖到低一级槽位：拆解（1 个高级 → 3 个低级）
 * 从神面板底部货币栏点击进入。
 */
public class CurrencyScreen extends Screen {

    private static final int SLOT = 36;
    private static final int SLOT_GAP = 4;
    private static final int PANEL_W = 250;
    private static final int PANEL_H = 116;

    private static final int ACCENT = ZsTheme.ACCENT;
    private static final int GOLD = ZsTheme.CURRENCY;
    private static final int SLOT_BG_HELD = 0x663A5A7A;
    private static final int TEXT_SUB = ZsTheme.TEXT_SUB;

    private int panelX, panelY;
    private int slotY;
    /** 当前拖拽中的支线等级（-1 = 未拖拽） */
    private int pickTier = -1;
    private int dragX, dragY;

    private long openedAt = -1;

    public CurrencyScreen() {
        super(Component.translatable("screen.zhushenspace.currency.title"));
    }

    @Override
    protected void init() {
        if (openedAt < 0) openedAt = ZsAnim.nowMs();
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;
        slotY = panelY + 44;
    }

    private int slotX(int tier) {
        return panelX + (PANEL_W - 5 * SLOT - 4 * SLOT_GAP) / 2 + tier * (SLOT + SLOT_GAP);
    }

    private int slotAt(double mx, double my) {
        for (int t = 0; t < PlayerCurrencyData.TIER_COUNT; t++) {
            int x = slotX(t);
            if (mx >= x && mx < x + SLOT && my >= slotY && my < slotY + SLOT) return t;
        }
        return -1;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        ZsTheme.beginOpen(g, openedAt, panelX + PANEL_W / 2, panelY + PANEL_H / 2);
        ZsTheme.panel(g, panelX, panelY, PANEL_W, PANEL_H);

        g.drawCenteredString(font, title, panelX + PANEL_W / 2, panelY + 7, ACCENT);

        // 操作提示
        g.drawString(font, Component.translatable("screen.zhushenspace.currency.hint1"),
                panelX + 8, panelY + 21, TEXT_SUB, true);
        g.drawString(font, Component.translatable("screen.zhushenspace.currency.hint2"),
                panelX + 8, panelY + 31, TEXT_SUB, true);

        // 支线槽位
        for (int t = 0; t < PlayerCurrencyData.TIER_COUNT; t++) {
            int x = slotX(t);
            ZsTheme.slot(g, mouseX, mouseY, x, slotY, SLOT, ClientProgressData.branch(t) > 0);
            if (pickTier == t) g.fill(x + 1, slotY + 1, x + SLOT - 1, slotY + SLOT - 1, SLOT_BG_HELD);
            g.drawCenteredString(font, PlayerCurrencyData.tierLetter(t),
                    x + SLOT / 2, slotY + 4, GOLD);
            int count = ClientProgressData.branch(t);
            g.drawCenteredString(font, String.valueOf(count),
                    x + SLOT / 2, slotY + SLOT - 12, count > 0 ? 0xFFFFFFFF : 0xFF5A6A78);
        }

        // 拖拽中的支线跟随鼠标
        if (pickTier >= 0) {
            int x = dragX - 8, y = dragY - 8;
            g.fill(x, y, x + 16, y + 16, 0xCC3BA9E0);
            g.renderOutline(x, y, 16, 16, 0xFF7FC4F0);
            g.drawCenteredString(font, PlayerCurrencyData.tierLetter(pickTier),
                    x + 8, y + 4, 0xFFFFFFFF);
        }

        // 底部：经验与关闭提示
        g.drawString(font, Component.translatable("screen.zhushenspace.currency.xp", xpText()),
                panelX + 8, panelY + PANEL_H - 12, GOLD, true);
        g.drawString(font, Component.translatable("screen.zhushenspace.currency.back"),
                panelX + PANEL_W - font.width(Component.translatable("screen.zhushenspace.currency.back")) - 8,
                panelY + PANEL_H - 12, TEXT_SUB, true);
        ZsTheme.endOpen(g);
    }

    private String xpText() {
        if (this.minecraft != null && this.minecraft.player != null) {
            return String.valueOf(this.minecraft.player.experienceLevel);
        }
        return "0";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int tier = slotAt(mouseX, mouseY);
            if (tier >= 0 && ClientProgressData.branch(tier) > 0) {
                pickTier = tier;
                dragX = (int) mouseX;
                dragY = (int) mouseY;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (pickTier >= 0) {
            this.dragX = (int) mouseX;
            this.dragY = (int) mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && pickTier >= 0) {
            int target = slotAt(mouseX, mouseY);
            if (target >= 0 && target != pickTier) {
                PacketDistributor.sendToServer(new BranchExchangePayload(pickTier, target));
            }
            pickTier = -1;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new GodPanelScreen());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

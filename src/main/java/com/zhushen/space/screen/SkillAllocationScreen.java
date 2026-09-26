package com.zhushen.space.screen;

import com.zhushen.space.client.ClientSkillData;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.network.CommitSkillAllocationPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能加点界面（属性加点界面的下一页）。
 *
 * 规则：
 * - 技能点来自服务端（每封邀请函 +15）
 * - 单个技能上限 5 点，第 1~3 点每点消耗 1 点，3→4、4→5 各消耗 2 点
 * - 运动在 3/4/5 点解锁 自我保护/跳跃/攀爬，肉搏在 3/4/5 点解锁 肉搏格挡/摔绊/冲锋攻击，
 *   白刃在 3 点解锁 白刃格挡；解锁后的主动技能可在主神面板"战斗预设"中拖入九宫格使用
 */
public class SkillAllocationScreen extends Screen {

    // ===== 淡蓝色主题配色（与属性加点界面一致）=====
    private static final int PANEL_BG = 0xEE0A1622;
    private static final int PANEL_BORDER = 0xFF5B9BD5;
    private static final int HEADER_LINE = 0x665B9BD5;
    private static final int ROW_BG = 0x66132B42;
    private static final int ROW_BG_ALT = 0x440F2233;
    private static final int ROW_HOVER = 0x99256490;
    private static final int TEXT_MAIN = 0xFFD9EEFF;
    private static final int TEXT_SUB = 0xFF8FC6EE;
    private static final int TEXT_TITLE = 0xFF9FD8F8;
    private static final int ACCENT = 0xFF3BA9E0;
    private static final int GOLD = 0xFFFFD966;
    private static final Style GOLD_STYLE = Style.EMPTY.withColor(TextColor.fromRgb(GOLD));
    private static final int DOT_FULL = 0xFF4FC3F7;
    private static final int DOT_FULL_5 = 0xFFFFD966;
    private static final int DOT_EMPTY = 0x66203A52;
    private static final int DOT_BORDER = 0xFF3A6E96;
    private static final int BTN_BG = 0xCC16455F;
    private static final int BTN_HOVER = 0xE02A6E96;
    private static final int BTN_DISABLED = 0x55143049;
    private static final int BTN_BORDER = 0xFF5B9BD5;
    private static final int BTN_DISABLED_BORDER = 0xFF2A4A62;
    private static final int WARN = 0xFFFF7B7B;

    // ===== 布局常量 =====
    private static final int ROW_HEIGHT = 28;
    private static final int HEADER_HEIGHT = 50;
    private static final int DESC_HEIGHT = 82;
    private static final int BTN_SIZE = 16;
    private static final int NAV_W = 72;
    private static final int NAV_H = 20;

    // ===== 状态 =====
    private final int[] points = new int[SkillType.COUNT];
    private final int totalPoints;
    private int scrollOffset;
    private int maxScroll;
    private int infoRow = 0;

    // ===== 面板几何 =====
    private int panelX, panelY, panelW, panelH;
    private int listTop, listBottom;
    private final int confirmW = 92, confirmH = 20;
    private int confirmX, confirmY;
    private int navX, navY;

    public SkillAllocationScreen() {
        super(Component.translatable("screen.zhushenspace.skill.allocation.title"));
        System.arraycopy(ClientSkillData.points(), 0, points, 0, SkillType.COUNT);
        this.totalPoints = ClientSkillData.totalSkillPoints();
    }

    private int freePoints() {
        return totalPoints - SkillType.totalCost(points);
    }

    // ===== 布局计算 =====

    @Override
    protected void init() {
        panelW = Math.min(430, this.width - 40);
        panelH = Math.min(this.height - 20, 236);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        listTop = panelY + HEADER_HEIGHT;
        listBottom = panelY + panelH - DESC_HEIGHT - 6;
        confirmX = panelX + panelW - confirmW - 10;
        confirmY = panelY + 10;
        navX = panelX + 10;
        navY = panelY + 10;
        clampScroll();
    }

    private int rowY(int index) {
        return listTop + index * ROW_HEIGHT - scrollOffset;
    }

    private int plusX() {
        return panelX + panelW - 14 - BTN_SIZE;
    }

    private int minusX() {
        return plusX() - BTN_SIZE - 4;
    }

    private int buttonY(int index) {
        return rowY(index) + (ROW_HEIGHT - BTN_SIZE) / 2;
    }

    private boolean rowVisible(int index) {
        int ry = rowY(index);
        return ry + ROW_HEIGHT > listTop && ry < listBottom;
    }

    private boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private boolean overRow(double mx, double my, int index) {
        int ry = rowY(index);
        return over(mx, my, panelX + 6, ry + 1, panelW - 12, ROW_HEIGHT - 2);
    }

    private void clampScroll() {
        int content = SkillType.COUNT * ROW_HEIGHT;
        maxScroll = Math.max(0, content - (listBottom - listTop));
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
    }

    // ===== 渲染 =====

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        renderPanel(g);
        renderHeader(g, mouseX, mouseY);
        renderRows(g, mouseX, mouseY);
        renderScrollbar(g);
        renderDescPanel(g);
        renderConfirm(g, mouseX, mouseY);
        renderNav(g, mouseX, mouseY);
    }

    private void renderPanel(GuiGraphics g) {
        g.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0x333BA9E0);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        g.renderOutline(panelX, panelY, panelW, panelH, PANEL_BORDER);
        g.renderOutline(panelX + 1, panelY + 1, panelW - 2, panelH - 2, 0x333BA9E0);
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        // 标题（避开左右按钮区域）
        int titleCenter = panelX + panelW / 2;
        g.drawCenteredString(font, title, titleCenter, panelY + 8, TEXT_TITLE);

        // 统计行
        int statsY = panelY + 24;
        g.drawString(font, Component.translatable("screen.zhushenspace.skill.free_points", freePoints()),
                panelX + 12, statsY, ACCENT, true);

        // 规则提示
        String hint = Component.translatable("screen.zhushenspace.skill.hint").getString();
        int hintWidth = font.width(hint);
        g.drawString(font, hint, panelX + panelW - hintWidth - 14, statsY, TEXT_SUB, true);

        // 分隔线
        g.fill(panelX + 6, panelY + HEADER_HEIGHT - 4, panelX + panelW - 6, panelY + HEADER_HEIGHT - 3, HEADER_LINE);
    }

    private void renderRows(GuiGraphics g, int mouseX, int mouseY) {
        g.enableScissor(panelX + 1, listTop, panelX + panelW - 1, listBottom);
        for (int i = 0; i < SkillType.COUNT; i++) {
            if (!rowVisible(i)) continue;
            renderRow(g, i, mouseX, mouseY);
        }
        g.disableScissor();
    }

    private void renderRow(GuiGraphics g, int index, int mouseX, int mouseY) {
        SkillType type = SkillType.values()[index];
        int ry = rowY(index);
        boolean hover = overRow(mouseX, mouseY, index);

        int bg = hover ? ROW_HOVER : (index % 2 == 0 ? ROW_BG : ROW_BG_ALT);
        g.fill(panelX + 6, ry + 1, panelX + panelW - 6, ry + ROW_HEIGHT - 1, bg);
        if (hover) {
            g.fill(panelX + 6, ry + 1, panelX + 9, ry + ROW_HEIGHT - 1, ACCENT);
        }

        int tx = panelX + 16;

        g.drawString(font, Component.translatable(type.nameKey()), tx, ry + 10, TEXT_MAIN, true);

        // 点数圆点
        int dotsX = tx + 66;
        int dotY = ry + (ROW_HEIGHT - 7) / 2;
        for (int d = 0; d < SkillType.MAX_POINTS; d++) {
            int dx = dotsX + d * 10;
            if (d < points[index]) {
                int color = d == SkillType.MAX_POINTS - 1 ? DOT_FULL_5 : DOT_FULL;
                g.fill(dx, dotY, dx + 7, dotY + 7, color);
            } else {
                g.fill(dx, dotY, dx + 7, dotY + 7, DOT_EMPTY);
                g.renderOutline(dx, dotY, 7, 7, DOT_BORDER);
            }
        }

        // 数值
        int valueX = dotsX + SkillType.MAX_POINTS * 10 + 6;
        boolean maxed = points[index] >= SkillType.MAX_POINTS;
        g.drawString(font, points[index] + "/" + SkillType.MAX_POINTS,
                valueX, ry + 10, maxed ? GOLD : TEXT_SUB, true);

        // 加/减按钮
        int bY = buttonY(index);
        int step = SkillType.stepCost(points[index]);
        boolean canUp = step > 0 && freePoints() >= step;
        boolean canDown = points[index] > 0;

        if (step > 0) {
            boolean afford = freePoints() >= step;
            g.drawString(font, "×" + step, minusX() - 18, bY + 4, afford ? TEXT_SUB : WARN, true);
        }

        renderMiniButton(g, minusX(), bY, "-", canDown, over(mouseX, mouseY, minusX(), bY, BTN_SIZE, BTN_SIZE));
        renderMiniButton(g, plusX(), bY, "+", canUp, over(mouseX, mouseY, plusX(), bY, BTN_SIZE, BTN_SIZE));
    }

    private void renderMiniButton(GuiGraphics g, int x, int y, String symbol, boolean enabled, boolean hovered) {
        if (!enabled) {
            g.fill(x, y, x + BTN_SIZE, y + BTN_SIZE, BTN_DISABLED);
            g.renderOutline(x, y, BTN_SIZE, BTN_SIZE, BTN_DISABLED_BORDER);
        } else {
            g.fill(x, y, x + BTN_SIZE, y + BTN_SIZE, hovered ? BTN_HOVER : BTN_BG);
            g.renderOutline(x, y, BTN_SIZE, BTN_SIZE, hovered ? 0xFF7FC4F0 : BTN_BORDER);
        }
        g.drawCenteredString(font, symbol, x + BTN_SIZE / 2, y + (BTN_SIZE - 8) / 2,
                enabled ? TEXT_MAIN : 0xFF4A6A80);
    }

    private void renderScrollbar(GuiGraphics g) {
        if (maxScroll <= 0) return;
        int trackX = panelX + panelW - 10;
        int trackTop = listTop + 2;
        int trackBottom = listBottom - 2;
        int trackHeight = trackBottom - trackTop;
        g.fill(trackX, trackTop, trackX + 4, trackBottom, 0x33132B42);
        int thumbHeight = Math.max(12, (int) ((float) trackHeight * (listBottom - listTop)
                / (SkillType.COUNT * ROW_HEIGHT)));
        int thumbY = trackTop + (int) ((float) (trackHeight - thumbHeight) * scrollOffset / maxScroll);
        g.fill(trackX, thumbY, trackX + 4, thumbY + thumbHeight, 0xFF5B9BD5);
    }

    private void renderDescPanel(GuiGraphics g) {
        int top = panelY + panelH - DESC_HEIGHT - 4;
        g.fill(panelX + 6, top, panelX + panelW - 6, panelY + panelH - 4, 0x88102438);
        g.renderOutline(panelX + 6, top, panelW - 12, DESC_HEIGHT, 0x335B9BD5);

        SkillType type = SkillType.values()[infoRow];
        int tx = panelX + 14;
        int y = top + 5;

        g.drawString(font, Component.translatable(type.nameKey()), tx, y, ACCENT, true);
        y += 13;

        List<FormattedCharSequence> desc = font.split(Component.translatable(type.descKey()), panelW - 28);
        for (int i = 0; i < desc.size() && i < 2; i++) {
            g.drawString(font, desc.get(i), tx, y, TEXT_MAIN, true);
            y += 11;
        }

        // 解锁主动技能列表
        y = top + 5 + 13 + 24;
        String unlockList = buildUnlockList(type);
        if (!unlockList.isEmpty()) {
            List<FormattedCharSequence> lines = font.split(
                    Component.translatable("screen.zhushenspace.skill.unlock_list", unlockList)
                            .withStyle(GOLD_STYLE),
                    panelW - 28);
            for (int i = 0; i < lines.size() && i < 2; i++) {
                g.drawString(font, lines.get(i), tx, y, GOLD, true);
                y += 11;
            }
        }
    }

    /** 构建某技能的主动技能解锁列表（如 "3级·自我保护 · 4级·跳跃 · 5级·攀爬"） */
    private String buildUnlockList(SkillType type) {
        List<String> parts = new ArrayList<>();
        for (SkillAbility ability : SkillAbility.values()) {
            if (ability.owner() == type) {
                parts.add(ability.requiredLevel() + Component.translatable(
                                "screen.zhushenspace.skill.level_suffix").getString()
                        + Component.translatable(ability.nameKey()).getString());
            }
        }
        return String.join(Component.translatable("screen.zhushenspace.skill.list_separator").getString(), parts);
    }

    private void renderConfirm(GuiGraphics g, int mouseX, int mouseY) {
        boolean hover = over(mouseX, mouseY, confirmX, confirmY, confirmW, confirmH);
        g.fill(confirmX, confirmY, confirmX + confirmW, confirmY + confirmH, hover ? BTN_HOVER : BTN_BG);
        g.renderOutline(confirmX, confirmY, confirmW, confirmH, hover ? 0xFF7FC4F0 : BTN_BORDER);
        g.drawCenteredString(font, Component.translatable("screen.zhushenspace.skill.confirm"),
                confirmX + confirmW / 2, confirmY + (confirmH - 8) / 2, TEXT_MAIN);
    }

    private void renderNav(GuiGraphics g, int mouseX, int mouseY) {
        boolean hover = over(mouseX, mouseY, navX, navY, NAV_W, NAV_H);
        g.fill(navX, navY, navX + NAV_W, navY + NAV_H, hover ? BTN_HOVER : BTN_BG);
        g.renderOutline(navX, navY, NAV_W, NAV_H, hover ? 0xFF7FC4F0 : BTN_BORDER);
        g.drawCenteredString(font, Component.translatable("screen.zhushenspace.to_attributes"),
                navX + NAV_W / 2, navY + (NAV_H - 8) / 2, TEXT_MAIN);
    }

    // ===== 交互 =====

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        for (int i = 0; i < SkillType.COUNT; i++) {
            if (rowVisible(i) && overRow(mouseX, mouseY, i)) {
                infoRow = i;
                break;
            }
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (over(mouseX, mouseY, confirmX, confirmY, confirmW, confirmH)) {
                onConfirm();
                return true;
            }
            if (over(mouseX, mouseY, navX, navY, NAV_W, NAV_H)) {
                Minecraft.getInstance().setScreen(new AttributeAllocationScreen());
                return true;
            }
            for (int i = 0; i < SkillType.COUNT; i++) {
                if (!rowVisible(i)) continue;
                int bY = buttonY(i);
                if (over(mouseX, mouseY, plusX(), bY, BTN_SIZE, BTN_SIZE)) {
                    onPlus(i);
                    return true;
                }
                if (over(mouseX, mouseY, minusX(), bY, BTN_SIZE, BTN_SIZE)) {
                    onMinus(i);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollOffset -= (int) Math.signum(scrollY) * ROW_HEIGHT;
        clampScroll();
        return true;
    }

    private void onPlus(int index) {
        int step = SkillType.stepCost(points[index]);
        if (step > 0 && freePoints() >= step) {
            points[index]++;
            playClick(1.0f);
        }
    }

    private void onMinus(int index) {
        if (points[index] > 0) {
            points[index]--;
            playClick(0.8f);
        }
    }

    private void onConfirm() {
        PacketDistributor.sendToServer(new CommitSkillAllocationPayload(points));
        Minecraft.getInstance().setScreen(null);
    }

    private void playClick(float pitch) {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

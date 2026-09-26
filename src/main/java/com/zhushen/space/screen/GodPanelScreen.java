package com.zhushen.space.screen;

import com.zhushen.space.client.ClientAttributeData;
import com.zhushen.space.client.ClientEnergyData;
import com.zhushen.space.client.ClientHealthData;
import com.zhushen.space.client.ClientProgressData;
import com.zhushen.space.client.ClientSkillData;
import com.zhushen.space.common.HallManager;
import com.zhushen.space.common.ProgressManager;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.PlayerCurrencyData;
import com.zhushen.space.data.SchoolType;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.network.EnterHallPayload;
import com.zhushen.space.network.EquipSkillsPayload;
import com.zhushen.space.network.SchoolClaimPayload;
import com.zhushen.space.network.SchoolPurchasePayload;
import com.zhushen.space.network.SchoolSkillPurchasePayload;
import com.zhushen.space.ZhuShenSpace;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.zhushen.space.screen.ZsTheme.*;

/**
 * 主神面板：在背包界面通过顶部选项卡打开。
 *
 * 四个选项卡：
 * - 属性：直接在面板内 +/- 加点，确认后提交（悬停查看详情与传奇加成）
 * - 技能：直接在面板内 +/- 加点，确认后提交（悬停查看解锁的主动技能）
 * - 战斗预设：将解锁的主动技能拖入九宫格装备，战斗模式（Alt）下按 1~9 使用
 * - 商城：使用主神空间资产（支线 + 奖励点数）购买流派等
 */
public class GodPanelScreen extends Screen {

    private enum Tab { ATTRIBUTES, SKILLS, PRESET, SHOP }


    private static final int ROW_HEIGHT = 18;
    private static final int HEADER_HEIGHT = 40;
    private static final int TOOLTIP_WIDTH = 175;
    private static final int TAB_H = 14;
    private static final int SLOT_SIZE = 20;
    private static final int CHIP_H = 16;

    // ===== 属性 / 技能页状态（两页共用同一套加点列表逻辑） =====
    private final PointList attrList = new PointList(AttributeType.COUNT, AttributeType.MAX_POINTS,
            AttributeType::totalCost, AttributeType::stepCost);
    private final PointList skillList = new PointList(SkillType.COUNT, SkillType.MAX_POINTS,
            SkillType::totalCost, SkillType::stepCost);
    /** 技能页 pending 数组别名（技能提示按待确认值显示解锁状态） */
    private final int[] skillPoints = skillList.cur;
    private static final int PM_BTN = 11;

    // ===== 预设页状态 =====
    /** 两套战斗预设栏（A/B），两栏共享已解锁技能 */
    private final int[][] slots = new int[2][9];
    private int dragging = -1;
    private int dragFromSlot = -1;
    private int dragFromBar = -1;
    /** 太极拳文件夹是否展开 */
    private boolean taiChiFolderOpen = false;
    /** 技能区滚动偏移（行） */
    private int chipScroll = 0;

    // ===== 商城页状态 =====
    /** 当前查看详情的流派序号（-1 = 卡片列表） */
    private int detailSchool = -1;
    /** 详情页技能列表滚动偏移（行） */
    private int detailScroll = 0;

    private Tab tab = Tab.ATTRIBUTES;
    /** 打开 / 切换选项卡时刻（驱动弹出与内容滑入动画） */
    private long openedAt = -1, tabChangedAt;

    private int panelX, panelY, panelW, panelH;
    private int listTop, listBottom;
    private final int confirmW = 40, resetW = 34, actionH = 13;
    private int confirmX, resetX, actionY;
    private int gearX;
    /** 主神大厅按钮（标签行，齿轮左侧） */
    private int hallX;
    private final int[] tabX = new int[4];
    private final int[] tabW = {44, 44, 62, 40};

    public GodPanelScreen() {
        super(Component.translatable("screen.zhushenspace.godpanel.title"));
        attrList.load(ClientAttributeData.points(), ClientAttributeData.totalPoints());
        skillList.load(ClientSkillData.points(), ClientSkillData.totalSkillPoints());
        for (int b = 0; b < slots.length; b++) {
            System.arraycopy(ClientSkillData.bar(b), 0, slots[b], 0, 9);
        }
    }

    /** 当前选项卡对应的加点列表（非加点页返回 null） */
    private PointList activeList() {
        return tab == Tab.ATTRIBUTES ? attrList : tab == Tab.SKILLS ? skillList : null;
    }

    /**
     * 加点列表：saved=服务端已保存值，cur=面板内待确认值。
     * 属性页与技能页的渲染、点击、滚动、提交全部复用这一个类，不再各自维护一套。
     */
    private static final class PointList {
        final int count, max;
        final int[] saved, cur;
        final java.util.function.ToIntFunction<int[]> costFn;
        final java.util.function.IntUnaryOperator stepFn;
        int total, scroll, maxScroll, hovered = -1;

        PointList(int count, int max, java.util.function.ToIntFunction<int[]> costFn,
                  java.util.function.IntUnaryOperator stepFn) {
            this.count = count;
            this.max = max;
            this.saved = new int[count];
            this.cur = new int[count];
            this.costFn = costFn;
            this.stepFn = stepFn;
        }

        void load(int[] src, int total) {
            System.arraycopy(src, 0, saved, 0, count);
            System.arraycopy(src, 0, cur, 0, count);
            this.total = total;
        }

        /** 未改动时跟随服务端同步刷新（确认后回包、指令发点等） */
        /** 提交后等待服务端回包的截止时间（期间不回滚显示，避免闪烁） */
        long awaitUntil;

        void refresh(int[] src, int total) {
            if (System.currentTimeMillis() < awaitUntil && !java.util.Arrays.equals(src, cur)) return;
            awaitUntil = 0;
            if (!dirty()) load(src, total);
            else {
                System.arraycopy(src, 0, saved, 0, count);
                this.total = total;
            }
        }

        boolean dirty() {
            return !java.util.Arrays.equals(saved, cur);
        }

        int free() {
            return total - costFn.applyAsInt(cur);
        }

        int step(int i) {
            return stepFn.applyAsInt(cur[i]);
        }

        boolean canUp(int i) {
            int st = step(i);
            return st > 0 && free() >= st;
        }

        /** 只能撤回本次未确认的点，已确认（saved）的点不可退回 */
        boolean canDown(int i) {
            return cur[i] > saved[i];
        }

        void reset() {
            System.arraycopy(saved, 0, cur, 0, count);
        }
    }

    // ===== 布局 =====

    @Override
    protected void init() {
        if (openedAt < 0) openedAt = tabChangedAt = ZsAnim.nowMs();
        panelW = Math.min(270, this.width - 40);
        panelH = Math.min(this.height - 20, HEADER_HEIGHT + AttributeType.COUNT * ROW_HEIGHT + 32);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        listTop = panelY + HEADER_HEIGHT;
        listBottom = panelY + panelH - 28;
        confirmX = panelX + panelW - confirmW - 6;
        resetX = confirmX - resetW - 4;
        actionY = panelY + 22;
        gearX = panelX + panelW - 26;
        hallX = gearX - 36;
        int tx = panelX + 6;
        for (int i = 0; i < 4; i++) {
            tabX[i] = tx;
            tx += tabW[i] + 4;
        }
        clampScroll();
    }

    private int rowY(PointList list, int index) {
        return listTop + index * ROW_HEIGHT - list.scroll;
    }

    private boolean rowVisible(PointList list, int index) {
        int ry = rowY(list, index);
        return ry + ROW_HEIGHT > listTop && ry < listBottom;
    }

    private boolean over(double mx, double my, int x, int y, int w, int h) {
        return ZsTheme.over(mx, my, x, y, w, h);
    }

    private int plusX() {
        return panelX + panelW - 12 - PM_BTN;
    }

    private int minusX() {
        return plusX() - PM_BTN - 3;
    }

    private void clampScroll() {
        for (PointList list : new PointList[]{attrList, skillList}) {
            int footer = list == skillList ? 22 : 0;
            int content = list.count * ROW_HEIGHT + footer;
            list.maxScroll = Math.max(0, content - (listBottom - listTop));
            list.scroll = Mth.clamp(list.scroll, 0, list.maxScroll);
        }
    }

    // ===== 渲染 =====

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        attrList.refresh(ClientAttributeData.points(), ClientAttributeData.totalPoints());
        skillList.refresh(ClientSkillData.points(), ClientSkillData.totalSkillPoints());
        clampScroll();
        ZsTheme.beginOpen(g, openedAt, panelX + panelW / 2, panelY + panelH / 2);
        renderPanel(g);
        renderHeader(g, mouseX, mouseY);

        // 切换选项卡：内容自右侧 10px 滑入
        float slide = 1 - ZsAnim.easeOutCubic((ZsAnim.nowMs() - tabChangedAt) / 220f);
        g.pose().pushPose();
        g.pose().translate(10 * slide, 0, 0);
        switch (tab) {
            case ATTRIBUTES -> renderPointTab(g, mouseX, mouseY, attrList);
            case SKILLS -> renderPointTab(g, mouseX, mouseY, skillList);
            case PRESET -> renderPresetTab(g, mouseX, mouseY);
            case SHOP -> renderShopTab(g, mouseX, mouseY);
        }
        g.pose().popPose();
        ZsTheme.endOpen(g);
    }

    private void renderPanel(GuiGraphics g) {
        ZsTheme.panel(g, panelX, panelY, panelW, panelH);
        // 面板中央缓慢旋转的法阵水印
        int sz = Math.min(panelW, panelH) - 40;
        ZsAnim.SIGIL.draw(g, panelX + (panelW - sz) / 2, panelY + (panelH - sz) / 2 + 10, sz, sz, 0x22FFFFFF);
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        // 选项卡
        String[] labels = {
                Component.translatable("screen.zhushenspace.godpanel.tab.attributes").getString(),
                Component.translatable("screen.zhushenspace.godpanel.tab.skills").getString(),
                Component.translatable("screen.zhushenspace.godpanel.tab.preset").getString(),
                Component.translatable("screen.zhushenspace.godpanel.tab.shop").getString()
        };
        Component[] tabLabels = new Component[4];
        for (int i = 0; i < 4; i++) {
            // 有未确认改动的加点页在标签上打 * 提醒
            boolean dirty = (i == 0 && attrList.dirty()) || (i == 1 && skillList.dirty());
            tabLabels[i] = Component.literal(dirty ? labels[i] + "*" : labels[i]);
        }
        ZsTheme.tabs(g, font, mouseX, mouseY, tabX, tabW, panelY + 5, TAB_H, tabLabels, tab.ordinal(), 1);

        // 主神空间大厅（标签行，齿轮左侧）：进入大厅 / 返回主世界
        boolean inHall = minecraft != null && minecraft.player != null
                && minecraft.player.level().dimension() == HallManager.HALL_DIMENSION;
        renderSmallButton(g, mouseX, mouseY, hallX, panelY + 5, 32, TAB_H,
                Component.translatable(inHall
                        ? "screen.zhushenspace.godpanel.leave_hall"
                        : "screen.zhushenspace.godpanel.enter_hall"));
        if (over(mouseX, mouseY, hallX, panelY + 5, 32, TAB_H)) {
            g.renderTooltip(font, List.of(Component.translatable(
                    inHall ? "screen.zhushenspace.hall.tooltip_leave"
                           : "screen.zhushenspace.hall.tooltip_enter").getVisualOrderText()), mouseX, mouseY);
        }

        // 能量池界面设置入口（⚙，标签行最右）
        renderSmallButton(g, mouseX, mouseY, gearX, panelY + 5, 20, TAB_H, Component.literal("⚙"));
        if (over(mouseX, mouseY, gearX, panelY + 5, 20, TAB_H)) {
            g.renderTooltip(font, List.of(Component.translatable(
                    "screen.zhushenspace.energy_config.entry").getVisualOrderText()), mouseX, mouseY);
        }

        // 分隔线
        ZsTheme.separator(g, panelX + 4, panelX + panelW - 4, panelY + HEADER_HEIGHT - 3);

        // 第二行统计（按选项卡）
        switch (tab) {
            case ATTRIBUTES, SKILLS -> {
                PointList list = activeList();
                String free = Component.translatable(tab == Tab.ATTRIBUTES
                        ? "screen.zhushenspace.free_points"
                        : "screen.zhushenspace.skill.free_points", list.free()).getString();
                int sx = panelX + 8;
                g.drawString(font, free, sx, actionY + 3, list.free() > 0 ? ACCENT : TEXT_SUB, true);
                sx += font.width(free) + 8;
                if (tab == Tab.ATTRIBUTES) {
                    String legend = Component.translatable("screen.zhushenspace.legendary_points",
                            AttributeType.legendaryCount(attrList.cur)).getString();
                    g.drawString(font, legend, sx, actionY + 3, GOLD, true);
                }
                boolean dirty = list.dirty();
                ZsTheme.button(g, font, mouseX, mouseY, resetX, actionY, resetW, actionH,
                        Component.translatable("screen.zhushenspace.reset"), dirty, false);
                ZsTheme.button(g, font, mouseX, mouseY, confirmX, actionY, confirmW, actionH,
                        Component.translatable("screen.zhushenspace.apply"), dirty, false);
            }
            case PRESET -> {
                List<FormattedCharSequence> lines = font.split(
                        Component.translatable("screen.zhushenspace.preset.hint"), panelW - 16);
                int y = panelY + 22;
                for (int i = 0; i < lines.size() && i < 2; i++) {
                    g.drawString(font, lines.get(i), panelX + 8, y, TEXT_SUB, true);
                    y += 10;
                }
            }
            case SHOP -> g.drawString(font,
                    Component.translatable("screen.zhushenspace.shop.hint"),
                    panelX + 8, panelY + 26, TEXT_SUB, true);
        }

        // 底部生命状态行（始终显示；有伤势时附 B/L/A 分段）
        if (minecraft != null && minecraft.player != null) {
            int hy = panelY + panelH - 24;
            int maxHp = Math.round(minecraft.player.getMaxHealth());
            String intact = Component.translatable("screen.zhushenspace.health.intact",
                    Math.max(0, maxHp - ClientHealthData.total())).getString();
            g.drawString(font, intact, panelX + 8, hy, ACCENT, true);
            int hx = panelX + 8 + font.width(intact) + 6;
            hx = drawWoundSegment(g, "B", ClientHealthData.b(), 0xFFF5D76E, hx, hy);
            hx = drawWoundSegment(g, "L", ClientHealthData.l(), 0xFFE8873A, hx, hy);
            drawWoundSegment(g, "A", ClientHealthData.a(), 0xFFE05C6E, hx, hy);
        }

        // 底部货币栏（主神空间资产：支线 + 奖励点数）
        String currency = Component.translatable("screen.zhushenspace.currency",
                ClientProgressData.branch(0), ClientProgressData.branch(1),
                ClientProgressData.branch(2), ClientProgressData.branch(3),
                ClientProgressData.branch(4), ClientProgressData.score()).getString();
        g.drawString(font, currency, panelX + 8, panelY + panelH - 12, CURRENCY, true);
    }

    /** 可购买按钮外圈呼吸金光，吸引注意 */
    private static void buyGlow(GuiGraphics g, int x, int y, int w, int h) {
        float p = ZsAnim.pulse(1400);
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, ZsAnim.withAlpha(GOLD, 0.10f + 0.25f * p));
    }

    private void renderSmallButton(GuiGraphics g, int mouseX, int mouseY,
                                   int x, int y, int w, int h, Component label) {
        ZsTheme.button(g, font, mouseX, mouseY, x, y, w, h, label);
    }

    /** 绘制一段伤势数值（如 “B3”），返回下一段起始 x（无伤势则原样返回） */
    private int drawWoundSegment(GuiGraphics g, String label, int value, int color, int x, int y) {
        if (value <= 0) return x;
        String text = label + value;
        g.drawString(font, text, x, y, color, true);
        return x + font.width(text) + 5;
    }

    // ===== 属性 / 技能页（共用） =====

    private void renderPointTab(GuiGraphics g, int mouseX, int mouseY, PointList list) {
        boolean attr = list == attrList;
        list.hovered = -1;
        g.enableScissor(panelX + 1, listTop, panelX + panelW - 1, listBottom);
        for (int i = 0; i < list.count; i++) {
            if (!rowVisible(list, i)) continue;
            int ry = rowY(list, i);
            if (over(mouseX, mouseY, panelX + 5, ry, panelW - 10, ROW_HEIGHT)
                    && mouseY >= listTop && mouseY < listBottom) list.hovered = i;
            renderPointRow(g, mouseX, mouseY, list, i, ry, attr);
        }
        if (!attr) {
            // 已自动获得的能力（内力系随内力池）与流派购买状态提示
            int footerY = rowY(list, list.count) + 3;
            if (ClientEnergyData.hasPool(ClientEnergyData.NEILI_ID)) {
                g.drawString(font, Component.translatable("screen.zhushenspace.skill.acquired_neili"),
                        panelX + 8, footerY, 0xFF4DE0C0, true);
                footerY += 10;
            }
            if (ClientProgressData.taiChiUnlocked()) {
                g.drawString(font, Component.translatable("screen.zhushenspace.skill.school_owned"),
                        panelX + 8, footerY, GOLD, true);
            }
        }
        g.disableScissor();
        int footer = attr ? 0 : 22;
        ZsTheme.scrollbar(g, panelX + panelW - 5, listTop + 2, listBottom - 2,
                list.count * ROW_HEIGHT + footer, list.scroll, list.maxScroll);

        // 悬停 +/- 按钮时不弹大提示框，避免遮挡
        if (list.hovered >= 0 && mouseX < minusX()) {
            g.renderTooltip(font, attr
                    ? buildTooltip(AttributeType.values()[list.hovered])
                    : buildSkillTooltip(SkillType.values()[list.hovered]), mouseX, mouseY);
        }
    }

    private void renderPointRow(GuiGraphics g, int mouseX, int mouseY, PointList list,
                                int index, int ry, boolean attr) {
        boolean hover = index == list.hovered;
        ZsTheme.row(g, panelX + 5, ry, panelW - 10, ROW_HEIGHT - 1, hover, index % 2 != 0);

        Component name = Component.translatable(attr
                ? AttributeType.values()[index].nameKey()
                : SkillType.values()[index].nameKey());
        g.drawString(font, name, panelX + 12, ry + 5, TEXT_MAIN, true);

        int cur = list.cur[index];
        int dotsX = panelX + (attr ? 46 : 62);
        int end = ZsTheme.dots(g, dotsX, ry + (ROW_HEIGHT - 5) / 2, 5, 3,
                list.max, list.saved[index], cur);

        String value;
        if (attr && AttributeType.values()[index] == AttributeType.INTELLIGENCE
                && ClientSkillData.intelligenceBonus() > 0) {
            // 智力行显示技能转化加成：基础(+技能加成)
            value = cur + "(+" + ClientSkillData.intelligenceBonus() + ")";
        } else {
            value = cur + "/" + list.max;
        }
        int valueColor = cur != list.saved[index] ? PENDING : cur >= list.max ? GOLD : TEXT_SUB;
        g.drawString(font, value, end + 2, ry + 5, valueColor, true);

        // 下一级消耗 + 加减按钮
        int by = ry + (ROW_HEIGHT - PM_BTN) / 2;
        int step = list.step(index);
        if (step > 0) {
            String cost = "×" + step;
            g.drawString(font, cost, minusX() - font.width(cost) - 3, ry + 5,
                    list.free() >= step ? TEXT_SUB : WARN, true);
        }
        ZsTheme.button(g, font, mouseX, mouseY, minusX(), by, PM_BTN, PM_BTN,
                Component.literal("-"), list.canDown(index), false);
        ZsTheme.button(g, font, mouseX, mouseY, plusX(), by, PM_BTN, PM_BTN,
                Component.literal("+"), list.canUp(index), false);
    }

    /** 加点页点击：+/-、重置、确认 */
    private boolean handlePointClick(double mouseX, double mouseY, PointList list) {
        if (list.dirty() && over(mouseX, mouseY, resetX, actionY, resetW, actionH)) {
            list.reset();
            ZsTheme.click(0.8f);
            return true;
        }
        if (list.dirty() && over(mouseX, mouseY, confirmX, actionY, confirmW, actionH)) {
            if (list == attrList) {
                PacketDistributor.sendToServer(new com.zhushen.space.network.CommitAllocationPayload(list.cur.clone()));
            } else {
                PacketDistributor.sendToServer(new com.zhushen.space.network.CommitSkillAllocationPayload(list.cur.clone()));
            }
            // 乐观更新：视为已保存，服务端回包后 refresh 会校正
            System.arraycopy(list.cur, 0, list.saved, 0, list.count);
            list.awaitUntil = System.currentTimeMillis() + 1500;
            ZsTheme.click(1.2f);
            return true;
        }
        if (mouseY < listTop || mouseY >= listBottom) return false;
        for (int i = 0; i < list.count; i++) {
            if (!rowVisible(list, i)) continue;
            int by = rowY(list, i) + (ROW_HEIGHT - PM_BTN) / 2;
            if (over(mouseX, mouseY, plusX(), by, PM_BTN, PM_BTN)) {
                if (list.canUp(i)) {
                    // Shift+点击：一次加到满（点数够的前提下）
                    do {
                        list.cur[i]++;
                    } while (Screen.hasShiftDown() && list.canUp(i));
                    ZsTheme.click(1.0f);
                }
                return true;
            }
            if (over(mouseX, mouseY, minusX(), by, PM_BTN, PM_BTN)) {
                if (list.canDown(i)) {
                    if (Screen.hasShiftDown()) list.cur[i] = list.saved[i];
                    else list.cur[i]--;
                    ZsTheme.click(0.8f);
                }
                return true;
            }
        }
        return false;
    }

    /** 技能悬停提示：说明 + 被动 + 解锁主动技能 */
    private List<FormattedCharSequence> buildSkillTooltip(SkillType type) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(Component.translatable(type.nameKey()).getVisualOrderText());
        lines.addAll(font.split(Component.translatable(type.descKey()), TOOLTIP_WIDTH));
        for (SkillAbility ability : SkillAbility.values()) {
            if (ability.owner() != type) continue;
            boolean unlocked = skillPoints[type.ordinal()] >= ability.requiredLevel();
            Component line = Component.translatable("screen.zhushenspace.skill.unlock_line",
                    ability.requiredLevel(),
                    Component.translatable(ability.nameKey()),
                    Component.translatable(ability.descKey()))
                    .withStyle(unlocked ? GOLD_STYLE : Style.EMPTY);
            lines.addAll(font.split(line, TOOLTIP_WIDTH));
        }
        return lines;
    }

    // ===== 战斗预设页 =====

    private int slotX(int slot) {
        return panelX + (panelW - 9 * SLOT_SIZE) / 2 + slot * SLOT_SIZE;
    }

    /** 第 bar 套预设栏的 y 坐标（A 在上，B 在下） */
    private int slotsY(int bar) {
        return listTop + 6 + bar * (SLOT_SIZE + 4);
    }

    private void renderPresetTab(GuiGraphics g, int mouseX, int mouseY) {
        // 悬停提示延后到所有格子/芯片绘制完之后统一绘制（优先级最高，避免被边框遮挡）
        List<FormattedCharSequence> hoverTip = null;

        // 两套预设栏（A/B），共享已解锁技能
        for (int bar = 0; bar < slots.length; bar++) {
            int sy = slotsY(bar);
            // 栏位标签
            int labelY = sy + (SLOT_SIZE - 8) / 2;
            g.drawCenteredString(font, bar == 0 ? "A" : "B", slotX(0) - 12, labelY, GOLD);
            for (int slot = 0; slot < 9; slot++) {
                int sx = slotX(slot);
                ZsTheme.slot(g, mouseX, mouseY, sx, sy, SLOT_SIZE, slots[bar][slot] >= 0);
                g.drawString(font, String.valueOf(slot + 1), sx + 2, sy + 1, TEXT_SUB, false);

                int abilityId = slots[bar][slot];
                if (abilityId >= 0 && abilityId < SkillAbility.COUNT) {
                    SkillAbility ability = SkillAbility.values()[abilityId];
                    // 技能图标（16×16 居中），悬停显示名称与描述（延后绘制）
                    g.blit(ability.iconTexture(), sx + 2, sy + 2, 16, 16, 0f, 0f, 32, 32, 32, 32);
                    if (dragging == -1 && over(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE)) {
                        hoverTip = buildAbilityTooltip(ability);
                    }
                }
            }
        }

        // 已解锁技能芯片区（太极拳收纳在文件夹中，支持翻页）
        int chipW = (panelW - 24) / 2;
        int chipTop = slotsY(1) + SLOT_SIZE + 8;
        int chipsBottom = listBottom - 14;
        int visibleRows = Math.max(1, (chipsBottom - chipTop) / (CHIP_H + 4));
        int totalRows = chipRowCount();
        chipScroll = Mth.clamp(chipScroll, 0, Math.max(0, totalRows - visibleRows));

        g.enableScissor(panelX + 1, chipTop, panelX + panelW - 1, chipsBottom);
        for (ChipPos pos : layoutChips(chipTop, chipW)) {
            if (pos.item().ability() == null) {
                // 太极拳文件夹（独占一行）
                renderFolderChip(g, mouseX, mouseY, pos.cx(), pos.cy(), panelW - 16);
            } else {
                List<FormattedCharSequence> tip =
                        renderChip(g, mouseX, mouseY, pos.item(), pos.cx(), pos.cy(), chipW);
                if (tip != null) hoverTip = tip;
            }
        }
        g.disableScissor();

        // 翻页指示（内容超出一页时显示）
        if (totalRows > visibleRows) {
            String page = (chipScroll / visibleRows + 1) + "/" + (int) Math.ceil(totalRows / (double) visibleRows);
            int pgY = chipsBottom + 2;
            renderSmallButton(g, mouseX, mouseY, panelX + panelW / 2 - 48, pgY, 14, 12,
                    Component.literal("<"));
            renderSmallButton(g, mouseX, mouseY, panelX + panelW / 2 + 34, pgY, 14, 12,
                    Component.literal(">"));
            g.drawCenteredString(font, Component.translatable("screen.zhushenspace.preset.page", page),
                    panelX + panelW / 2, pgY + 2, TEXT_SUB);
        }

        // 拖拽中的技能跟随鼠标
        if (dragging >= 0) {
            g.drawString(font, Component.translatable(SkillAbility.values()[dragging].shortKey()).getString(),
                    (int) mouseX + 6, (int) mouseY - 6, GOLD, true);
        }

        // 悬停提示最后绘制：位于所有格子、芯片背景与边框之上，且不受芯片区剪裁影响
        if (hoverTip != null) {
            g.renderTooltip(font, hoverTip, mouseX, mouseY);
        }
    }

    /** 芯片条目：ability = null 表示太极拳文件夹行 */
    private record ChipItem(SkillAbility ability, boolean enabled, boolean locked) {
    }

    /** 芯片布局位置（渲染与点击共用同一布局，保证对齐） */
    private record ChipPos(ChipItem item, int cx, int cy) {
    }

    /** 构建技能芯片列表：基础技能 + 太极拳文件夹（展开时列出已购/未购招式） */
    private List<ChipItem> buildChipItems() {
        List<ChipItem> items = new ArrayList<>();
        boolean hasPool = ClientEnergyData.hasPool(ClientEnergyData.NEILI_ID);
        for (SkillAbility ability : SkillAbility.unlocked(ClientSkillData.points(), hasPool, false)) {
            items.add(new ChipItem(ability, true, false));
        }
        if (ClientProgressData.taiChiUnlocked()) {
            items.add(new ChipItem(null, false, false));
            if (taiChiFolderOpen) {
                for (SkillAbility ability : SkillAbility.values()) {
                    if (!"tai_chi".equals(ability.gate())) continue;
                    if (ability == SkillAbility.EIGHT_POWERS) continue; // 被动，不可拖入预设
                    if (ability == SkillAbility.COILING_SILK) continue; // 被动，不可拖入预设
                    boolean purchased = ClientProgressData.skillPurchased(0, ability.ordinal());
                    items.add(new ChipItem(ability, purchased && hasPool, !purchased));
                }
            }
        }
        return items;
    }

    /** 计算芯片布局（含滚动偏移），渲染与点击共用 */
    private List<ChipPos> layoutChips(int chipTop, int chipW) {
        List<ChipPos> list = new ArrayList<>();
        int row = 0, col = 0;
        for (ChipItem item : buildChipItems()) {
            if (item.ability() == null) {
                list.add(new ChipPos(item, panelX + 8, chipY(row, chipTop)));
                row++;
                col = 0;
            } else {
                list.add(new ChipPos(item, panelX + 8 + col * (chipW + 8), chipY(row, chipTop)));
                if (++col == 2) {
                    col = 0;
                    row++;
                }
            }
        }
        return list;
    }

    /** 芯片区总行数（文件夹独占一行，其余每行 2 个） */
    private int chipRowCount() {
        int rows = 0, col = 0;
        for (ChipItem item : buildChipItems()) {
            if (item.ability() == null) {
                rows++;
                col = 0;
            } else if (++col == 2) {
                col = 0;
                rows++;
            }
        }
        return rows + (col > 0 ? 1 : 0);
    }

    /** 芯片行绘制 y（含滚动偏移） */
    private int chipY(int row, int chipTop) {
        return chipTop + row * (CHIP_H + 4) - chipScroll * (CHIP_H + 4);
    }

    /** 太极拳文件夹芯片（展开/收起，显示已购进度） */
    private void renderFolderChip(GuiGraphics g, int mouseX, int mouseY, int cx, int cy, int w) {
        long total = 0, count = 0;
        for (SkillAbility ability : SkillAbility.values()) {
            if ("tai_chi".equals(ability.gate())
                    && ability != SkillAbility.EIGHT_POWERS
                    && ability != SkillAbility.COILING_SILK) {
                total++;
                if (ClientProgressData.skillPurchased(0, ability.ordinal())) count++;
            }
        }
        String label = Component.translatable(taiChiFolderOpen
                        ? "screen.zhushenspace.preset.folder_open" : "screen.zhushenspace.preset.folder_closed",
                Component.translatable("school.zhushenspace.tai_chi"), count, total).getString();
        boolean hover = over(mouseX, mouseY, cx, cy, w, CHIP_H);
        ZsTheme.card(g, cx, cy, w, CHIP_H, hover);
        ZsAnim.TAIJI.draw(g, cx + 3, cy + 2, 12, 12);
        g.drawCenteredString(font, label, cx + w / 2, cy + (CHIP_H - 8) / 2, TEXT_MAIN);
    }

    /** 单个技能芯片（locked = 未购买置灰）。悬停时返回提示行，由调用方最后统一绘制 */
    private List<FormattedCharSequence> renderChip(GuiGraphics g, int mouseX, int mouseY,
                                                   ChipItem item, int cx, int cy, int chipW) {
        SkillAbility ability = item.ability();
        boolean hover = over(mouseX, mouseY, cx, cy, chipW, CHIP_H);
        if (item.locked()) {
            g.fill(cx, cy, cx + chipW, cy + CHIP_H, ROW_BG_ALT);
            g.renderOutline(cx, cy, chipW, CHIP_H, SLOT_BORDER);
        } else {
            ZsTheme.card(g, cx, cy, chipW, CHIP_H, hover);
        }
        // 图标（12×12）+ 名称
        g.blit(ability.iconTexture(), cx + 4, cy + 2, 12, 12, 0f, 0f, 32, 32, 32, 32);
        if (item.locked()) {
            g.fill(cx + 4, cy + 2, cx + 16, cy + 14, 0x8C0E1820); // 置灰遮罩
        }
        int textColor = item.locked() ? 0xFF5A7A8C : TEXT_MAIN;
        String label = Component.translatable(ability.nameKey()).getString();
        if (item.locked()) {
            label += " ✕";
        } else if (!item.enabled()) {
            textColor = 0xFFC9A85C;
        }
        g.drawString(font, label, cx + 20, cy + (CHIP_H - 8) / 2, textColor, true);

        if (hover && dragging == -1) {
            List<FormattedCharSequence> lines = new ArrayList<>(buildAbilityTooltip(ability));
            if (item.locked()) {
                lines.addAll(font.split(Component.translatable("screen.zhushenspace.preset.locked"),
                        TOOLTIP_WIDTH));
            } else if (!item.enabled()) {
                lines.addAll(font.split(Component.translatable("screen.zhushenspace.preset.unequipped"),
                        TOOLTIP_WIDTH));
            }
            return lines;
        }
        return null;
    }

    private List<FormattedCharSequence> buildAbilityTooltip(SkillAbility ability) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(Component.translatable(ability.nameKey()).getVisualOrderText());
        lines.addAll(font.split(Component.translatable(ability.descKey()), TOOLTIP_WIDTH));
        return lines;
    }

    // ===== 商城页 =====

    /** 商城卡片布局（每个流派一张卡） */
    private int shopCardY(int index) {
        return listTop + 4 + index * 56;
    }

    // ===== 商城详情页布局（渲染与点击判定共用，固定行高 + 悬停描述提示） =====

    /** 商城详情页技能行高（固定）：单行紧凑布局，描述改为悬停提示 */
    private static final int SHOP_ROW_H = 24;
    /** 商城详情页悬停描述的换行宽度 */
    private static final int SHOP_TIP_WIDTH = 240;

    /** 详情页技能区起始 Y（返回按钮 + 状态 + 被动/描述实际行数 + 标题） */
    private int detailListY(SchoolType school) {
        int y = listTop + 2;
        y += 14 + 10; // 返回按钮行 + 装备状态行
        y += font.split(Component.translatable(
                "school.zhushenspace." + school.key() + ".passive"), panelW - 16).size() * 10;
        y += font.split(Component.translatable(
                "school.zhushenspace." + school.key() + ".desc"), panelW - 16).size() * 10;
        return y + 2 + 12; // 间隔 + 技能列表标题
    }

    /** 详情页技能列表（该流派 gate 下的全部技能，按枚举顺序） */
    private List<SkillAbility> shopAbilities(SchoolType school) {
        List<SkillAbility> list = new ArrayList<>();
        for (SkillAbility ability : SkillAbility.values()) {
            if (school.key().equals(ability.gate())) list.add(ability);
        }
        return list;
    }

    /** 客户端本地判断：玩家身上（背包 / 饰品栏）是否带有太极拳饰品 */
    private boolean hasEmblemClient() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (mc.player.getInventory().hasAnyOf(Set.of(ZhuShenSpace.TAI_CHI_EMBLEM.get()))) return true;
        return CuriosApi.getCuriosHelper()
                .findFirstCurio(mc.player, ZhuShenSpace.TAI_CHI_EMBLEM.get()).isPresent();
    }

    private void renderShopTab(GuiGraphics g, int mouseX, int mouseY) {
        if (detailSchool >= 0 && detailSchool < SchoolType.COUNT) {
            renderSchoolDetail(g, mouseX, mouseY);
            return;
        }
        for (int i = 0; i < SchoolType.COUNT; i++) {
            SchoolType school = SchoolType.values()[i];
            int cy = shopCardY(i);
            int cx = panelX + 5;
            int cw = panelW - 10;
            boolean owned = ClientProgressData.school(i);

            // 卡片背景（已拥有可点击进入详情）
            boolean hover = over(mouseX, mouseY, cx, cy, cw, 52);
            ZsTheme.card(g, cx, cy, cw, 52, hover);

            // 流派徽记（已拥有：旋转太极；未拥有：暗淡）+ 名称 + 状态/购买按钮
            ZsAnim.TAIJI.draw(g, cx + 5, cy + 3, 12, 12, owned ? 0xFFFFFFFF : 0x66FFFFFF);
            g.drawString(font, Component.translatable(school.nameKey()), cx + 21, cy + 5, TEXT_MAIN, true);
            if (owned) {
                String ownedText = Component.translatable("screen.zhushenspace.shop.owned").getString();
                g.drawString(font, ownedText, cx + cw - font.width(ownedText) - 8, cy + 6, GOLD, true);
            } else if (ClientSkillData.points()[school.reqSkill().ordinal()] < school.reqLevel()) {
                String reqText = Component.translatable("screen.zhushenspace.shop.req_unmet",
                        Component.translatable(school.reqSkill().nameKey()), school.reqLevel()).getString();
                g.drawString(font, reqText, cx + cw - font.width(reqText) - 8, cy + 6, 0xFFFF8A80, true);
            } else {
                buyGlow(g, cx + cw - 48, cy + 3, 42, 14);
                renderSmallButton(g, mouseX, mouseY, cx + cw - 48, cy + 3, 42, 14,
                        Component.translatable("screen.zhushenspace.shop.buy"));
            }

            // 说明
            List<FormattedCharSequence> desc = font.split(
                    Component.translatable("school.zhushenspace." + school.key() + ".desc"), cw - 16);
            int dy = cy + 17;
            for (int l = 0; l < desc.size() && l < 2; l++) {
                g.drawString(font, desc.get(l), cx + 8, dy, TEXT_SUB, true);
                dy += 10;
            }

            // 底行：价格 / 领取饰品按钮 / 详情入口
            if (owned) {
                if (!hasEmblemClient()) {
                    renderSmallButton(g, mouseX, mouseY, cx + 8, cy + 38, 58, 12,
                            Component.translatable("screen.zhushenspace.shop.claim"));
                }
                String detail = Component.translatable("screen.zhushenspace.shop.detail").getString();
                g.drawString(font, detail, cx + cw - font.width(detail) - 8, cy + 39, ACCENT, true);
            } else {
                boolean affordable = ClientProgressData.branch(school.branchTier()) >= school.branchCost()
                        && ClientProgressData.score() >= school.scoreCost();
                g.drawString(font, Component.translatable("screen.zhushenspace.shop.cost",
                                PlayerCurrencyData.tierLetter(school.branchTier()), school.branchCost(),
                                school.scoreCost()),
                        cx + 8, cy + 39, affordable ? ACCENT : 0xFFFF8A80, true);
                String req = Component.translatable("screen.zhushenspace.shop.req",
                        Component.translatable(school.reqSkill().nameKey()), school.reqLevel()).getString();
                g.drawString(font, req, cx + cw - font.width(req) - 8, cy + 39, TEXT_SUB, true);
            }
        }
    }

    /** 流派详情页：饰品介绍 + 可购买技能列表 */
    private void renderSchoolDetail(GuiGraphics g, int mouseX, int mouseY) {
        SchoolType school = SchoolType.values()[detailSchool];
        int y = listTop + 2;

        // 返回按钮 + 流派名
        renderSmallButton(g, mouseX, mouseY, panelX + 5, y, 30, 12,
                Component.translatable("screen.zhushenspace.shop.back"));
        g.drawString(font, Component.translatable(school.nameKey()), panelX + 42, y + 2, TEXT_MAIN, true);
        y += 14;

        // 装备状态（内力池存在 = 饰品已装备生效）
        boolean equipped = ClientEnergyData.hasPool(ClientEnergyData.NEILI_ID);
        g.drawString(font,
                Component.translatable(equipped
                        ? "screen.zhushenspace.shop.state_equipped"
                        : "screen.zhushenspace.shop.state_unequipped"),
                panelX + 8, y, equipped ? 0xFF4DE0C0 : 0xFFFF8A80, true);
        y += 10;

        // 被动说明 / 流派描述（按实际换行行数完整展示，不截断）
        for (FormattedCharSequence line : font.split(
                Component.translatable("school.zhushenspace." + school.key() + ".passive"), panelW - 16)) {
            g.drawString(font, line, panelX + 8, y, TEXT_SUB, true);
            y += 10;
        }
        for (FormattedCharSequence line : font.split(
                Component.translatable("school.zhushenspace." + school.key() + ".desc"), panelW - 16)) {
            g.drawString(font, line, panelX + 8, y, TEXT_SUB, true);
            y += 10;
        }
        y += 2;

        // 技能列表标题（悬停提示说明）
        g.drawString(font, Component.translatable("screen.zhushenspace.shop.skills_hint"),
                panelX + 8, y, ACCENT, true);
        y += 12;

        // 技能行（固定行高紧凑列表 + 悬停描述提示，像素级滚动 + 滚动条）
        List<SkillAbility> abilities = shopAbilities(school);
        int contentH = abilities.size() * SHOP_ROW_H;
        int viewH = listBottom - y;
        int maxScroll = Math.max(0, contentH - viewH);
        detailScroll = Mth.clamp(detailScroll, 0, maxScroll);

        g.enableScissor(panelX + 1, y, panelX + panelW - 1, listBottom);
        List<FormattedCharSequence> hoverTip = null;
        int ry = y - detailScroll;
        for (SkillAbility ability : abilities) {
            if (ry + SHOP_ROW_H >= y && ry < listBottom) {
                List<FormattedCharSequence> tip =
                        renderShopSkillRow(g, mouseX, mouseY, school, ability, ry);
                if (tip != null) hoverTip = tip;
            }
            ry += SHOP_ROW_H;
        }
        g.disableScissor();

        // 滚动条指示（内容溢出时）
        if (maxScroll > 0 && viewH > 0) {
            int trackX = panelX + panelW - 5;
            ZsTheme.scrollbar(g, trackX, y, listBottom, contentH, detailScroll, maxScroll);
        }

        // 悬停提示（在剪裁区外绘制，避免被裁切）
        if (hoverTip != null) {
            g.renderTooltip(font, hoverTip, mouseX, mouseY);
        }
    }

    /**
     * 详情页单个技能行（固定高 24px）：图标 + 名称 + 类型标签 + 价格/购买按钮。
     * 悬停整行（购买按钮除外）返回完整描述提示行，由调用方在剪裁区外绘制。
     */
    private List<FormattedCharSequence> renderShopSkillRow(GuiGraphics g, int mouseX, int mouseY,
                                                           SchoolType school, SkillAbility ability, int ry) {
        int cx = panelX + 5;
        int cw = panelW - 10;
        boolean hover = over(mouseX, mouseY, cx, ry, cw, SHOP_ROW_H - 1);
        ZsTheme.row(g, cx, ry, cw, SHOP_ROW_H - 1, hover, true);

        // 图标（16×16）+ 名称 + 类型标签
        Component name = Component.translatable(ability.nameKey());
        g.blit(ability.iconTexture(), cx + 4, ry + 4, 16, 16, 0f, 0f, 32, 32, 32, 32);
        g.drawString(font, name, cx + 24, ry + 8, TEXT_MAIN, true);
        Component tag = Component.translatable((ability == SkillAbility.EIGHT_POWERS
                || ability == SkillAbility.COILING_SILK)
                ? "screen.zhushenspace.shop.tag_passive"
                : "screen.zhushenspace.shop.tag_active");
        g.drawString(font, tag, cx + 24 + font.width(name) + 6, ry + 8, 0xFF7FA6C0, true);

        // 购买信息（价格按技能：化劲 B+2000，高阶 C+1000，招式 C+800）
        boolean purchased = ClientProgressData.skillPurchased(school.ordinal(), ability.ordinal());
        int scoreCost = ProgressManager.abilityScoreCost(ability);
        int branchTier = ProgressManager.abilityBranchTier(ability);
        int branchCost = ProgressManager.abilityBranchCost(ability);
        boolean reqMet = ability != SkillAbility.EIGHT_POWERS || ProgressManager.eightPowersPrereqMet(
                id -> ClientProgressData.skillPurchased(school.ordinal(), id));
        boolean affordable = reqMet
                && ClientProgressData.branch(branchTier) >= branchCost
                && ClientProgressData.score() >= scoreCost;
        boolean overBuy = false;
        if (purchased) {
            String owned = Component.translatable("screen.zhushenspace.shop.skill_owned").getString();
            g.drawString(font, owned, cx + cw - font.width(owned) - 6, ry + 8, GOLD, true);
        } else if (!reqMet) {
            String req = Component.translatable("screen.zhushenspace.shop.ep_prereq").getString();
            g.drawString(font, req, cx + cw - font.width(req) - 6, ry + 8, 0xFF5A7A8C, true);
        } else {
            String cost = Component.translatable("screen.zhushenspace.shop.move_cost_short",
                    PlayerCurrencyData.tierLetter(branchTier),
                    branchCost, scoreCost).getString();
            g.drawString(font, cost, cx + cw - 48 - font.width(cost) - 4, ry + 8,
                    affordable ? TEXT_SUB : 0xFFFF8A80, true);
            if (affordable) buyGlow(g, cx + cw - 44, ry + 6, 40, 12);
            renderSmallButton(g, mouseX, mouseY, cx + cw - 44, ry + 6, 40, 12,
                    Component.translatable("screen.zhushenspace.shop.buy"));
            overBuy = over(mouseX, mouseY, cx + cw - 44, ry + 6, 40, 12);
        }

        // 悬停提示：技能名 + 价格/状态 + 完整描述
        if (hover && !overBuy) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(name.getVisualOrderText());
            if (purchased) {
                lines.add(Component.translatable("screen.zhushenspace.shop.skill_owned")
                        .withStyle(ChatFormatting.GOLD).getVisualOrderText());
            } else if (!reqMet) {
                lines.add(Component.translatable("screen.zhushenspace.shop.ep_prereq")
                        .withStyle(ChatFormatting.GRAY).getVisualOrderText());
            } else {
                lines.add(Component.translatable("screen.zhushenspace.shop.move_cost_short",
                                PlayerCurrencyData.tierLetter(branchTier), branchCost, scoreCost)
                        .withStyle(affordable ? ChatFormatting.GRAY : ChatFormatting.RED).getVisualOrderText());
            }
            lines.addAll(font.split(Component.translatable(ability.descKey()), SHOP_TIP_WIDTH));
            return lines;
        }
        return null;
    }

    /** 商城页点击 */
    private boolean handleShopClick(double mouseX, double mouseY) {
        // 详情页
        if (detailSchool >= 0 && detailSchool < SchoolType.COUNT) {
            SchoolType school = SchoolType.values()[detailSchool];
            int y = listTop + 2;
            // 返回
            if (over(mouseX, mouseY, panelX + 5, y, 30, 12)) {
                detailSchool = -1;
                detailScroll = 0;
                playClick(1.0f);
                return true;
            }

            // 技能行购买按钮（与渲染共用同一布局计算：固定行高）
            y = detailListY(school);
            List<SkillAbility> abilities = shopAbilities(school);
            int viewH = listBottom - y;
            detailScroll = Mth.clamp(detailScroll, 0,
                    Math.max(0, abilities.size() * SHOP_ROW_H - viewH));
            int ry = y - detailScroll;
            for (SkillAbility ability : abilities) {
                if (ry >= listBottom) break;
                boolean visible = ry + SHOP_ROW_H >= y;
                if (visible
                        && !ClientProgressData.skillPurchased(school.ordinal(), ability.ordinal())
                        && !(ability == SkillAbility.EIGHT_POWERS && !ProgressManager.eightPowersPrereqMet(
                                id -> ClientProgressData.skillPurchased(school.ordinal(), id)))
                        && over(mouseX, mouseY, panelX + 5 + (panelW - 10) - 44, ry + 6, 40, 12)) {
                    PacketDistributor.sendToServer(new SchoolSkillPurchasePayload(detailSchool, ability.ordinal()));
                    playClick(1.0f);
                    return true;
                }
                ry += SHOP_ROW_H;
            }
            return false;
        }

        // 卡片列表
        for (int i = 0; i < SchoolType.COUNT; i++) {
            SchoolType school = SchoolType.values()[i];
            int cy = shopCardY(i);
            int cx = panelX + 5;
            int cw = panelW - 10;
            if (!over(mouseX, mouseY, cx, cy, cw, 52)) continue;
            boolean owned = ClientProgressData.school(i);
            if (owned) {
                // 领取饰品按钮
                if (!hasEmblemClient() && over(mouseX, mouseY, cx + 8, cy + 38, 58, 12)) {
                    PacketDistributor.sendToServer(new SchoolClaimPayload(i));
                    playClick(1.0f);
                    return true;
                }
                // 进入详情
                detailSchool = i;
                detailScroll = 0;
                playClick(1.0f);
                return true;
            }
            if (ClientSkillData.points()[school.reqSkill().ordinal()] < school.reqLevel()) continue;
            if (over(mouseX, mouseY, cx + cw - 48, cy + 3, 42, 14)) {
                PacketDistributor.sendToServer(new SchoolPurchasePayload(i));
                playClick(1.0f);
                return true;
            }
        }
        return false;
    }

    // ===== 交互 =====

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 底部货币栏：点击打开货币界面（拖拽拼合/拆解）
            if (over(mouseX, mouseY, panelX + 4, panelY + panelH - 16, panelW - 8, 14)) {
                Minecraft.getInstance().setScreen(new CurrencyScreen());
                return true;
            }

            // 主神空间大厅：进入大厅 / 返回主世界
            if (over(mouseX, mouseY, hallX, panelY + 5, 32, TAB_H)) {
                playClick(1.0f);
                PacketDistributor.sendToServer(new EnterHallPayload());
                onClose();
                return true;
            }

            // 能量池界面设置（⚙）：拖拽调整能量池 HUD 位置、缩放
            if (over(mouseX, mouseY, gearX, panelY + 5, 20, TAB_H)) {
                playClick(1.0f);
                Minecraft.getInstance().setScreen(new EnergyUiConfigScreen(this));
                return true;
            }

            // 选项卡切换
            for (int i = 0; i < 4; i++) {
                if (over(mouseX, mouseY, tabX[i], panelY + 5, tabW[i], TAB_H)) {
                    if (tab != Tab.values()[i]) tabChangedAt = ZsAnim.nowMs();
                    tab = Tab.values()[i];
                    dragging = -1;
                    dragFromSlot = -1;
                    dragFromBar = -1;
                    detailSchool = -1;
                    detailScroll = 0;
                    chipScroll = 0;
                    return true;
                }
            }

            switch (tab) {
                case ATTRIBUTES -> {
                    if (handlePointClick(mouseX, mouseY, attrList)) return true;
                }
                case SKILLS -> {
                    if (handlePointClick(mouseX, mouseY, skillList)) return true;
                }
                case PRESET -> {
                    if (handlePresetClick(mouseX, mouseY)) return true;
                }
                case SHOP -> {
                    if (handleShopClick(mouseX, mouseY)) return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 预设页按下：从已解锁列表拾取技能（未购/未生效的太极招式不可拖），或从格子中取出 */
    private boolean handlePresetClick(double mouseX, double mouseY) {
        for (int bar = 0; bar < slots.length; bar++) {
            for (int slot = 0; slot < 9; slot++) {
                if (over(mouseX, mouseY, slotX(slot), slotsY(bar), SLOT_SIZE, SLOT_SIZE)) {
                    if (slots[bar][slot] >= 0) {
                        dragFromBar = bar;
                        dragFromSlot = slot;
                        dragging = slots[bar][slot];
                        slots[bar][slot] = -1;
                        playClick(1.1f);
                        return true;
                    }
                }
            }
        }
        int chipW = (panelW - 24) / 2;
        int chipTop = slotsY(1) + SLOT_SIZE + 8;
        int chipsBottom = listBottom - 14;
        for (ChipPos pos : layoutChips(chipTop, chipW)) {
            boolean folder = pos.item().ability() == null;
            int w = folder ? panelW - 16 : chipW;
            if (!over(mouseX, mouseY, pos.cx(), pos.cy(), w, CHIP_H)) continue;
            // 滚动出可视区的芯片不响应点击
            if (pos.cy() < chipTop || pos.cy() + CHIP_H > chipsBottom) continue;
            if (folder) {
                // 太极拳文件夹：展开 / 收起
                taiChiFolderOpen = !taiChiFolderOpen;
                chipScroll = 0;
                playClick(1.1f);
                return true;
            }
            if (pos.item().locked() || !pos.item().enabled()) continue; // 未购买或未装备饰品
            dragging = pos.item().ability().ordinal();
            dragFromSlot = -1;
            dragFromBar = -1;
            playClick(1.1f);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging >= 0) {
            boolean placed = false;
            for (int bar = 0; bar < slots.length && !placed; bar++) {
                for (int slot = 0; slot < 9; slot++) {
                    if (over(mouseX, mouseY, slotX(slot), slotsY(bar), SLOT_SIZE, SLOT_SIZE)) {
                        slots[bar][slot] = dragging;
                        placed = true;
                        playClick(1.0f);
                        break;
                    }
                }
            }
            if (!placed && dragFromSlot >= 0 && dragFromBar >= 0) {
                // 放回原格子
                slots[dragFromBar][dragFromSlot] = dragging;
            }
            dragging = -1;
            dragFromSlot = -1;
            dragFromBar = -1;
            // 两栏都会随本次释放同步（服务端逐栏校验，未改动的一栏原样回传）
            for (int b = 0; b < slots.length; b++) {
                PacketDistributor.sendToServer(new EquipSkillsPayload(b, slots[b]));
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int dir = -(int) Math.signum(scrollY);
        PointList list = activeList();
        if (list != null) {
            list.scroll += dir * ROW_HEIGHT;
            clampScroll();
            return true;
        }
        if (tab == Tab.PRESET) {
            // 芯片区翻页（滚轮逐行滚动，渲染时按可视行数 clamp）
            chipScroll = Math.max(0, chipScroll + dir);
            return true;
        }
        if (tab == Tab.SHOP && detailSchool >= 0) {
            // 详情页像素级滚动（固定行高，向下滚动查看后续内容，上限在渲染时 clamp）
            detailScroll = Math.max(0, detailScroll + dir * 16);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void playClick(float pitch) {
        ZsTheme.click(pitch);
    }

    /** 构建属性悬停提示框内容（按固定宽度手动换行，传奇加成为金色） */
    private List<FormattedCharSequence> buildTooltip(AttributeType type) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(Component.translatable(type.nameKey()).getVisualOrderText());
        lines.addAll(font.split(Component.translatable(type.descKey()), TOOLTIP_WIDTH));
        lines.addAll(font.split(Component.translatable(type.legendKey()).withStyle(GOLD_STYLE), TOOLTIP_WIDTH));
        return lines;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_E) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 关闭面板时返回背包界面（仿照创造选项卡的体验） */
    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new InventoryScreen(mc.player));
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

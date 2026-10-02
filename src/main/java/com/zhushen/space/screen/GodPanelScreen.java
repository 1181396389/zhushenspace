package com.zhushen.space.screen;

import com.zhushen.space.client.ClientAttributeData;
import com.zhushen.space.client.ClientEnergyData;
import com.zhushen.space.client.ClientProgressData;
import com.zhushen.space.client.ClientSkillData;
import com.zhushen.space.common.HallManager;
import com.zhushen.space.common.ProgressManager;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.PlayerCurrencyData;
import com.zhushen.space.data.SchoolType;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.data.ArtSkill;
import com.zhushen.space.client.ClientArtData;
import com.zhushen.space.common.FeatEffects;
import com.zhushen.space.network.ArtActionPayload;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.data.BuildRules;
import com.zhushen.space.data.BuildCheck;
import com.zhushen.space.data.FeatType;
import com.zhushen.space.client.ClientBuildData;
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

    private enum Tab { ATTRIBUTES, SKILLS, PRESET, SHOP, FEATS }


    private static final int ROW_HEIGHT = 20;
    private static final int HEADER_HEIGHT = 44;
    private static final int TOOLTIP_WIDTH = 175;
    private static final int TAB_H = 14;
    /** 战斗预设巨剑栏缩放（240×30 → 264×33），技能槽 22px */
    private static final float BAR_SCALE = 1.1f;
    private static final int SLOT_SIZE = BladeBar.slotSize(BAR_SCALE);
    private static final int BAR_H = Math.round(BladeBar.H * BAR_SCALE);
    private static final int CHIP_H = 20;

    // ===== 属性 / 技能页状态（两页共用同一套加点列表逻辑） =====
    // 属性列表存「投入 XP」（0~10），技能列表存等级（0~15）；三页共享同一 XP 池
    private final PointList attrList = new PointList(AttributeType.COUNT, BuildRules.ATTR_MAX_XP,
            (i, c) -> c >= BuildRules.ATTR_MAX_XP ? -1 : 1);
    private final PointList skillList = new PointList(SkillType.COUNT, SkillType.MAX_POINTS, this::skillStepUi);
    // 专长（等级掩码）与建卡专长选择：已保存 / 待确认
    private int[] featSaved = new int[FeatType.COUNT], featCur = new int[FeatType.COUNT];
    private int si1Saved = -1, si3aSaved = -1, si3bSaved = -1, si1Cur = -1, si3aCur = -1, si3bCur = -1;
    private int buildRev = -1;

    private int skillStepUi(int i, int level) {
        boolean si1 = FeatType.has(featCur, FeatType.SPECIAL_IDENTITY, 1);
        int disc = si1 ? si1Cur : -1;
        if (!ClientBuildData.created && level >= BuildRules.creationCap(i, disc)) return -1;
        return BuildRules.skillStep(level, i == disc);
    }

    private BuildCheck buildCheck() {
        return BuildCheck.of(ClientBuildData.totalXp, ClientBuildData.created, ClientBuildData.giftedXp,
                attrList.saved, skillList.saved, featSaved, si1Saved, si3aSaved, si3bSaved,
                attrList.cur, skillList.cur, featCur, si1Cur, si3aCur, si3bCur);
    }

    private boolean featDirty() {
        return !java.util.Arrays.equals(featSaved, featCur) || si1Saved != si1Cur || si3aSaved != si3aCur || si3bSaved != si3bCur;
    }

    private boolean buildDirty() {
        return attrList.dirty() || skillList.dirty() || featDirty();
    }

    private void loadFeats() {
        featSaved = ClientBuildData.featMask.clone();
        featCur = featSaved.clone();
        si1Saved = si1Cur = ClientBuildData.si1;
        si3aSaved = si3aCur = ClientBuildData.si3a;
        si3bSaved = si3bCur = ClientBuildData.si3b;
    }

    private void resetBuild() {
        attrList.reset();
        skillList.reset();
        featCur = featSaved.clone();
        si1Cur = si1Saved; si3aCur = si3aSaved; si3bCur = si3bSaved;
    }

    private void commitBuild() {
        PacketDistributor.sendToServer(new com.zhushen.space.network.CommitBuildPayload(
                attrList.cur.clone(), skillList.cur.clone(), featCur.clone(), si1Cur, si3aCur, si3bCur));
        SgStyle.converge();
        System.arraycopy(attrList.cur, 0, attrList.saved, 0, attrList.count);
        System.arraycopy(skillList.cur, 0, skillList.saved, 0, skillList.count);
        attrList.awaitUntil = skillList.awaitUntil = System.currentTimeMillis() + 1500;
        ZsTheme.click(1.2f);
    }
    /** 技能页 pending 数组别名（技能提示按待确认值显示解锁状态） */
    private final int[] skillPoints = skillList.cur;
    private static final int PM_BTN = 11;

    // ===== 预设页状态 =====
    /** 战斗预设页（两柄剑栏 + 技能库，拖拽 / 交换 / 右键卸下 / 筛选搜索 / 撤销），在 init 中创建 */
    private PresetTab presetTab;

    // ===== 商城页状态 =====
    /** 当前查看详情的流派序号（-1 = 卡片列表） */
    private int detailSchool = -1;
    /** 详情页技能列表滚动偏移（行） */
    private int detailScroll = 0;

    private Tab tab = lastTab;
    /** 打开 / 切换选项卡时刻（驱动弹出与内容滑入动画） */
    private long openedAt = -1, tabChangedAt;

    private int panelX, panelY, panelW, panelH;
    private int listTop, listBottom;
    private final int confirmW = 40, resetW = 34, actionH = 13;
    private int confirmX, resetX, actionY;
    private int gearX;
    /** 主神大厅按钮（标签行，齿轮左侧） */
    private int hallX;
    private final int[] tabX = new int[5];
    private final int[] tabW = {44, 44, 62, 40, 40};

    /**
     * 从外部入口打开主神面板：未使用过主神邀请函的玩家无法打开（提示并播放拒绝音效）。
     * 邀请函剧情界面（MeaningOfLifeScreen）直接 new，不经此校验。
     */
    public static boolean tryOpen() {
        Minecraft mc = Minecraft.getInstance();
        if (!com.zhushen.space.client.ClientEnvelopeData.used()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("screen.zhushenspace.godpanel.locked"), true);
                mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.VILLAGER_NO, 1.0f, 0.6f));
            }
            return false;
        }
        mc.setScreen(new GodPanelScreen());
        return true;
    }

    public GodPanelScreen() {
        super(Component.translatable("screen.zhushenspace.godpanel.title"));
        attrList.load(ClientBuildData.attrXp, 0);
        skillList.load(ClientSkillData.points(), 0);
        attrList.freeFn = skillList.freeFn = () -> buildCheck().free;
        loadFeats();
        buildRev = ClientBuildData.revision;
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
        final java.util.function.IntBinaryOperator stepFn;
        java.util.function.IntSupplier freeFn = () -> 0;
        int total, scroll, maxScroll, hovered = -1;

        PointList(int count, int max, java.util.function.IntBinaryOperator stepFn) {
            this.count = count;
            this.max = max;
            this.saved = new int[count];
            this.cur = new int[count];
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
            return freeFn.getAsInt();
        }

        int step(int i) {
            return stepFn.applyAsInt(i, cur[i]);
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
        if (presetTab == null) presetTab = new PresetTab(font);
        com.zhushen.space.client.ClientTrial.notePanel(); // 新手试炼：「打开主神面板」目标
        if (openedAt < 0) {
            openedAt = tabChangedAt = ZsAnim.nowMs();
            SgStyle.rollIn();
        }
        panelW = Math.min(340, this.width - 40);
        // 列表区高度：容纳属性列表，或技能列表 + 底部获得/流派提示（取较大者；屏幕不够时滚动）
        int listH = Math.max(AttributeType.COUNT * ROW_HEIGHT + 4, SkillType.COUNT * ROW_HEIGHT + 22);
        panelH = Math.min(this.height - 20, HEADER_HEIGHT + listH + 28);
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
        for (int i = 0; i < 5; i++) {
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
        attrList.refresh(ClientBuildData.attrXp, 0);
        skillList.refresh(ClientSkillData.points(), 0);
        if (buildRev != ClientBuildData.revision) {
            buildRev = ClientBuildData.revision;
            if (!featDirty()) loadFeats();
            else {
                featSaved = ClientBuildData.featMask.clone();
                si1Saved = ClientBuildData.si1; si3aSaved = ClientBuildData.si3a; si3bSaved = ClientBuildData.si3b;
            }
        }
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
            case FEATS -> renderFeatTab(g, mouseX, mouseY);
        }
        g.pose().popPose();
        int statusEnd = panelX + 8;
        if (tab == Tab.ATTRIBUTES || tab == Tab.SKILLS || tab == Tab.FEATS) statusEnd = renderBuildStatus(g, listBottom + 3);
        if (tab == Tab.ATTRIBUTES) renderKeywordChip(g, mouseX, mouseY, statusEnd, listBottom + 3);
        ZsTheme.endOpen(g);
        if (tab == Tab.SKILLS) renderProfChooser(g, mouseX, mouseY);
        else profChooser = -1;
        renderDiscardConfirm(g, mouseX, mouseY);
    }

    private void renderPanel(GuiGraphics g) {
        if (tab == Tab.SKILLS) {
            // 技能页：整块面板统一为新月同行冷灰外框（不叠星云与法阵）
            XytStyle.chrome(g, panelX, panelY, panelW, panelH);
            return;
        }
        if (tab == Tab.ATTRIBUTES) {
            // 属性页：命运石之门 —— 交错同调外框
            SgStyle.chrome(g, panelX, panelY, panelW, panelH);
            return;
        }
        if (tab == Tab.FEATS) {
            // 专长页：两种领域结界自中缝向两侧展开的外框
            JjkStyle.chrome(g, panelX, panelY, panelW, panelH, tabChangedAt, HEADER_HEIGHT);
            return;
        }
        if (tab == Tab.PRESET) {
            // 战斗预设页：锻铁 + 余烬外框，与剑冢背景统一
            BladeBar.chrome(g, panelX, panelY, panelW, panelH);
            renderBattlefield(g);
            return;
        }
        ZsTheme.panel(g, panelX, panelY, panelW, panelH);
        // 面板中央缓慢旋转的蓝紫魔法阵水印（大黑塔裙摆徽记）
        int sz = Math.min(panelW, panelH) - 40;
        ZsTheme.sigil(g, panelX + panelW / 2f, panelY + panelH / 2f + 10, sz, 0x248C9CFF);
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        // 选项卡
        String[] labels = {
                Component.translatable("screen.zhushenspace.godpanel.tab.attributes").getString(),
                Component.translatable("screen.zhushenspace.godpanel.tab.skills").getString(),
                Component.translatable("screen.zhushenspace.godpanel.tab.preset").getString(),
                Component.translatable("screen.zhushenspace.godpanel.tab.shop").getString(),
                Component.translatable("screen.zhushenspace.godpanel.tab.feats").getString()
        };
        Component[] tabLabels = new Component[5];
        for (int i = 0; i < 5; i++) {
            // 有未确认改动的加点页在标签上打 * 提醒
            boolean dirty = (i == 0 && attrList.dirty()) || (i == 1 && skillList.dirty()) || (i == 4 && featDirty());
            tabLabels[i] = Component.literal(dirty ? labels[i] + "*" : labels[i]);
        }
        if (tab == Tab.SKILLS) {
            XytStyle.tabs(g, font, mouseX, mouseY, tabX, tabW, panelY + 5, TAB_H, tabLabels, tab.ordinal());
        } else if (tab == Tab.ATTRIBUTES) {
            SgStyle.tabs(g, font, mouseX, mouseY, tabX, tabW, panelY + 5, TAB_H, tabLabels, tab.ordinal());
        } else if (tab == Tab.PRESET) {
            BladeBar.tabs(g, font, mouseX, mouseY, tabX, tabW, panelY + 5, TAB_H, tabLabels, tab.ordinal());
        } else {
            ZsTheme.tabs(g, font, mouseX, mouseY, tabX, tabW, panelY + 5, TAB_H, tabLabels, tab.ordinal(), 1);
        }

        // 主神空间大厅（标签行，齿轮左侧）：进入大厅 / 返回主世界
        boolean inHall = minecraft != null && minecraft.player != null
                && minecraft.player.level().dimension() == HallManager.HALL_DIMENSION;
        Component hallLabel = Component.translatable(inHall
                ? "screen.zhushenspace.godpanel.leave_hall"
                : "screen.zhushenspace.godpanel.enter_hall");
        if (tab == Tab.SKILLS) XytStyle.darkButton(g, font, mouseX, mouseY, hallX, panelY + 5, 32, TAB_H, hallLabel);
        else if (tab == Tab.ATTRIBUTES) SgStyle.darkButton(g, font, mouseX, mouseY, hallX, panelY + 5, 32, TAB_H, hallLabel);
        else if (tab == Tab.PRESET) BladeBar.button(g, font, mouseX, mouseY, hallX, panelY + 5, 32, TAB_H, hallLabel);
        else renderSmallButton(g, mouseX, mouseY, hallX, panelY + 5, 32, TAB_H, hallLabel);
        if (over(mouseX, mouseY, hallX, panelY + 5, 32, TAB_H)) {
            g.renderTooltip(font, List.of(Component.translatable(
                    inHall ? "screen.zhushenspace.hall.tooltip_leave"
                           : "screen.zhushenspace.hall.tooltip_enter").getVisualOrderText()), mouseX, mouseY);
        }

        // 能量池界面设置入口（⚙，标签行最右）
        if (tab == Tab.SKILLS) XytStyle.darkButton(g, font, mouseX, mouseY, gearX, panelY + 5, 20, TAB_H, Component.literal("⚙"));
        else if (tab == Tab.ATTRIBUTES) SgStyle.darkButton(g, font, mouseX, mouseY, gearX, panelY + 5, 20, TAB_H, Component.literal("⚙"));
        else if (tab == Tab.PRESET) BladeBar.button(g, font, mouseX, mouseY, gearX, panelY + 5, 20, TAB_H, Component.literal("⚙"));
        else renderSmallButton(g, mouseX, mouseY, gearX, panelY + 5, 20, TAB_H, Component.literal("⚙"));
        if (over(mouseX, mouseY, gearX, panelY + 5, 20, TAB_H)) {
            g.renderTooltip(font, List.of(Component.translatable(
                    "screen.zhushenspace.energy_config.entry").getVisualOrderText()), mouseX, mouseY);
        }

        // 分隔线
        if (tab == Tab.ATTRIBUTES) SgStyle.rule(g, panelX + 4, panelX + panelW - 4, panelY + HEADER_HEIGHT - 3);
        else if (tab == Tab.PRESET) BladeBar.separator(g, panelX + 4, panelX + panelW - 4, panelY + HEADER_HEIGHT - 3);
        else ZsTheme.separator(g, panelX + 4, panelX + panelW - 4, panelY + HEADER_HEIGHT - 3);

        // 第二行统计（按选项卡）
        switch (tab) {
            case SKILLS -> renderXytHeader(g, mouseX, mouseY);
            case ATTRIBUTES -> renderSgHeader(g, mouseX, mouseY);
            case PRESET -> {
                List<FormattedCharSequence> lines = font.split(PresetTab.hint(), panelW - 16);
                int y = panelY + 22;
                for (int i = 0; i < lines.size() && i < 2; i++) {
                    g.drawString(font, lines.get(i), panelX + 8, y, BladeBar.IRON_SUB, true);
                    y += 10;
                }
            }
            case SHOP -> g.drawString(font,
                    Component.translatable("screen.zhushenspace.shop.hint"),
                    panelX + 8, panelY + 26, TEXT_SUB, true);
            case FEATS -> { }
        }

        // 生命 / 伤势显示已移至战斗模式 HUD（WoundHudRenderer）

        // 底部货币栏（主神空间资产：支线 + 奖励点数）
        String currency = Component.translatable("screen.zhushenspace.currency",
                ClientProgressData.branch(0), ClientProgressData.branch(1),
                ClientProgressData.branch(2), ClientProgressData.branch(3),
                ClientProgressData.branch(4), ClientProgressData.score()).getString();
        g.drawString(font, currency, panelX + 8, panelY + panelH - 12,
                tab == Tab.SKILLS ? XytStyle.ORANGE : tab == Tab.PRESET ? BladeBar.EMBER
                        : tab == Tab.ATTRIBUTES ? SgStyle.NIXIE : CURRENCY, tab != Tab.SKILLS);
    }

    /** 专长页内容：专长暂未开放（占位） */
    private final JjkDomainGame domainGame = new JjkDomainGame();

    private int[] gameToggleRect() {
        Component l = gameToggleLabel();
        int w = font.width(l) + 10;
        return new int[]{panelX + panelW - 10 - w, panelY + panelH - 17, w, 11};
    }

    private Component gameToggleLabel() {
        return Component.translatable(com.zhushen.space.client.ClientUiConfig.get().jjkGame
                ? "screen.zhushenspace.feats.game_on" : "screen.zhushenspace.feats.game_off");
    }

    // ===== 专长：可容纳大量条目的购买界面（筛选 + 搜索 + 滚动列表 + 详情） =====

    private static final int FEAT_ROW_H = 15, FEAT_PIP_W = 52, FEAT_PIP_H = 13;
    /** 筛选：0 全部 / 1 普通 / 2 轮回之境 / 3 加点专长 / 4 已拥有 */
    private int featFilter = 0;
    private int featScroll = 0, featMaxScroll = 0;
    private int featSel = 0;
    private int featDescScroll = 0, featDescMax = 0;
    private int featHoverLevel = -1;
    private String featSearch = "";
    private boolean featSearchFocus = false;

    private int featListX() { return panelX + 10; }
    private int featListW() { return Math.max(110, (panelW - 20) * 2 / 5); }
    private int featPaneTop() { return panelY + HEADER_HEIGHT + 15; }
    private int featPaneBottom() { return listBottom - 1; }
    private int featDetailX() { return featListX() + featListW() + 5; }
    private int featDetailW() { return panelX + panelW - 10 - featDetailX(); }
    private int[] featSearchRect() {
        int w = Math.min(96, panelW / 3);
        return new int[]{resetX - w - 6, actionY, w, actionH};
    }

    private String[] featFilterKeys() {
        return new String[]{"build.zhushenspace.filter.all", "feat_category.zhushenspace.normal",
                "feat_category.zhushenspace.reincarnation", "feat_category.zhushenspace.creation",
                "build.zhushenspace.filter.owned"};
    }

    private int[] featChipX = new int[5], featChipW = new int[5];

    private List<FeatType> featVisible() {
        List<FeatType> out = new ArrayList<>();
        String q = featSearch.toLowerCase(java.util.Locale.ROOT).trim();
        for (FeatType f : FeatType.VALUES) {
            switch (featFilter) {
                case 1 -> { if (f.category != FeatType.Category.NORMAL) continue; }
                case 2 -> { if (f.category != FeatType.Category.REINCARNATION) continue; }
                case 3 -> { if (f.category != FeatType.Category.CREATION) continue; }
                case 4 -> { if (featCur[f.ordinal()] == 0) continue; }
                default -> { }
            }
            if (!q.isEmpty()) {
                String n = Component.translatable(f.nameKey()).getString().toLowerCase(java.util.Locale.ROOT);
                if (!n.contains(q) && !f.key.contains(q)) continue;
            }
            out.add(f);
        }
        return out;
    }

    private static int catColor(FeatType.Category c) {
        return switch (c) {
            case NORMAL -> 0xFF8FD18F;
            case REINCARNATION -> 0xFFB48CFF;
            case CREATION -> JjkStyle.GOJO;
        };
    }

    private boolean featEditable(FeatType f) {
        return !(f.creationOnly() && ClientBuildData.created);
    }

    private String skillLabel(int sk) {
        return sk < 0 ? Component.translatable("build.zhushenspace.none").getString()
                : Component.translatable(SkillType.values()[sk].nameKey()).getString();
    }

    private boolean featPrereqOk(FeatType f) {
        int[] al = new int[AttributeType.COUNT];
        for (int i = 0; i < al.length; i++) al[i] = com.zhushen.space.data.BuildRules.attrLevel(attrList.cur[i]);
        return f.prereqMet(al, skillList.cur);
    }

    private int featTopLevel(int k) {
        int m = featCur[k] & FeatType.LEVEL_BITS;
        return m == 0 ? 0 : 31 - Integer.numberOfLeadingZeros(m);
    }

    /** 详情区布局：返回 {pipsY, pipsPerRow, choicesY, descY} */
    private int[] featDetailLayout(FeatType f) {
        int y = featPaneTop() + 4;
        int perRow = Math.max(1, (featDetailW() - 8) / (FEAT_PIP_W + 3));
        int pipsY = y + 24;
        int n = f.maxLevel - f.minLevel + 1;
        int rows = (n + perRow - 1) / perRow;
        int choicesY = pipsY + rows * (FEAT_PIP_H + 3) + 2;
        int choices = featChoiceCount(f);
        int descY = choicesY + choices * 14 + (choices > 0 ? 2 : 0);
        return new int[]{pipsY, perRow, choicesY, descY};
    }

    private int featChoiceCount(FeatType f) {
        int k = f.ordinal();
        if (f == FeatType.SPECIAL_IDENTITY) return ((featCur[k] & 2) != 0 ? 1 : 0) + ((featCur[k] & 8) != 0 ? 2 : 0);
        if (f == FeatType.BARBARIAN) return (featCur[k] & FeatType.LEVEL_BITS) != 0 ? 1 : 0;
        if (f == FeatType.WOLF_CHILD) return (featCur[k] & FeatType.LEVEL_BITS) != 0 ? 4 : 0;
        return 0;
    }

    private int[] featPipRect(FeatType f, int level, int[] lay) {
        int idx = level - f.minLevel;
        int x = featDetailX() + 4 + (idx % lay[1]) * (FEAT_PIP_W + 3);
        int y = lay[0] + (idx / lay[1]) * (FEAT_PIP_H + 3);
        return new int[]{x, y, FEAT_PIP_W, FEAT_PIP_H};
    }

    private int[] featChoiceRect(int idx, int[] lay) {
        return new int[]{featDetailX() + 4, lay[2] + idx * 14, featDetailW() - 8, 12};
    }

    private int featAnimSel = -1;
    private long featSelAt;

    private void renderFeatCards(GuiGraphics g, int mouseX, int mouseY, float fade) {
        // —— 头部：标题 + 搜索 + 重置 / 确认 ——
        g.drawString(font, Component.translatable("screen.zhushenspace.godpanel.tab.feats"), panelX + 10, actionY + 3,
                ZsAnim.withAlpha(0xFFF2ECE0, fade), true);
        {
            int tw = font.width(Component.translatable("screen.zhushenspace.godpanel.tab.feats"));
            float grow = ZsAnim.easeOutCubic(fade);
            JjkStyle.line(g, panelX + 8, actionY + 13, panelX + 8 + (tw + 10) * grow, actionY + 12, 2, JjkStyle.alpha(JjkStyle.SUKUNA, fade));
        }
        int[] sr = featSearchRect();
        g.fill(sr[0], sr[1], sr[0] + sr[2], sr[1] + sr[3], 0xCC06070C);
        g.fill(sr[0], sr[1] + sr[3] - 1, sr[0] + sr[2], sr[1] + sr[3], featSearchFocus ? JjkStyle.GOJO : 0xFF3A4050);
        if (featSearchFocus) {
            float fw = ZsAnim.tween(ZsAnim.key(91, sr[0], sr[1]), 1f, 10f);
            int half = (int) (sr[2] / 2f * fw);
            g.fill(sr[0] + sr[2] / 2 - half, sr[1] + sr[3] - 2, sr[0] + sr[2] / 2 + half, sr[1] + sr[3] - 1, JjkStyle.alpha(JjkStyle.GOJO_LIGHT, 0.7f));
        } else ZsAnim.tween(ZsAnim.key(91, sr[0], sr[1]), 0f, 50f);
        g.drawString(font, "咒", sr[0] + sr[2] - 10, sr[1] + (sr[3] - 8) / 2, JjkStyle.alpha(JjkStyle.SUKUNA, 0.5f + 0.5f * ZsAnim.pulse(1600)), false);
        String shown = featSearch.isEmpty() && !featSearchFocus
                ? Component.translatable("build.zhushenspace.search").getString() : featSearch;
        String clip = font.plainSubstrByWidth(shown, sr[2] - 10, true);
        g.drawString(font, clip, sr[0] + 4, sr[1] + (sr[3] - 8) / 2, featSearch.isEmpty() && !featSearchFocus ? 0xFF6A7080 : 0xFFE8EEF8, false);
        if (featSearchFocus && (ZsAnim.nowMs() / 500) % 2 == 0) {
            int cx = sr[0] + 4 + font.width(clip);
            g.fill(cx, sr[1] + 3, cx + 1, sr[1] + sr[3] - 3, 0xFFFFFFFF);
        }
        boolean dirty = buildDirty();
        JjkStyle.button(g, font, mouseX, mouseY, resetX, actionY, resetW, actionH,
                Component.translatable("screen.zhushenspace.reset"), JjkStyle.SUKUNA, dirty);
        JjkStyle.button(g, font, mouseX, mouseY, confirmX, actionY, confirmW, actionH,
                Component.translatable("screen.zhushenspace.apply"), JjkStyle.GOJO, dirty && buildCheck().ok());

        // —— 筛选标签 ——
        String[] keys = featFilterKeys();
        int cx = featListX(), cy = panelY + HEADER_HEIGHT + 1;
        for (int i = 0; i < keys.length; i++) {
            Component c = Component.translatable(keys[i]);
            int w = font.width(c) + 8;
            featChipX[i] = cx;
            featChipW[i] = w;
            boolean sel = featFilter == i, hov = over(mouseX, mouseY, cx, cy, w, 11);
            JjkStyle.talisman(g, font, mouseX, mouseY, cx, cy, w, 11, c, JjkStyle.GOJO, sel, fade);
            cx += w + 3;
        }

        // —— 左：列表 ——
        List<FeatType> vis = featVisible();
        int lx = featListX(), lw = featListW(), top = featPaneTop(), bot = featPaneBottom();
        JjkStyle.cursedPanel(g, lx, top, lw, bot - top, JjkStyle.GOJO, fade, 1);
        featMaxScroll = Math.max(0, vis.size() * FEAT_ROW_H - (bot - top - 2));
        featScroll = Math.max(0, Math.min(featScroll, featMaxScroll));
        if (!vis.isEmpty() && !vis.contains(FeatType.VALUES[Math.min(featSel, FeatType.COUNT - 1)])) featSel = vis.get(0).ordinal();
        g.enableScissor(lx + 1, top + 1, lx + lw - 1, bot - 1);
        {
            int selIdx = vis.indexOf(FeatType.VALUES[Math.min(featSel, FeatType.COUNT - 1)]);
            if (selIdx >= 0) {
                float sy = ZsAnim.tween(ZsAnim.key(92, lx, 0), top + 1 + selIdx * FEAT_ROW_H - featScroll, 18f);
                JjkStyle.selectRow(g, lx + 1, (int) sy, lw - 2, FEAT_ROW_H, catColor(FeatType.VALUES[featSel].category));
            }
        }
        for (int r = 0; r < vis.size(); r++) {
            FeatType f = vis.get(r);
            int ry = top + 1 + r * FEAT_ROW_H - featScroll;
            if (ry + FEAT_ROW_H < top || ry > bot) continue;
            int k = f.ordinal();
            boolean sel = k == featSel, hov = over(mouseX, mouseY, lx, ry, lw - 4, FEAT_ROW_H) && mouseY >= top && mouseY < bot;
            if (!sel && hov) g.fill(lx + 1, ry, lx + lw - 1, ry + FEAT_ROW_H, 0x22FFFFFF);
            g.fill(lx + 1, ry, lx + 3, ry + FEAT_ROW_H, sel ? catColor(f.category) : ZsAnim.withAlpha(catColor(f.category), 0.45f));
            String name = font.plainSubstrByWidth(Component.translatable(f.nameKey()).getString(), lw - 44);
            int nx = lx + 7 + (int) ZsAnim.tween(ZsAnim.key(93, k, 0), sel ? 4f : hov ? 2f : 0f, 14f);
            g.drawString(font, name, nx, ry + 4, featEditable(f) ? 0xFFE8EEF8 : 0xFF8A92A0, false);
            int top2 = featTopLevel(k);
            String lv = featCur[k] == 0 ? "—" : "Lv" + top2 + "/" + f.maxLevel;
            boolean pend = featCur[k] != featSaved[k];
            g.drawString(font, lv, lx + lw - 6 - font.width(lv), ry + 4, pend ? JjkStyle.GOJO : featCur[k] != 0 ? 0xFFF2ECE0 : 0xFF5A6070, false);
            g.fill(lx + 4, ry + FEAT_ROW_H - 1, lx + lw - 4, ry + FEAT_ROW_H, 0x14FFFFFF);
        }
        if (vis.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("build.zhushenspace.empty"), lx + lw / 2, top + 10, 0xFF6A7080);
        }
        g.disableScissor();
        if (featMaxScroll > 0) {
            int trackH = bot - top - 4;
            int barH = Math.max(12, trackH * (bot - top) / (vis.size() * FEAT_ROW_H));
            int by = top + 2 + (trackH - barH) * featScroll / featMaxScroll;
            g.fill(lx + lw - 3, top + 2, lx + lw - 2, bot - 2, 0x22FFFFFF);
            g.fill(lx + lw - 4, by, lx + lw - 1, by + barH, JjkStyle.alpha(JjkStyle.GOJO, 0.6f + 0.3f * ZsAnim.pulse(1200)));
        }

        // —— 右：详情 ——
        int dx = featDetailX(), dw = featDetailW();
        JjkStyle.cursedPanel(g, dx, top, dw, bot - top, JjkStyle.SUKUNA, fade, 2);
        featHoverLevel = -1;
        if (vis.isEmpty()) return;
        FeatType f = FeatType.VALUES[featSel];
        int k = f.ordinal();
        int accent = catColor(f.category);
        int y = top + 4;
        {
            // 切换条目时标题从右侧斩入
            float in = ZsAnim.tween(ZsAnim.key(94, k, 0), 1f, 9f);
            if (featAnimSel != k) { featAnimSel = k; featSelAt = ZsAnim.nowMs(); }
            float t = ZsAnim.easeOutCubic(ZsAnim.clamp01((ZsAnim.nowMs() - featSelAt) / 260f));
            int ox = (int) ((1 - t) * 18);
            g.drawString(font, Component.translatable(f.nameKey()), dx + 5 + ox, y, ZsAnim.withAlpha(0xFFF2ECE0, Math.max(0.1f, t)), true);
            int nw = font.width(Component.translatable(f.nameKey()));
            if (t < 1f) JjkStyle.line(g, dx + 2, y + 9 - t * 8, dx + 10 + nw * t + 8, y + 1 + t * 2, 1, JjkStyle.alpha(0xFFFFFFFF, 1 - t));
            JjkStyle.line(g, dx + 5, y + 21, dx + 5 + (dw - 10) * t, y + 21, 1, JjkStyle.alpha(accent, 0.35f));
            if (in < 0) featSelAt = 0;
        }
        Component cat = Component.translatable(f.category.nameKey());
        g.drawString(font, cat, dx + dw - 5 - font.width(cat), y, accent, false);
        Component pre = Component.translatable("build.zhushenspace.prereq",
                Component.translatable(f.prereqKey()));
        boolean preOk = featPrereqOk(f);
        g.drawString(font, font.plainSubstrByWidth(pre.getString(), dw - 10), dx + 5, y + 11, preOk ? 0xFF8A94A4 : 0xFFFF6A6A, false);
        int[] lay = featDetailLayout(f);
        for (int l = f.minLevel; l <= f.maxLevel; l++) {
            int[] r = featPipRect(f, l, lay);
            boolean own = (featCur[k] & (1 << l)) != 0, saved = (featSaved[k] & (1 << l)) != 0;
            boolean hov = over(mouseX, mouseY, r[0], r[1], r[2], r[3]);
            if (hov) featHoverLevel = l;
            int price = f.creationOnly() ? f.payLowerPrice(l) : f.levelPrice(l);
            String lbl = "Lv" + l + " · " + price;
            JjkStyle.sealPip(g, font, r[0], r[1], r[2], r[3], lbl, accent, own, saved, hov && featEditable(f));
        }
        // 选择项
        if (f == FeatType.SPECIAL_IDENTITY) {
            int idx = 0;
            if ((featCur[k] & 2) != 0) {
                int[] r = featChoiceRect(idx++, lay);
                JjkStyle.button(g, font, mouseX, mouseY, r[0], r[1], r[2], r[3],
                        Component.translatable("build.zhushenspace.si1", skillLabel(si1Cur)), accent, featEditable(f));
            }
            if ((featCur[k] & 8) != 0) {
                int[] r = featChoiceRect(idx++, lay);
                JjkStyle.button(g, font, mouseX, mouseY, r[0], r[1], r[2], r[3],
                        Component.translatable("build.zhushenspace.si3", skillLabel(si3aCur)), accent, featEditable(f));
                r = featChoiceRect(idx, lay);
                JjkStyle.button(g, font, mouseX, mouseY, r[0], r[1], r[2], r[3],
                        Component.translatable("build.zhushenspace.si3", skillLabel(si3bCur)), accent, featEditable(f));
            }
        }
        if (f == FeatType.BARBARIAN && (featCur[k] & FeatType.LEVEL_BITS) != 0) {
            int[] r = featChoiceRect(0, lay);
            int c = FeatType.choice(featCur[k]);
            JjkStyle.button(g, font, mouseX, mouseY, r[0], r[1], r[2], r[3],
                    Component.translatable("build.zhushenspace.barbarian_attr",
                            Component.translatable(AttributeType.values()[Math.max(0, c)].nameKey())),
                    accent, featEditable(f) && featSaved[k] == 0);
        }
        if (f == FeatType.WOLF_CHILD && (featCur[k] & FeatType.LEVEL_BITS) != 0) {
            int m = featCur[k], sm = featSaved[k];
            boolean baseEdit = featEditable(f) && (sm & FeatType.LEVEL_BITS) == 0;
            int[] r = featChoiceRect(0, lay);
            JjkStyle.button(g, font, mouseX, mouseY, r[0], r[1], r[2], r[3],
                    Component.translatable("build.zhushenspace.wolf_variant",
                            Component.translatable(FeatType.wildVariant(m) == 1 ? "build.zhushenspace.wolf_tarzan" : "build.zhushenspace.wolf_wolf")),
                    accent, baseEdit);
            r = featChoiceRect(1, lay);
            JjkStyle.button(g, font, mouseX, mouseY, r[0], r[1], r[2], r[3],
                    Component.translatable("build.zhushenspace.barbarian_attr",
                            Component.translatable(AttributeType.values()[FeatType.wildAttr(m)].nameKey())),
                    accent, baseEdit);
            r = featChoiceRect(2, lay);
            JjkStyle.button(g, font, mouseX, mouseY, r[0], r[1], r[2], r[3],
                    Component.translatable(FeatType.wildLiterate(m) ? "build.zhushenspace.wolf_literate_on" : "build.zhushenspace.wolf_literate_off",
                            FeatType.WILD_BUYOFF_XP),
                    accent, featEditable(f) && !(FeatType.wildLiterate(sm) && (sm & FeatType.LEVEL_BITS) != 0));
            r = featChoiceRect(3, lay);
            JjkStyle.button(g, font, mouseX, mouseY, r[0], r[1], r[2], r[3],
                    Component.translatable(FeatType.wildFearless(m) ? "build.zhushenspace.wolf_fearless_on" : "build.zhushenspace.wolf_fearless_off",
                            FeatType.WILD_BUYOFF_XP),
                    accent, featEditable(f) && !(FeatType.wildFearless(sm) && (sm & FeatType.LEVEL_BITS) != 0));
        }
        // 说明（悬停等级优先，否则当前最高等级，否则最低等级），可滚动
        int showL = featHoverLevel >= 0 ? featHoverLevel : Math.max(f.minLevel, featTopLevel(k));
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(Component.literal("Lv" + showL).withStyle(s2 -> s2.withColor(accent)).getVisualOrderText());
        lines.addAll(font.split(Component.translatable(f.levelDescKey(showL)), dw - 14));
        if (f == FeatType.SUPERNATURAL_IDENTITY && (featCur[k] & (1 << 5)) != 0) {
            lines.addAll(font.split(Component.translatable(ClientBuildData.pendingExchange
                    ? "build.zhushenspace.exchange_pending" : "build.zhushenspace.exchange_note")
                    .withStyle(net.minecraft.ChatFormatting.GRAY), dw - 14));
        }
        if (!featEditable(f)) {
            lines.addAll(font.split(Component.translatable("build.zhushenspace.creation_locked")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY), dw - 14));
        }
        int descTop = lay[3], descH = bot - 3 - descTop;
        for (int q = dx + 4; q < dx + dw - 4; q += 4) g.fill(q, descTop - 2, q + 2, descTop - 1, 0x33FFFFFF);
        featDescMax = Math.max(0, lines.size() * 10 - descH);
        featDescScroll = Math.max(0, Math.min(featDescScroll, featDescMax));
        if (descH > 8) {
            g.enableScissor(dx + 1, descTop, dx + dw - 1, bot - 2);
            for (int li = 0; li < lines.size(); li++) {
                g.drawString(font, lines.get(li), dx + 6, descTop + li * 10 - featDescScroll, 0xFFC8D0DC, false);
            }
            g.disableScissor();
            if (featDescMax > 0) {
                int by = descTop + (descH - 10) * featDescScroll / featDescMax;
                g.fill(dx + dw - 3, by, dx + dw - 1, by + 10, JjkStyle.alpha(JjkStyle.SUKUNA_GLOW, 0.8f));
            }
        }
    }

    private int cycleSkill(int cur, int dir, boolean allowNone) {
        int n = SkillType.COUNT + (allowNone ? 1 : 0);
        int idx = (allowNone ? cur + 1 : Math.max(0, cur)) + dir;
        idx = ((idx % n) + n) % n;
        return allowNone ? idx - 1 : idx;
    }

    private void toggleFeatLevel(FeatType f, int l) {
        int k = f.ordinal();
        if (!featEditable(f)) { ZsTheme.click(0.5f); return; }
        int bit = 1 << l;
        if ((featSaved[k] & bit) != 0) { ZsTheme.click(0.5f); return; }
        int m = featCur[k] ^ bit;
        if (!f.validMask(m)) {
            // 普通 / 轮回专长：点高级时自动补齐前置等级，点低级时连同更高等级一起取消
            if ((featCur[k] & bit) == 0) {
                for (int q = f.minLevel; q <= l; q++) m |= 1 << q;
            } else {
                m = featCur[k];
                for (int q = l; q <= f.maxLevel; q++) if ((featSaved[k] & (1 << q)) == 0) m &= ~(1 << q);
            }
            if (!f.validMask(m)) { ZsTheme.click(0.5f); return; }
        }
        if (f == FeatType.BARBARIAN) m = (m & FeatType.LEVEL_BITS) == 0 ? 0 : FeatType.withChoice(m, Math.max(0, FeatType.choice(m)));
        if (f == FeatType.WOLF_CHILD) m = (m & FeatType.LEVEL_BITS) == 0 ? 0 : FeatType.withChoice(m, Math.max(0, FeatType.choice(m)));
        featCur[k] = m;
        if (f == FeatType.SPECIAL_IDENTITY) {
            if ((m & 2) == 0) si1Cur = -1;
            else if (si1Cur < 0) si1Cur = 0;
            if ((m & 8) == 0) { si3aCur = -1; si3bCur = -1; }
        }
        ZsTheme.click((m & bit) != 0 ? 1.2f : 0.8f);
    }

    private boolean handleFeatClick(double mouseX, double mouseY, int button) {
        int[] sr = featSearchRect();
        boolean inSearch = over(mouseX, mouseY, sr[0], sr[1], sr[2], sr[3]);
        if (inSearch) {
            featSearchFocus = true;
            if (button == 1) { featSearch = ""; featScroll = 0; }
            return true;
        }
        featSearchFocus = false;
        if (button == 0 && buildDirty() && over(mouseX, mouseY, resetX, actionY, resetW, actionH)) {
            resetBuild();
            ZsTheme.click(0.8f);
            return true;
        }
        if (button == 0 && buildDirty() && over(mouseX, mouseY, confirmX, actionY, confirmW, actionH)) {
            if (buildCheck().ok()) commitBuild();
            else ZsTheme.click(0.5f);
            return true;
        }
        int cy = panelY + HEADER_HEIGHT + 1;
        for (int i = 0; i < featChipX.length; i++) {
            if (button == 0 && over(mouseX, mouseY, featChipX[i], cy, featChipW[i], 11)) {
                featFilter = i;
                featScroll = 0;
                ZsTheme.click(1.0f);
                return true;
            }
        }
        int lx = featListX(), lw = featListW(), top = featPaneTop(), bot = featPaneBottom();
        List<FeatType> vis = featVisible();
        if (over(mouseX, mouseY, lx, top, lw, bot - top)) {
            int r = (int) ((mouseY - top - 1 + featScroll) / FEAT_ROW_H);
            if (r >= 0 && r < vis.size()) {
                if (featSel != vis.get(r).ordinal()) {
                    featSel = vis.get(r).ordinal();
                    featDescScroll = 0;
                    ZsTheme.click(1.1f);
                }
                return true;
            }
            return false; // 空白处：交给术式演练
        }
        if (vis.isEmpty()) return false;
        FeatType f = FeatType.VALUES[featSel];
        int k = f.ordinal();
        int[] lay = featDetailLayout(f);
        if (button == 0) {
            for (int l = f.minLevel; l <= f.maxLevel; l++) {
                int[] r = featPipRect(f, l, lay);
                if (over(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                    toggleFeatLevel(f, l);
                    return true;
                }
            }
        }
        if (f == FeatType.BARBARIAN && featEditable(f) && featSaved[k] == 0 && (featCur[k] & FeatType.LEVEL_BITS) != 0 && (button == 0 || button == 1)) {
            int[] r = featChoiceRect(0, lay);
            if (over(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                int c = (Math.max(0, FeatType.choice(featCur[k])) + (button == 0 ? 1 : 2)) % 3;
                featCur[k] = FeatType.withChoice(featCur[k], c);
                ZsTheme.click(1.0f);
                return true;
            }
        }
        if (f == FeatType.WOLF_CHILD && featEditable(f) && (featCur[k] & FeatType.LEVEL_BITS) != 0 && (button == 0 || button == 1)) {
            int m = featCur[k], sm = featSaved[k];
            boolean saved = (sm & FeatType.LEVEL_BITS) != 0;
            int v = FeatType.wildVariant(m), a = FeatType.wildAttr(m);
            boolean lit = FeatType.wildLiterate(m), fear = FeatType.wildFearless(m);
            int hit = -1;
            for (int i = 0; i < 4; i++) {
                int[] r = featChoiceRect(i, lay);
                if (over(mouseX, mouseY, r[0], r[1], r[2], r[3])) hit = i;
            }
            boolean ok = false;
            if (hit == 0 && !saved) { v ^= 1; ok = true; }                       // 变体：建卡保存后锁定
            else if (hit == 1 && !saved) { a = (a + (button == 0 ? 1 : 2)) % 3; ok = true; }
            else if (hit == 2 && !(saved && FeatType.wildLiterate(sm))) { lit = !lit; ok = true; }   // 已消除的缺陷不可恢复
            else if (hit == 3 && !(saved && FeatType.wildFearless(sm))) { fear = !fear; ok = true; }
            if (hit >= 0) {
                if (ok) {
                    featCur[k] = FeatType.withChoice(m, FeatType.wildChoice(v, a, lit, fear));
                    ZsTheme.click(1.0f);
                } else ZsTheme.click(0.5f);
                return true;
            }
        }
        if (f == FeatType.SPECIAL_IDENTITY && featEditable(f) && (button == 0 || button == 1)) {
            int dir = button == 0 ? 1 : -1;
            int idx = 0;
            if ((featCur[k] & 2) != 0) {
                int[] r = featChoiceRect(idx++, lay);
                if (over(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                    si1Cur = cycleSkill(si1Cur, dir, false);
                    ZsTheme.click(1.0f);
                    return true;
                }
            }
            if ((featCur[k] & 8) != 0) {
                int[] r = featChoiceRect(idx++, lay);
                if (over(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                    do si3aCur = cycleSkill(si3aCur, dir, true); while (si3aCur >= 0 && si3aCur == si3bCur);
                    ZsTheme.click(1.0f);
                    return true;
                }
                r = featChoiceRect(idx, lay);
                if (over(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                    do si3bCur = cycleSkill(si3bCur, dir, true); while (si3bCur >= 0 && si3bCur == si3aCur);
                    ZsTheme.click(1.0f);
                    return true;
                }
            }
        }
        return false;
    }

    /** 专长页滚轮：列表 / 详情说明 */
    private boolean scrollFeats(double mouseX, double mouseY, int dir) {
        int top = featPaneTop(), bot = featPaneBottom();
        if (over(mouseX, mouseY, featListX(), top, featListW(), bot - top)) {
            featScroll = Math.max(0, Math.min(featMaxScroll, featScroll + dir * FEAT_ROW_H));
            return true;
        }
        if (over(mouseX, mouseY, featDetailX(), top, featDetailW(), bot - top)) {
            featDescScroll = Math.max(0, Math.min(featDescMax, featDescScroll + dir * 10));
            return true;
        }
        return false;
    }

    private void renderFeatTab(GuiGraphics g, int mouseX, int mouseY) {
        int[] in = JjkStyle.frameInner(panelX, panelY, panelW, panelH, HEADER_HEIGHT);
        domainGame.setBounds(in[0], in[1], in[2] - 0, in[3] - 6);
        boolean on = com.zhushen.space.client.ClientUiConfig.get().jjkGame;
        float fa = ZsAnim.clamp01((ZsAnim.nowMs() - tabChangedAt - 400) / 400f);
        if (fa > 0.02f) renderFeatCards(g, mouseX, mouseY, fa);
        if (on && ZsAnim.nowMs() - tabChangedAt > 900) domainGame.render(g, mouseX, mouseY);
        else domainGame.reset();
        if (ZsAnim.nowMs() - tabChangedAt > 700) {
            int[] r = gameToggleRect();
            JjkStyle.button(g, font, mouseX, mouseY, r[0], r[1], r[2], r[3], gameToggleLabel(),
                    on ? JjkStyle.GOJO : 0xFF606060, true);
        }
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
    // ===== 属性 / 技能页（共用） =====

    // ===== 技能页：新月同行风格（纪念） =====

    /** 技能页纸面区域：标签行下方至列表底部 */
    private int xytTop() {
        return panelY + 20;
    }

    /** 技能页头部：档案纸底 + 标题牌 + 英文注释 / π 纸带 + 点数计 + 重置/确认 + 刻度分隔线 */
    private void renderXytHeader(GuiGraphics g, int mouseX, int mouseY) {
        int top = xytTop();
        XytStyle.paper(g, panelX + 1, top, panelW - 2, listBottom + 2 - top);

        int y = actionY;
        int x = XytStyle.titlePlate(g, font, panelX + 7, y,
                Component.translatable("screen.zhushenspace.godpanel.tab.skills"));
        XytStyle.tiny(g, font, "SKILL ARCHIVE / " + String.format("%02d", SkillType.COUNT), x + 4, y + 1, XytStyle.INK);
        XytStyle.piTape(g, font, x + 4, y + 7, 62);

        XytStyle.counter(g, font, x + 72, y + 1,
                Component.translatable("screen.zhushenspace.xyt.points").getString(), skillList.free());

        boolean dirty = buildDirty();
        XytStyle.button(g, font, mouseX, mouseY, resetX, actionY, resetW, actionH,
                Component.translatable("screen.zhushenspace.reset"), dirty, false);
        XytStyle.button(g, font, mouseX, mouseY, confirmX, actionY, confirmW, actionH,
                Component.translatable("screen.zhushenspace.apply"), dirty && buildCheck().ok(), true);

        XytStyle.rule(g, panelX + 6, panelX + panelW - 6, panelY + HEADER_HEIGHT - 3);
    }

    /** 技能列表：编号 + 名称 + 等级刻度计 + 等级 + 消耗 + ±；逐行错峰滑入，切页时橙色扫描线 */
    private void renderXytSkillTab(GuiGraphics g, int mouseX, int mouseY) {
        PointList list = skillList;
        list.hovered = -1;
        int x0 = panelX + 6;
        int rw = panelW - 15;
        long now = ZsAnim.nowMs();
        g.enableScissor(panelX + 1, listTop, panelX + panelW - 1, listBottom);
        for (int i = 0; i < list.count; i++) {
            if (!rowVisible(list, i)) continue;
            int ry = rowY(list, i);
            if (over(mouseX, mouseY, panelX + 5, ry, panelW - 10, ROW_HEIGHT)
                    && mouseY >= listTop && mouseY < listBottom) list.hovered = i;
            // 错峰滑入（每行延迟 35ms）
            float e = ZsAnim.easeOutCubic((now - tabChangedAt - i * 35L) / 260f);
            g.pose().pushPose();
            g.pose().translate((1 - e) * 14, 0, 0);
            renderXytRow(g, mouseX, mouseY, list, i, x0, ry, rw);
            g.pose().popPose();
        }
        // 自动获得 / 流派状态：橙色方点 + 墨色文字
        int footerY = rowY(list, list.count) + 4;
        if (ClientEnergyData.hasPool(ClientEnergyData.NEILI_ID)) {
            g.fill(x0 + 2, footerY + 2, x0 + 5, footerY + 5, XytStyle.ORANGE);
            g.drawString(font, Component.translatable("screen.zhushenspace.skill.acquired_neili"),
                    x0 + 9, footerY, XytStyle.INK, false);
            footerY += 10;
        }
        if (ClientProgressData.taiChiUnlocked()) {
            g.fill(x0 + 2, footerY + 2, x0 + 5, footerY + 5, XytStyle.ORANGE);
            g.drawString(font, Component.translatable("screen.zhushenspace.skill.school_owned"),
                    x0 + 9, footerY, XytStyle.INK, false);
        }
        XytStyle.scan(g, panelX + 1, listTop, panelW - 2, listBottom - listTop, tabChangedAt);
        g.disableScissor();
        XytStyle.scrollbar(g, panelX + panelW - 6, listTop + 2, listBottom - 2,
                list.count * ROW_HEIGHT + 22, list.scroll, list.maxScroll);

        if (list.hovered >= 0 && mouseX < minusX()) {
            g.renderTooltip(font, buildSkillTooltip(SkillType.values()[list.hovered]), mouseX, mouseY);
        }
    }

    /** 专业选择入口（技能行内）：[0] 白刃，[1] 枪械；x = -1 表示不可见 */
    private final int[] profChipX = {-1, -1, -1}, profChipY = new int[3], profChipW = new int[3];
    /** 正在选择的专业组（-1 = 未打开选择框） */
    private int profChooser = -1;
    private final int[] profPending = new int[3];

    private static final int PROF_BTN_W = 70, PROF_BTN_H = 16;

    private int[] profBox() {
        int n = com.zhushen.space.data.WeaponCategory.choices(
                com.zhushen.space.data.WeaponCategory.ProfGroup.values()[profChooser]).size();
        int cols = profCols(), rows = (n + cols - 1) / cols;
        int w = PROF_BTN_W * cols + 6 * (cols - 1) + 12, h = 44 + rows * (PROF_BTN_H + 4) + 22;
        return new int[]{panelX + (panelW - w) / 2, panelY + (panelH - h) / 2, w, h};
    }

    /** 选项超过 8 个时排成 3 列，超过 15 个且面板够宽时 4 列 */
    private int profCols() {
        int n = com.zhushen.space.data.WeaponCategory.choices(
                com.zhushen.space.data.WeaponCategory.ProfGroup.values()[profChooser]).size();
        if (n > 15 && panelW >= PROF_BTN_W * 4 + 6 * 3 + 20) return 4; // 白刃组 18 个分类：4 列
        return n > 8 ? 3 : 2;
    }

    /** 专业选择框：列出该组分类，点击即选定（不可更改） */
    private void renderProfChooser(GuiGraphics g, int mouseX, int mouseY) {
        if (profChooser < 0) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xAA000000);
        int[] b = profBox();
        XytStyle.chrome(g, b[0], b[1], b[2], b[3]);
        Component title = Component.translatable(profChooser == 0 ? "screen.zhushenspace.profession.title_blade"
                : profChooser == 2 ? "screen.zhushenspace.profession.title_brawl" : "screen.zhushenspace.profession.title_gun");
        g.drawCenteredString(font, title, b[0] + b[2] / 2, b[1] + 8, XytStyle.INK);
        g.drawCenteredString(font, Component.translatable("screen.zhushenspace.profession.warn"),
                b[0] + b[2] / 2, b[1] + 20, XytStyle.WARN);
        var list = com.zhushen.space.data.WeaponCategory.choices(
                com.zhushen.space.data.WeaponCategory.ProfGroup.values()[profChooser]);
        for (int k = 0; k < list.size(); k++) {
            int cols = profCols();
            int bx = b[0] + 6 + (k % cols) * (PROF_BTN_W + 6), by = b[1] + 36 + (k / cols) * (PROF_BTN_H + 4);
            boolean owned = (ClientSkillData.professionMask(profChooser) & (1 << list.get(k).ordinal())) != 0;
            if (owned) {
                g.fill(bx, by, bx + PROF_BTN_W, by + PROF_BTN_H, 0x55000000);
                g.drawCenteredString(font, Component.translatable(list.get(k).nameKey()).append(" ✔"),
                        bx + PROF_BTN_W / 2, by + 4, 0xFF808080);
            } else {
                XytStyle.darkButton(g, font, mouseX, mouseY, bx, by, PROF_BTN_W, PROF_BTN_H,
                        Component.translatable(list.get(k).nameKey()));
            }
        }
        int cy = b[1] + b[3] - 20;
        XytStyle.darkButton(g, font, mouseX, mouseY, b[0] + b[2] / 2 - 25, cy, 50, 14,
                Component.translatable("gui.cancel"));
        g.pose().popPose();
    }

    private boolean handleProfChooserClick(double mouseX, double mouseY) {
        int[] b = profBox();
        var list = com.zhushen.space.data.WeaponCategory.choices(
                com.zhushen.space.data.WeaponCategory.ProfGroup.values()[profChooser]);
        for (int k = 0; k < list.size(); k++) {
            int cols = profCols();
            int bx = b[0] + 6 + (k % cols) * (PROF_BTN_W + 6), by = b[1] + 36 + (k / cols) * (PROF_BTN_H + 4);
            if (over(mouseX, mouseY, bx, by, PROF_BTN_W, PROF_BTN_H)
                    && (ClientSkillData.professionMask(profChooser) & (1 << list.get(k).ordinal())) == 0) {
                PacketDistributor.sendToServer(new com.zhushen.space.network.ChooseProfessionPayload(
                        profChooser, list.get(k).ordinal()));
                playClick(1.3f);
                profChooser = -1;
                return true;
            }
        }
        int cy = b[1] + b[3] - 20;
        if (over(mouseX, mouseY, b[0] + b[2] / 2 - 25, cy, 50, 14) || !over(mouseX, mouseY, b[0], b[1], b[2], b[3])) {
            profChooser = -1;
            playClick(0.8f);
        }
        return true;
    }

    private void renderXytRow(GuiGraphics g, int mouseX, int mouseY, PointList list, int i, int x0, int ry, int rw) {
        int h = ROW_HEIGHT - 1;
        XytStyle.row(g, x0, ry, rw, h, i == list.hovered);
        int ty = ry + (h - 8) / 2 + 1;
        // 编号（档案式 01~08）+ 竖发丝线
        g.drawString(font, String.format("%02d", i + 1), x0 + 5, ty, XytStyle.INK_FAINT, false);
        g.fill(x0 + 19, ry + 3, x0 + 20, ry + h - 3, XytStyle.HAIRLINE);
        g.drawString(font, Component.translatable(SkillType.values()[i].nameKey()), x0 + 24, ty, XytStyle.INK, false);

        int saved = list.saved[i], cur = list.cur[i];
        int end = XytStyle.gauge(g, font, panelX + 76, ry + (h - 5) / 2, list.max, saved, cur,
                ZsAnim.key(35, i, 0));
        Component lv;
        int lvColor;
        if (cur >= list.max) {
            lv = Component.literal("MAX").withStyle(net.minecraft.ChatFormatting.BOLD);
            lvColor = XytStyle.ORANGE;
        } else {
            lv = Component.literal("Lv." + cur);
            lvColor = cur != saved ? XytStyle.ORANGE : XytStyle.INK;
        }
        g.drawString(font, lv, end + 3, ty, lvColor, false);

        // 专业：技能达到 3、4 时各免费获得一个（加点最多共 3 个），选择后不可更改
        int grp = i == SkillType.BLADE.ordinal() ? 0 : i == SkillType.FIREARMS.ordinal() ? 1 : i == SkillType.BRAWL.ordinal() ? 2 : -1;
        if (grp >= 0) {
            profChipX[grp] = -1;
            int mask = ClientSkillData.professionMask(grp);
            int pending = ClientSkillData.pendingProfessions(grp, saved);
            profPending[grp] = pending;
            int px = end + 3 + font.width(lv) + 6, py = ry + (h - 11) / 2;
            for (var c : com.zhushen.space.data.WeaponCategory.values()) {
                if ((mask & (1 << c.ordinal())) == 0) continue;
                Component name = Component.translatable(c.nameKey());
                int pw = font.width(name) + 8;
                g.fill(px, py, px + pw, py + 11, 0x33000000);
                g.renderOutline(px, py, pw, 11, XytStyle.INK_SUB);
                g.drawString(font, name, px + 4, py + 2, XytStyle.INK, false);
                px += pw + 3;
            }
            if (pending > 0) {
                Component label = Component.translatable("screen.zhushenspace.profession.choose_n", pending);
                int pw = font.width(label) + 8;
                boolean hov = over(mouseX, mouseY, px, py, pw, 11);
                int bg = ZsAnim.pulse(900) > 0.5f ? XytStyle.ORANGE : 0xFFB05A20;
                g.fill(px, py, px + pw, py + 11, ZsAnim.withAlpha(bg, 0.85f));
                g.renderOutline(px, py, pw, 11, hov ? 0xFFFFFFFF : XytStyle.INK_SUB);
                g.drawString(font, label, px + 4, py + 2, 0xFFFFFFFF, false);
                profChipX[grp] = px;
                profChipY[grp] = py;
                profChipW[grp] = pw;
            }
        }

        int by = ry + (ROW_HEIGHT - PM_BTN) / 2;
        int step = list.step(i);
        if (step > 0) {
            String cost = "×" + step;
            g.drawString(font, cost, minusX() - font.width(cost) - 4, ty,
                    list.free() >= step ? XytStyle.INK_SUB : XytStyle.WARN, false);
        }
        XytStyle.stepButton(g, font, mouseX, mouseY, minusX(), by, PM_BTN, false, list.canDown(i));
        XytStyle.stepButton(g, font, mouseX, mouseY, plusX(), by, PM_BTN, true, list.canUp(i));
    }

    // ===== 属性页：命运石之门风格 =====

    /** 属性页头部：世界线变动率探测仪 + 剩余点数辉光管 + 传奇点数 + 重置（冈部白）/ 确认（红莉栖红） */
    private void renderSgHeader(GuiGraphics g, int mouseX, int mouseY) {
        boolean dirty = buildDirty();
        int x = panelX + 7, y = actionY;
        String reading = dirty ? SgStyle.alphaReading(attrList.cur) : SgStyle.STEINS_GATE;
        int end = SgStyle.meter(g, x, y, reading);
        boolean overMeter = over(mouseX, mouseY, x, y, end - x, SgStyle.TUBE_H);

        int lx = end + 6;
        String pts = Component.translatable("screen.zhushenspace.xyt.points").getString();
        g.drawString(font, pts, lx, y + 3, SgStyle.TEXT_SUB, false);
        lx += font.width(pts) + 3;
        lx = SgStyle.nixieNumber(g, lx, y, Math.max(0, attrList.free()), 2) + 6;
        g.drawString(font, "★", lx, y + 3, GOLD, false);
        lx += font.width("★") + 2;
        SgStyle.nixieNumber(g, lx, y, AttributeType.legendaryCount(attrLevels(attrList.cur)), 1);

        SgStyle.button(g, font, mouseX, mouseY, resetX, actionY, resetW, actionH,
                Component.translatable("screen.zhushenspace.reset"), dirty, true);
        SgStyle.button(g, font, mouseX, mouseY, confirmX, actionY, confirmW, actionH,
                Component.translatable("screen.zhushenspace.apply"), dirty && buildCheck().ok(), false);

        if (overMeter) {
            List<FormattedCharSequence> tip = new ArrayList<>();
            tip.add(Component.translatable("screen.zhushenspace.sg.meter").getVisualOrderText());
            tip.addAll(font.split(Component.translatable(dirty
                    ? "screen.zhushenspace.sg.meter_alpha" : "screen.zhushenspace.sg.meter_sg", reading), TOOLTIP_WIDTH));
            g.renderTooltip(font, tip, mouseX, mouseY);
        }
    }

    /** 属性列表：研究所徽章 / Amadeus 水印 + 世界线刻度计；切页时画面撕裂 + 行错位 */
    private void renderSgAttrTab(GuiGraphics g, int mouseX, int mouseY) {
        PointList list = attrList;
        list.hovered = -1;
        int x0 = panelX + 6, rw = panelW - 15;
        g.enableScissor(panelX + 1, listTop, panelX + panelW - 1, listBottom);
        SgStyle.watermarks(g, panelX + 6, listTop, panelW - 12, listBottom - listTop);
        for (int i = 0; i < list.count; i++) {
            if (!rowVisible(list, i)) continue;
            int ry = rowY(list, i);
            if (over(mouseX, mouseY, panelX + 5, ry, panelW - 10, ROW_HEIGHT)
                    && mouseY >= listTop && mouseY < listBottom) list.hovered = i;
            g.pose().pushPose();
            g.pose().translate(SgStyle.jitter(i, tabChangedAt), 0, 0);
            renderSgRow(g, mouseX, mouseY, list, i, x0, ry, rw);
            g.pose().popPose();
        }
        SgStyle.shift(g, panelX + 1, listTop, panelW - 2, listBottom - listTop, tabChangedAt);
        g.disableScissor();
        SgStyle.scrollbar(g, panelX + panelW - 6, listTop + 2, listBottom - 2,
                list.count * ROW_HEIGHT, list.scroll, list.maxScroll);
        if (list.hovered >= 0 && mouseX < minusX()) {
            g.renderTooltip(font, buildTooltip(AttributeType.values()[list.hovered]), mouseX, mouseY);
        }
    }

    private void renderSgRow(GuiGraphics g, int mouseX, int mouseY, PointList list, int i, int x0, int ry, int rw) {
        int h = ROW_HEIGHT - 1;
        boolean hover = i == list.hovered;
        SgStyle.row(g, x0, ry, rw, h, hover, i % 2 != 0);
        int ty = ry + (h - 8) / 2 + 1;
        g.drawString(font, String.format("%02d", i + 1), x0 + 5, ty, hover ? SgStyle.NIXIE : SgStyle.NIXIE_DIM, false);
        g.fill(x0 + 19, ry + 3, x0 + 20, ry + h - 3, 0x33B08A4A);
        g.drawString(font, Component.translatable(AttributeType.values()[i].nameKey()), x0 + 24, ty,
                hover ? 0xFFFFFFFF : SgStyle.TEXT, false);

        int saved = list.saved[i], cur = list.cur[i];
        int end = SgStyle.worldlineGauge(g, panelX + 80, ry + h / 2, list.max, saved, cur, ZsAnim.key(49, i, 0));
        String value = cur >= list.max ? "MAX" : BuildRules.formatAttr(cur) + "/" + BuildRules.ATTR_CAP;
        int vc = cur != saved ? SgStyle.KURISU : cur >= list.max ? SgStyle.NIXIE_HOT : SgStyle.NIXIE;
        g.drawString(font, value, end + 3, ty, vc, false);

        int by = ry + (ROW_HEIGHT - PM_BTN) / 2;
        int step = list.step(i);
        if (step > 0) {
            String cost = "×" + step;
            g.drawString(font, cost, minusX() - font.width(cost) - 4, ty,
                    list.free() >= step ? SgStyle.TEXT_SUB : SgStyle.KURISU, false);
        }
        SgStyle.stepButton(g, mouseX, mouseY, minusX(), by, PM_BTN, false, list.canDown(i));
        SgStyle.stepButton(g, mouseX, mouseY, plusX(), by, PM_BTN, true, list.canUp(i));
    }

    private static int[] attrLevels(int[] xp) {
        int[] r = new int[xp.length];
        for (int i = 0; i < xp.length; i++) r[i] = BuildRules.attrLevel(xp[i]);
        return r;
    }

    /** 建卡 XP 状态行：属性 / 技能 / 专长投入与剩余（建卡时显示各自区间，超出标红） */
    private int renderBuildStatus(GuiGraphics g, int y) {
        BuildCheck c = buildCheck();
        boolean cr = ClientBuildData.created;
        int x = panelX + 8;
        x = statusPart(g, x, y, Component.translatable("build.zhushenspace.attr", c.attr,
                cr ? "" : " (" + BuildRules.ATTR_MIN + "~" + BuildRules.ATTR_MAX + ")"), !cr && (c.attr < BuildRules.ATTR_MIN || c.attr > BuildRules.ATTR_MAX));
        x = statusPart(g, x, y, Component.translatable("build.zhushenspace.skill", c.skillPool,
                cr ? "" : " (" + BuildRules.SKILL_MIN + "~" + BuildRules.SKILL_MAX + ")"), !cr && (c.skillPool < BuildRules.SKILL_MIN || c.skillPool > BuildRules.SKILL_MAX));
        x = statusPart(g, x, y, Component.translatable("build.zhushenspace.feat", c.feat,
                cr ? "" : " (≥" + BuildRules.FEAT_MIN + ")"), !cr && c.feat < BuildRules.FEAT_MIN);
        return statusPart(g, x, y, Component.translatable("build.zhushenspace.free", c.free, ClientBuildData.totalXp), c.free < 0);
    }

    /** 属性页右下：「减伤关键字」标签，悬停列出当前全部伤害降低能力（免疫 / 忽略 / 硬度 / 抵消 / 抗力 / 减免 / 吸收 / 阈值 / 转化 / 易伤） */
    private void renderKeywordChip(GuiGraphics g, int mouseX, int mouseY, int minX, int y) {
        Component label = Component.translatable("dmgkw.zhushenspace.chip",
                com.zhushen.space.client.ClientDefenseData.keywords().size());
        int w = font.width(label) + 10, h = 11;
        int x = Math.max(minX, panelX + panelW - 8 - w);
        int top = y - 2;
        boolean hover = over(mouseX, mouseY, x, top, w, h);
        boolean any = com.zhushen.space.client.DamageKeywordText.any();
        int edge = hover ? SgStyle.NIXIE_HOT : any ? SgStyle.NIXIE : SgStyle.NIXIE_DIM;
        g.fill(x, top, x + w, top + h, hover ? 0xCC2A1A0C : 0x99140C06);
        g.fill(x, top, x + w, top + 1, edge);
        g.fill(x, top + h - 1, x + w, top + h, edge);
        g.fill(x, top, x + 1, top + h, edge);
        g.fill(x + w - 1, top, x + w, top + h, edge);
        g.drawString(font, label, x + 5, y, hover ? 0xFFFFFFFF : any ? SgStyle.TEXT : SgStyle.TEXT_SUB, false);
        if (hover) g.renderTooltip(font, com.zhushen.space.client.DamageKeywordText.tooltip(font, TOOLTIP_WIDTH), mouseX, mouseY);
    }

    private int statusPart(GuiGraphics g, int x, int y, Component text, boolean bad) {
        g.drawString(font, text, x, y, bad ? 0xFFFF5A5A : 0xFFE0E4EA, true);
        return x + font.width(text) + 8;
    }

    /** 当前是否处于属性页（悬停提示框切换为命运石之门风格） */
    public boolean sgStyle() {
        return tab == Tab.ATTRIBUTES;
    }

    /** 当前是否处于技能页（悬停提示框切换为新月同行风格） */
    public boolean xytStyle() {
        return tab == Tab.SKILLS;
    }

    private void renderPointTab(GuiGraphics g, int mouseX, int mouseY, PointList list) {
        if (list == skillList) renderXytSkillTab(g, mouseX, mouseY);
        else renderSgAttrTab(g, mouseX, mouseY);
    }

    /** 加点页点击：+/-、重置、确认 */
    private boolean handlePointClick(double mouseX, double mouseY, PointList list) {
        if (buildDirty() && over(mouseX, mouseY, resetX, actionY, resetW, actionH)) {
            resetBuild();
            ZsTheme.click(0.8f);
            return true;
        }
        if (buildDirty() && over(mouseX, mouseY, confirmX, actionY, confirmW, actionH)) {
            if (buildCheck().ok()) commitBuild();
            else ZsTheme.click(0.5f);
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
            if (ability.owner() != type && ability.altOwner() != type) continue;
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

    /** 巨剑栏左端 x（面板内水平居中） */
    private int barX() {
        return panelX + (panelW - Math.round(BladeBar.W * BAR_SCALE)) / 2;
    }

    /** 第 bar 柄巨剑栏顶端 y */
    private int barY(int bar) {
        return listTop + 6 + bar * (BAR_H + 8);
    }

    private int slotX(int slot) {
        return BladeBar.slotX(barX(), slot, BAR_SCALE);
    }

    /** 第 bar 套预设栏的 y 坐标（A 在上，B 在下） */
    private int slotsY(int bar) {
        return BladeBar.slotY(barY(bar), BAR_SCALE);
    }

    /** 战斗预设背景：无限剑制（燃烧黄昏、空中巨轮、剑冢荒原、升腾火星） */
    private void renderBattlefield(GuiGraphics g) {
        // 铺满整个面板内部（等比放大裁切，覆盖标签行至底栏）
        int top = panelY + 1;
        int x = panelX + 1, w = panelW - 2, h = panelH - 2;
        g.fill(x, top, x + w, top + h, 0xFF120806);
        float sc = Math.max(w / 256f, h / 192f);
        int dw = Math.round(256 * sc), dh = Math.round(192 * sc);
        int dx = x + (w - dw) / 2, dy = top + (h - dh) / 2;
        g.enableScissor(x, top, x + w, top + h);
        ZsAnim.UBW.draw(g, dx, dy, dw, dh);
        g.disableScissor();
        // 顶部标签行 / 说明文字区压暗保证可读；技能芯片区略压暗
        g.fillGradient(x, top, x + w, panelY + HEADER_HEIGHT + 6, 0xCC120806, 0x33120806);
        int chipTop = presetTab.libraryTop(listTop);
        g.fillGradient(x, chipTop, x + w, top + h, 0x44120806, 0xAA120806);
    }

    /** 当前是否处于战斗预设页（悬停提示框切换为锻铁风格） */
    public boolean forgeStyle() {
        return tab == Tab.PRESET;
    }

    private void renderPresetTab(GuiGraphics g, int mouseX, int mouseY) {
        presetTab.render(g, mouseX, mouseY, panelX, panelW, listTop, listBottom);
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

    /** 商店背景：星空宇宙帧动画（银河、螺旋星系、闪烁星辰、流星），覆盖面板内容区 */
    private void renderCosmos(GuiGraphics g) {
        int top = panelY + HEADER_HEIGHT - 2;
        int x = panelX + 1, w = panelW - 2, h = panelY + panelH - 1 - top;
        // 按宽度等比铺满，纵向居中裁切
        int dh = w * 192 / 256;
        g.enableScissor(x, top, x + w, top + h);
        ZsAnim.COSMOS.draw(g, x, top + (h - dh) / 2, w, Math.max(dh, h));
        g.disableScissor();
        g.fillGradient(x, top, x + w, top + 12, 0xCC0A1622, 0x000A1622);
    }

    private void renderShopTab(GuiGraphics g, int mouseX, int mouseY) {
        renderCosmos(g);
        if (detailArt >= 0) {
            renderArtDetail(g, mouseX, mouseY);
            return;
        }
        if (artPage > 0) {
            renderArtPages(g, mouseX, mouseY);
            return;
        }
        if (gearPage) {
            renderGearPage(g, mouseX, mouseY);
            return;
        }
        if (detailSchool >= 0 && detailSchool < SchoolType.COUNT) {
            renderSchoolDetail(g, mouseX, mouseY);
            return;
        }
        renderArtCard(g, mouseX, mouseY);
        renderGearGroupCard(g, mouseX, mouseY);
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

    // ===== 商城：装备与武器 =====

    /** 商城首页第 3 张卡片「装备与武器」→ 进入可滚动的装备页（魔虚罗法阵 + 基础冷兵器） */
    private boolean gearPage = false;

    private int gearGroupCardY() { return shopCardY(SchoolType.COUNT + 1); }

    private static net.minecraft.world.item.Item gearItem(com.zhushen.space.data.ShopGear gear) {
        return switch (gear) {
            case MAHORAGA_WHEEL -> com.zhushen.space.ZhuShenSpace.MAHORAGA_WHEEL.get();
        };
    }

    /** 首页大分类卡：标题 + 件数 + 说明 + 代表物品图标 */
    private void renderGearGroupCard(GuiGraphics g, int mouseX, int mouseY) {
        int cx = panelX + 5, cw = panelW - 10, cy = gearGroupCardY();
        if (cy >= listBottom) return;
        g.flush();
        g.enableScissor(panelX, listTop, panelX + panelW, listBottom);
        ZsTheme.card(g, cx, cy, cw, 52, over(mouseX, mouseY, cx, cy, cw, 52));
        g.drawString(font, Component.translatable("screen.zhushenspace.shop.gear_group"), cx + 8, cy + 5, TEXT_MAIN, true);
        String cnt = Component.translatable("screen.zhushenspace.shop.gear_count",
                com.zhushen.space.data.ShopGear.COUNT + com.zhushen.space.data.MeleeWeapon.COUNT).getString();
        g.drawString(font, cnt, cx + cw - font.width(cnt) - 8, cy + 6, GOLD, true);
        int dy = cy + 17;
        for (FormattedCharSequence l : font.split(Component.translatable("screen.zhushenspace.shop.gear_group_desc"), cw - 16)) {
            if (dy > cy + 30) break;
            g.drawString(font, l, cx + 8, dy, TEXT_SUB, true);
            dy += 10;
        }
        // 代表物品：魔虚罗法阵 + 冷兵器（小图标排成一行）
        String detail = Component.translatable("screen.zhushenspace.shop.detail").getString();
        int ix = cx + 8, ixMax = cx + cw - font.width(detail) - 12 - 16;
        g.renderItem(new net.minecraft.world.item.ItemStack(ZhuShenSpace.MAHORAGA_WHEEL.get()), ix, cy + 35);
        ix += 18;
        for (com.zhushen.space.data.MeleeWeapon w : com.zhushen.space.data.MeleeWeapon.values()) {
            if (ix > ixMax) break;
            g.renderItem(new net.minecraft.world.item.ItemStack(ZhuShenSpace.weaponItem(w)), ix, cy + 35);
            ix += 16;
        }
        g.drawString(font, detail, cx + cw - font.width(detail) - 8, cy + 39, ACCENT, true);
        g.flush();
        g.disableScissor();
    }

    private static final int GEAR_CARD_H = 52, WEAPON_ROW_H = 24, WEAPON_ROW_GAP = 2, GEAR_SECTION_H = 16;

    private int gearContentTop() { return listTop + 18; }

    private int gearContentH() {
        return com.zhushen.space.data.ShopGear.COUNT * (GEAR_CARD_H + 4) + GEAR_SECTION_H
                + (com.zhushen.space.data.MeleeWeapon.COUNT + 1) * (WEAPON_ROW_H + WEAPON_ROW_GAP); // +1 = 弩矢
    }

    private int gearMaxScroll() { return Math.max(0, gearContentH() - (listBottom - gearContentTop())); }

    private int weaponRowY(int i, int base) {
        return base + com.zhushen.space.data.ShopGear.COUNT * (GEAR_CARD_H + 4) + GEAR_SECTION_H + i * (WEAPON_ROW_H + WEAPON_ROW_GAP);
    }

    /** 伤害类型（可选多种时以「/」分隔） */
    private static String kindsText(com.zhushen.space.data.MeleeWeapon w) {
        StringBuilder b = new StringBuilder();
        for (var k : w.kinds) {
            if (b.length() > 0) b.append("/");
            b.append(Component.translatable(k.nameKey()).getString());
        }
        return b.toString();
    }

    /** 武器行副标题：分类 · 伤害 · 重量 */
    private String weaponLine(com.zhushen.space.data.MeleeWeapon w) {
        String dmg = (w == com.zhushen.space.data.MeleeWeapon.KNUCKLE ? "+" : "") + w.damage + w.severity.name()
                + (w.armorPierce > 0 ? " " + Component.translatable("tooltip.zhushenspace.weapon.pierce", w.armorPierce).getString() : "")
                + " " + kindsText(w);
        String kg = (w.weight == Math.floor(w.weight) ? String.valueOf((int) w.weight) : String.valueOf(w.weight)) + " kg";
        return Component.translatable(w.category.nameKey()).getString() + " · " + dmg + " · " + kg;
    }

    private void renderGearPage(GuiGraphics g, int mouseX, int mouseY) {
        int y = listTop + 2;
        renderSmallButton(g, mouseX, mouseY, panelX + 5, y, 30, 12, Component.translatable("screen.zhushenspace.shop.back"));
        g.drawString(font, Component.translatable("screen.zhushenspace.shop.gear_group"), panelX + 42, y + 2, TEXT_MAIN, true);
        String bal = Component.translatable("screen.zhushenspace.shop.score_balance", ClientProgressData.score()).getString();
        g.drawString(font, bal, panelX + panelW - font.width(bal) - 8, y + 2, GOLD, true);

        int top = gearContentTop();
        int maxScroll = gearMaxScroll();
        detailScroll = Mth.clamp(detailScroll, 0, maxScroll);
        int base = top - detailScroll;
        int cx = panelX + 5, cw = panelW - 10 - (maxScroll > 0 ? 5 : 0);
        net.minecraft.world.item.ItemStack hoverStack = net.minecraft.world.item.ItemStack.EMPTY;
        boolean inView = mouseY >= top && mouseY < listBottom;

        g.flush();
        g.enableScissor(panelX, top, panelX + panelW, listBottom);
        // —— 装备（概念武装）——
        for (com.zhushen.space.data.ShopGear gear : com.zhushen.space.data.ShopGear.values()) {
            int cy = base + gear.ordinal() * (GEAR_CARD_H + 4);
            if (cy + GEAR_CARD_H < top || cy >= listBottom) continue;
            ZsTheme.card(g, cx, cy, cw, GEAR_CARD_H, inView && over(mouseX, mouseY, cx, cy, cw, GEAR_CARD_H));
            g.renderItem(new net.minecraft.world.item.ItemStack(gearItem(gear)), cx + 4, cy + 2);
            g.drawString(font, Component.translatable(gear.nameKey()), cx + 23, cy + 5, TEXT_MAIN, true);
            String tag = Component.translatable("screen.zhushenspace.shop.gear_tag", gear.rank.label()).getString();
            g.drawString(font, tag, cx + 23 + font.width(Component.translatable(gear.nameKey())) + 6, cy + 5, GOLD, true);
            buyGlow(g, cx + cw - 48, cy + 3, 42, 14);
            renderSmallButton(g, mouseX, mouseY, cx + cw - 48, cy + 3, 42, 14, Component.translatable("screen.zhushenspace.shop.buy"));
            int dy = cy + 17;
            List<FormattedCharSequence> desc = font.split(Component.translatable(gear.descKey()), cw - 16);
            for (int l = 0; l < desc.size() && l < 2; l++) {
                g.drawString(font, desc.get(l), cx + 8, dy, TEXT_SUB, true);
                dy += 10;
            }
            boolean affordable = ClientProgressData.branch(gear.branchTier) >= gear.branchCost
                    && ClientProgressData.score() >= gear.scoreCost;
            g.drawString(font, Component.translatable("screen.zhushenspace.shop.cost",
                            PlayerCurrencyData.tierLetter(gear.branchTier), gear.branchCost, gear.scoreCost),
                    cx + 8, cy + 39, affordable ? ACCENT : 0xFFFF8A80, true);
            String slot = Component.translatable("screen.zhushenspace.shop.gear_slot").getString();
            g.drawString(font, slot, cx + cw - font.width(slot) - 8, cy + 39, TEXT_SUB, true);
            if (inView && over(mouseX, mouseY, cx + 4, cy + 2, 16, 16)) hoverStack = new net.minecraft.world.item.ItemStack(gearItem(gear));
        }
        // —— 基础冷兵器 ——
        int sy = base + com.zhushen.space.data.ShopGear.COUNT * (GEAR_CARD_H + 4);
        if (sy + GEAR_SECTION_H >= top && sy < listBottom) {
            g.drawString(font, Component.translatable("screen.zhushenspace.shop.cold_weapons"), cx + 3, sy + 4, TEXT_MAIN, true);
            boolean affordable = ClientProgressData.score() >= com.zhushen.space.data.MeleeWeapon.PRICE;
            String price = Component.translatable("screen.zhushenspace.shop.weapon_price", com.zhushen.space.data.MeleeWeapon.PRICE).getString();
            g.drawString(font, price, cx + cw - font.width(price) - 4, sy + 4, affordable ? GOLD : 0xFFFF8A80, true);
            int lx = cx + 3 + font.width(Component.translatable("screen.zhushenspace.shop.cold_weapons")) + 6;
            int rx = cx + cw - font.width(price) - 10;
            if (rx > lx) g.fill(lx, sy + 8, rx, sy + 9, 0x40D8C8FF);
        }
        for (com.zhushen.space.data.MeleeWeapon w : com.zhushen.space.data.MeleeWeapon.values()) {
            int ry = weaponRowY(w.ordinal(), base);
            if (ry + WEAPON_ROW_H < top || ry >= listBottom) continue;
            boolean hover = inView && over(mouseX, mouseY, cx, ry, cw, WEAPON_ROW_H);
            ZsTheme.card(g, cx, ry, cw, WEAPON_ROW_H, hover);
            net.minecraft.world.item.ItemStack st = new net.minecraft.world.item.ItemStack(ZhuShenSpace.weaponItem(w));
            g.renderItem(st, cx + 4, ry + 4);
            g.drawString(font, Component.translatable(w.nameKey()), cx + 24, ry + 3, TEXT_MAIN, true);
            g.drawString(font, weaponLine(w), cx + 24, ry + 13, TEXT_SUB, true);
            int bx = cx + cw - 46;
            if (ClientProgressData.score() >= com.zhushen.space.data.MeleeWeapon.PRICE) buyGlow(g, bx, ry + 5, 40, 14);
            renderSmallButton(g, mouseX, mouseY, bx, ry + 5, 40, 14, Component.translatable("screen.zhushenspace.shop.buy"));
            if (hover && !over(mouseX, mouseY, bx, ry + 5, 40, 14)) hoverStack = st;
        }
        { // 弩矢（轻弩 / 重弩的弹药）
            int ry = weaponRowY(com.zhushen.space.data.MeleeWeapon.COUNT, base);
            if (ry + WEAPON_ROW_H >= top && ry < listBottom) {
                boolean hover = inView && over(mouseX, mouseY, cx, ry, cw, WEAPON_ROW_H);
                ZsTheme.card(g, cx, ry, cw, WEAPON_ROW_H, hover);
                net.minecraft.world.item.ItemStack st = new net.minecraft.world.item.ItemStack(ZhuShenSpace.CROSSBOW_BOLT.get(),
                        ProgressManager.BOLT_COUNT);
                g.renderItem(st, cx + 4, ry + 4);
                g.renderItemDecorations(font, st, cx + 4, ry + 4);
                g.drawString(font, Component.translatable("item.zhushenspace.crossbow_bolt"), cx + 24, ry + 3, TEXT_MAIN, true);
                boolean ok = ClientProgressData.score() >= ProgressManager.BOLT_PRICE;
                g.drawString(font, Component.translatable("screen.zhushenspace.shop.bolt_line", ProgressManager.BOLT_COUNT,
                        ProgressManager.BOLT_PRICE), cx + 24, ry + 13, ok ? TEXT_SUB : 0xFFFF8A80, true);
                int bx = cx + cw - 46;
                if (ok) buyGlow(g, bx, ry + 5, 40, 14);
                renderSmallButton(g, mouseX, mouseY, bx, ry + 5, 40, 14, Component.translatable("screen.zhushenspace.shop.buy"));
                if (hover && !over(mouseX, mouseY, bx, ry + 5, 40, 14)) hoverStack = st;
            }
        }
        g.flush();
        g.disableScissor();
        if (maxScroll > 0) ZsTheme.scrollbar(g, panelX + panelW - 7, top, listBottom, gearContentH(), detailScroll, maxScroll);
        if (!hoverStack.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            g.renderComponentTooltip(font, getTooltipFromItem(minecraft, hoverStack), mouseX, mouseY);
            g.pose().popPose();
        }
    }

    private boolean handleGearPageClick(double mouseX, double mouseY) {
        if (over(mouseX, mouseY, panelX + 5, listTop + 2, 30, 12)) {
            gearPage = false;
            detailScroll = 0;
            playClick(1.0f);
            return true;
        }
        int top = gearContentTop();
        if (mouseY < top || mouseY >= listBottom) return false;
        int maxScroll = gearMaxScroll();
        detailScroll = Mth.clamp(detailScroll, 0, maxScroll);
        int base = top - detailScroll;
        int cx = panelX + 5, cw = panelW - 10 - (maxScroll > 0 ? 5 : 0);
        for (com.zhushen.space.data.ShopGear gear : com.zhushen.space.data.ShopGear.values()) {
            int cy = base + gear.ordinal() * (GEAR_CARD_H + 4);
            if (over(mouseX, mouseY, cx + cw - 48, cy + 3, 42, 14)) {
                PacketDistributor.sendToServer(new com.zhushen.space.network.GearPurchasePayload(gear.ordinal()));
                playClick(1.0f);
                return true;
            }
        }
        for (com.zhushen.space.data.MeleeWeapon w : com.zhushen.space.data.MeleeWeapon.values()) {
            int ry = weaponRowY(w.ordinal(), base);
            if (over(mouseX, mouseY, cx + cw - 46, ry + 5, 40, 14)) {
                PacketDistributor.sendToServer(new com.zhushen.space.network.GearPurchasePayload(
                        ProgressManager.WEAPON_OFFSET + w.ordinal()));
                playClick(1.0f);
                return true;
            }
        }
        if (over(mouseX, mouseY, cx + cw - 46, weaponRowY(com.zhushen.space.data.MeleeWeapon.COUNT, base) + 5, 40, 14)) {
            PacketDistributor.sendToServer(new com.zhushen.space.network.GearPurchasePayload(ProgressManager.BOLT_PURCHASE_ID));
            playClick(1.0f);
            return true;
        }
        return false;
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

    // ===== 技艺（按能量池分类） =====

    private int detailArt = -1;

    /** 技艺层级：0 商城首页；1 技艺大类（列出子分类）；2 通用技艺（按能量池分组） */
    private int artPage = 0;

    private int artGridTop() { return listTop + 18; }

    private int artCardY() { return shopCardY(SchoolType.COUNT); }

    private static int ownedArts() {
        int n = 0;
        for (ArtSkill s : ArtSkill.values()) if (ClientArtData.owns(s)) n++;
        return n;
    }

    /** 大分类卡片（首页「技艺」、技艺页「通用技艺」）共用 */
    private void renderGroupCard(GuiGraphics g, int mouseX, int mouseY, int cy, String titleKey, String descKey) {
        int cx = panelX + 5, cw = panelW - 10;
        ZsTheme.card(g, cx, cy, cw, 52, over(mouseX, mouseY, cx, cy, cw, 52));
        g.drawString(font, Component.translatable(titleKey), cx + 8, cy + 5, TEXT_MAIN, true);
        String cnt = Component.translatable("screen.zhushenspace.art.owned_count", ownedArts(), ArtSkill.COUNT).getString();
        g.drawString(font, cnt, cx + cw - font.width(cnt) - 8, cy + 6, GOLD, true);
        int dy = cy + 17;
        for (FormattedCharSequence l : font.split(Component.translatable(descKey), cw - 16)) {
            if (dy > cy + 30) break;
            g.drawString(font, l, cx + 8, dy, TEXT_SUB, true);
            dy += 10;
        }
        String detail = Component.translatable("screen.zhushenspace.shop.detail").getString();
        g.drawString(font, detail, cx + cw - font.width(detail) - 8, cy + 39, ACCENT, true);
    }

    private void renderArtCard(GuiGraphics g, int mouseX, int mouseY) {
        renderGroupCard(g, mouseX, mouseY, artCardY(), "screen.zhushenspace.art.title", "screen.zhushenspace.art.desc");
    }

    private void renderArtPages(GuiGraphics g, int mouseX, int mouseY) {
        int y = listTop + 2;
        renderSmallButton(g, mouseX, mouseY, panelX + 5, y, 30, 12, Component.translatable("screen.zhushenspace.shop.back"));
        g.drawString(font, Component.translatable(artPage == 1 ? "screen.zhushenspace.art.title" : "screen.zhushenspace.art.general"),
                panelX + 42, y + 2, TEXT_MAIN, true);
        if (artPage == 1) {
            renderGroupCard(g, mouseX, mouseY, listTop + 18, "screen.zhushenspace.art.general", "screen.zhushenspace.art.general_desc");
        } else {
            renderArtGrid(g, mouseX, mouseY);
        }
    }

    private static final int ART_TILE_H = 30;

    private int[] artTile(int i) {
        int cols = 3, gap = 4;
        int w = (panelW - 10 - gap * (cols - 1)) / cols;
        int x = panelX + 5 + (i % cols) * (w + gap);
        int y = artGridTop() + (i / cols) * (ART_TILE_H + gap);
        return new int[]{x, y, w};
    }

    private void renderArtGrid(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < ArtSkill.CATEGORIES.length; i++) {
            FeatEffects.Pool pool = ArtSkill.CATEGORIES[i];
            int[] t = artTile(i);
            boolean has = ClientEnergyData.hasPool(pool.id);
            boolean hover = over(mouseX, mouseY, t[0], t[1], t[2], ART_TILE_H);
            ZsTheme.card(g, t[0], t[1], t[2], ART_TILE_H, hover);
            List<ArtSkill> list = ArtSkill.inCategory(pool);
            int own = 0;
            for (ArtSkill s : list) if (ClientArtData.owns(s)) own++;
            g.drawString(font, Component.translatable("art.zhushenspace.cat." + pool.id), t[0] + 6, t[1] + 5,
                    has ? TEXT_MAIN : 0xFF6A6280, true);
            g.drawString(font, own + "/" + list.size(), t[0] + 6, t[1] + 17, has ? GOLD : 0xFF6A6280, true);
            if (!has) {
                String lock = Component.translatable("screen.zhushenspace.art.no_pool_short").getString();
                g.drawString(font, lock, t[0] + t[2] - font.width(lock) - 5, t[1] + 17, 0xFFFF8A80, true);
            }
        }
    }

    /** 详情页条目：row = 技艺行；chips = 选项 / 研发芯片行 */
    private record ArtChip(String label, int action, int value, int state) {}  // state 0 可购 1 已有 2 当前 3 锁定
    private record ArtLine(ArtSkill art, List<ArtChip> chips) {}

    private static final int ART_CHIP_H = 14;

    private List<ArtLine> artLines(FeatEffects.Pool pool) {
        List<ArtLine> lines = new ArrayList<>();
        int maxW = panelW - 40;
        for (ArtSkill s : ArtSkill.inCategory(pool)) {
            lines.add(new ArtLine(s, null));
            if (!ClientArtData.owns(s)) continue;
            List<ArtChip> all = new ArrayList<>();
            boolean anyOpt = false;
            for (int i = 0; i < s.options.length; i++) if (ClientArtData.hasOption(s, i)) anyOpt = true;
            for (int i = 0; i < s.options.length; i++) {
                String name = Component.translatable(s.optionKey(i)).getString();
                boolean have = ClientArtData.hasOption(s, i);
                int state;
                String label = name;
                if (have) state = ClientArtData.current(s) == i ? 2 : 1;
                else if (!anyOpt) { state = 0; label = name + " ✦"; }
                else if (s.extraCost < 0) state = 3;
                else { state = 0; label = name + " " + s.extraCost + "XP"; }
                all.add(new ArtChip(label, 1, i, state));
            }
            for (int i = 0; i < s.researches.length; i++) {
                ArtSkill.Research r = s.researches[i];
                String name = Component.translatable(s.researchKey(i)).getString();
                boolean have = ClientArtData.hasResearch(s, i);
                int state = have ? 1 : (r.minCaster() > 1 ? 3 : 0);
                String label = have ? name : name + (r.minCaster() > 1
                        ? " [" + Component.translatable("screen.zhushenspace.art.caster", "DCBAS".charAt(Math.min(4, r.minCaster() - 1))).getString() + "]"
                        : " " + r.xp() + "XP");
                all.add(new ArtChip((have ? "✔ " : "+ ") + label, 2, i, state));
            }
            List<ArtChip> cur = new ArrayList<>();
            int w = 0;
            for (ArtChip c : all) {
                int cw = font.width(c.label()) + 8;
                if (!cur.isEmpty() && w + cw > maxW) { lines.add(new ArtLine(s, cur)); cur = new ArrayList<>(); w = 0; }
                cur.add(c);
                w += cw + 3;
            }
            if (!cur.isEmpty()) lines.add(new ArtLine(s, cur));
        }
        return lines;
    }

    private int artLineH(ArtLine l) { return l.chips() == null ? SHOP_ROW_H : ART_CHIP_H; }

    private int artDetailListY() { return listTop + 2 + 14 + 12; }

    private void renderArtDetail(GuiGraphics g, int mouseX, int mouseY) {
        FeatEffects.Pool pool = ArtSkill.CATEGORIES[detailArt];
        int y = listTop + 2;
        renderSmallButton(g, mouseX, mouseY, panelX + 5, y, 30, 12, Component.translatable("screen.zhushenspace.shop.back"));
        g.drawString(font, Component.translatable("art.zhushenspace.cat." + pool.id), panelX + 42, y + 2, TEXT_MAIN, true);
        String xp = Component.translatable("screen.zhushenspace.art.xp", ClientArtData.xp()).getString();
        g.drawString(font, xp, panelX + panelW - font.width(xp) - 8, y + 2, GOLD, true);
        y += 14;
        boolean has = ClientEnergyData.hasPool(pool.id);
        g.drawString(font, Component.translatable(has ? "screen.zhushenspace.art.pool_ok" : "screen.zhushenspace.art.pool_missing"),
                panelX + 8, y, has ? 0xFF4DE0C0 : 0xFFFF8A80, true);
        y = artDetailListY();

        List<ArtLine> lines = artLines(pool);
        int contentH = 0;
        for (ArtLine l : lines) contentH += artLineH(l);
        int viewH = listBottom - y;
        int maxScroll = Math.max(0, contentH - viewH);
        detailScroll = Mth.clamp(detailScroll, 0, maxScroll);
        g.enableScissor(panelX + 1, y, panelX + panelW - 1, listBottom);
        List<FormattedCharSequence> tip = null;
        int ry = y - detailScroll;
        for (ArtLine l : lines) {
            int h = artLineH(l);
            if (ry + h >= y && ry < listBottom) {
                if (l.chips() == null) {
                    List<FormattedCharSequence> t = renderArtRow(g, mouseX, mouseY, l.art(), ry, has);
                    if (t != null) tip = t;
                } else {
                    int cx = panelX + 30;
                    for (ArtChip c : l.chips()) {
                        int cw = font.width(c.label()) + 8;
                        boolean hv = over(mouseX, mouseY, cx, ry + 1, cw, ART_CHIP_H - 2);
                        int bg = switch (c.state()) { case 2 -> 0xCC6B4E16; case 1 -> 0xAA3A2F58; case 3 -> 0x66201C2C; default -> hv ? 0xCC4A3A7A : 0xAA2A2244; };
                        int border = switch (c.state()) { case 2 -> GOLD; case 1 -> 0xFF8E7CC3; case 3 -> 0xFF4A4458; default -> ACCENT; };
                        g.fill(cx, ry + 1, cx + cw, ry + ART_CHIP_H - 1, bg);
                        g.renderOutline(cx, ry + 1, cw, ART_CHIP_H - 2, border);
                        g.drawString(font, c.label(), cx + 4, ry + 3, c.state() == 3 ? 0xFF6A6280 : TEXT_MAIN, false);
                        cx += cw + 3;
                    }
                }
            }
            ry += h;
        }
        g.disableScissor();
        if (maxScroll > 0 && viewH > 0) ZsTheme.scrollbar(g, panelX + panelW - 5, y, listBottom, contentH, detailScroll, maxScroll);
        if (tip != null) g.renderTooltip(font, tip, mouseX, mouseY);
    }

    private List<FormattedCharSequence> renderArtRow(GuiGraphics g, int mouseX, int mouseY, ArtSkill s, int ry, boolean hasPool) {
        int cx = panelX + 5, cw = panelW - 10;
        boolean hover = over(mouseX, mouseY, cx, ry, cw, SHOP_ROW_H - 1);
        ZsTheme.row(g, cx, ry, cw, SHOP_ROW_H - 1, hover, true);
        Component name = Component.translatable(s.ability.nameKey());
        g.blit(s.ability.iconTexture(), cx + 4, ry + 4, 16, 16, 0f, 0f, 32, 32, 32, 32);
        g.drawString(font, name, cx + 24, ry + 8, TEXT_MAIN, true);
        String costTag = Component.translatable("screen.zhushenspace.art.energy", s.cost).getString();
        g.drawString(font, costTag, cx + 24 + font.width(name) + 6, ry + 8, 0xFF7FA6C0, true);
        boolean owned = ClientArtData.owns(s);
        boolean affordable = hasPool && (s.branchTier < 0 || ClientProgressData.branch(s.branchTier) >= s.branchCost)
                && ClientProgressData.score() >= s.scoreCost;
        boolean overBuy = false;
        String price = s.branchTier < 0
                ? Component.translatable("screen.zhushenspace.art.price_score", s.scoreCost).getString()
                : Component.translatable("screen.zhushenspace.shop.move_cost_short",
                PlayerCurrencyData.tierLetter(s.branchTier), s.branchCost, s.scoreCost).getString();
        if (s.innate()) {
            // 天生技艺：拥有对应能量池即习得，不可购买
            price = Component.translatable("screen.zhushenspace.art.innate").getString();
            g.drawString(font, price, cx + cw - font.width(price) - 6, ry + 8, owned ? GOLD : 0xFF8A94A4, true);
        } else if (owned) {
            String o = Component.translatable("screen.zhushenspace.shop.skill_owned").getString();
            g.drawString(font, o, cx + cw - font.width(o) - 6, ry + 8, GOLD, true);
        } else {
            g.drawString(font, price, cx + cw - 48 - font.width(price) - 4, ry + 8, affordable ? TEXT_SUB : 0xFFFF8A80, true);
            if (affordable) buyGlow(g, cx + cw - 44, ry + 6, 40, 12);
            renderSmallButton(g, mouseX, mouseY, cx + cw - 44, ry + 6, 40, 12, Component.translatable("screen.zhushenspace.shop.buy"));
            overBuy = over(mouseX, mouseY, cx + cw - 44, ry + 6, 40, 12);
        }
        if (hover && !overBuy) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(name.getVisualOrderText());
            lines.add(Component.literal(price + "  ·  " + costTag).withStyle(ChatFormatting.GRAY).getVisualOrderText());
            lines.addAll(font.split(Component.translatable(s.ability.descKey()), SHOP_TIP_WIDTH));
            return lines;
        }
        return null;
    }

    private boolean handleArtClick(double mouseX, double mouseY) {
        int gw = panelW - 10;
        if (detailArt < 0 && artPage == 0) {
            if (over(mouseX, mouseY, panelX + 5, artCardY(), gw, 52)) { artPage = 1; playClick(1.1f); return true; }
            return false;
        }
        if (detailArt < 0 && over(mouseX, mouseY, panelX + 5, listTop + 2, 30, 12)) {
            artPage--;
            playClick(1.0f);
            return true;
        }
        if (detailArt < 0 && artPage == 1) {
            if (over(mouseX, mouseY, panelX + 5, listTop + 18, gw, 52)) { artPage = 2; playClick(1.1f); return true; }
            return false;
        }
        if (detailArt < 0) {
            for (int i = 0; i < ArtSkill.CATEGORIES.length; i++) {
                int[] t = artTile(i);
                if (over(mouseX, mouseY, t[0], t[1], t[2], ART_TILE_H)) {
                    detailArt = i;
                    detailScroll = 0;
                    playClick(1.1f);
                    return true;
                }
            }
            return false;
        }
        int y = listTop + 2;
        if (over(mouseX, mouseY, panelX + 5, y, 30, 12)) {
            detailArt = -1;
            detailScroll = 0;
            playClick(1.0f);
            return true;
        }
        y = artDetailListY();
        if (mouseY < y || mouseY >= listBottom) return false;
        int ry = y - detailScroll;
        for (ArtLine l : artLines(ArtSkill.CATEGORIES[detailArt])) {
            int h = artLineH(l);
            if (mouseY >= ry && mouseY < ry + h) {
                if (l.chips() == null) {
                    int cx = panelX + 5, cw = panelW - 10;
                    if (!l.art().innate() && !ClientArtData.owns(l.art()) && over(mouseX, mouseY, cx + cw - 44, ry + 6, 40, 12)) {
                        PacketDistributor.sendToServer(new ArtActionPayload(0, l.art().ordinal(), 0));
                        playClick(1.0f);
                        return true;
                    }
                } else {
                    int cx = panelX + 30;
                    for (ArtChip c : l.chips()) {
                        int cw = font.width(c.label()) + 8;
                        if (c.state() != 3 && c.state() != 2 && over(mouseX, mouseY, cx, ry + 1, cw, ART_CHIP_H - 2)
                                && !(c.action() == 2 && c.state() == 1)) {
                            PacketDistributor.sendToServer(new ArtActionPayload(c.action(), l.art().ordinal(), c.value()));
                            playClick(1.2f);
                            return true;
                        }
                        cx += cw + 3;
                    }
                }
                return false;
            }
            ry += h;
        }
        return false;
    }

    /** 商城页点击 */
    private boolean handleShopClick(double mouseX, double mouseY) {
        if (gearPage) return handleGearPageClick(mouseX, mouseY);
        if (detailArt >= 0 || artPage > 0) return handleArtClick(mouseX, mouseY);
        if (detailSchool < 0 && handleArtClick(mouseX, mouseY)) return true;
        if (detailSchool < 0 && gearGroupCardY() < listBottom && mouseY < listBottom
                && over(mouseX, mouseY, panelX + 5, gearGroupCardY(), panelW - 10, 52)) {
            gearPage = true;
            detailScroll = 0;
            playClick(1.0f);
            return true;
        }
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
        if (discardConfirm) return button == 0 ? handleDiscardClick(mouseX, mouseY) : true;
        if (tab == Tab.FEATS && ZsAnim.nowMs() - tabChangedAt > 400 && handleFeatClick(mouseX, mouseY, button)) return true;
        if (tab == Tab.FEATS && button == 0) {
            int[] r = gameToggleRect();
            if (over(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                var cfg = com.zhushen.space.client.ClientUiConfig.get();
                cfg.jjkGame = !cfg.jjkGame;
                com.zhushen.space.client.ClientUiConfig.save();
                domainGame.reset();
                playClick(cfg.jjkGame ? 1.2f : 0.9f);
                return true;
            }
        }
        if (tab == Tab.FEATS && com.zhushen.space.client.ClientUiConfig.get().jjkGame
                && ZsAnim.nowMs() - tabChangedAt > 900 && domainGame.press(mouseX, mouseY, button)) {
            return true;
        }
        if (profChooser >= 0) {
            if (button == 0) return handleProfChooserClick(mouseX, mouseY);
            profChooser = -1;
            return true;
        }
        if (button == 0 && tab == Tab.SKILLS) {
            for (int gi = 0; gi < 3; gi++) {
                if (profChipX[gi] >= 0 && profPending[gi] > 0
                        && over(mouseX, mouseY, profChipX[gi], profChipY[gi], profChipW[gi], 11)
                        && mouseY >= listTop && mouseY < listBottom) {
                    profChooser = gi;
                    playClick(1.1f);
                    return true;
                }
            }
        }
        if (button != 0 && tab == Tab.PRESET && presetTab.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 0) {
            // 底部货币栏：点击打开货币界面（拖拽拼合/拆解）
            if (over(mouseX, mouseY, panelX + 4, panelY + panelH - 16, panelW - 8, 14)) {
                Minecraft.getInstance().setScreen(new CurrencyScreen(this));
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
            for (int i = 0; i < 5; i++) {
                if (over(mouseX, mouseY, tabX[i], panelY + 5, tabW[i], TAB_H)) {
                    switchTab(Tab.values()[i]);
                    presetTab.reset();
                    detailSchool = -1;
                    detailArt = -1;
                    artPage = 0;
                    detailScroll = 0;
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
                    if (presetTab.mouseClicked(mouseX, mouseY, 0)) return true;
                }
                case SHOP -> {
                    if (handleShopClick(mouseX, mouseY)) return true;
                }
                default -> { }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        domainGame.release(mouseX, mouseY, button);
        if (tab == Tab.PRESET && presetTab.mouseReleased(mouseX, mouseY, button)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int dir = -(int) Math.signum(scrollY);
        if (discardConfirm) return true;
        if (tab == Tab.FEATS && scrollFeats(mouseX, mouseY, dir)) return true;
        PointList list = activeList();
        if (list != null) {
            list.scroll += dir * ROW_HEIGHT;
            clampScroll();
            return true;
        }
        if (tab == Tab.PRESET && presetTab.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        if (tab == Tab.SHOP && (detailSchool >= 0 || detailArt >= 0 || gearPage)) {
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
        Component legend = Component.translatable(type.legendKey());
        String legendText = legend.getString();
        // 未提供传奇描述（空文本或缺失键）的属性不显示传奇行
        if (!legendText.isBlank() && !legendText.equals(type.legendKey())) {
            lines.addAll(font.split(legend.copy().withStyle(GOLD_STYLE), TOOLTIP_WIDTH));
        }
        return lines;
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (tab == Tab.PRESET && presetTab.charTyped(c)) return true;
        if (tab == Tab.FEATS && featSearchFocus) {
            if (c >= ' ' && featSearch.length() < 32) {
                featSearch += c;
                featScroll = 0;
            }
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    /**
     * 键盘：Esc / E 逐层返回（输入框 → 弹窗 → 详情 → 未保存确认 → 关闭）；
     * 1~5 切换选项卡，Ctrl+Tab / Ctrl+Shift+Tab 循环切换；回车确认加点；Ctrl+Z 撤销本次未确认的改动。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (tab == Tab.FEATS && featSearchFocus) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!featSearch.isEmpty()) featSearch = featSearch.substring(0, featSearch.length() - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) {
                featSearchFocus = false;
                return true;
            }
            return true; // 输入中屏蔽其他快捷键（含 E）
        }
        if (tab == Tab.PRESET && !discardConfirm && keyCode != GLFW.GLFW_KEY_ESCAPE && presetTab.keyPressed(keyCode)) return true;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_E) {
            back();
            return true;
        }
        if (discardConfirm) {
            if (keyCode == GLFW.GLFW_KEY_ENTER) { resetBuild(); discardConfirm = false; forceClose(); }
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_5) {
            switchTab(Tab.values()[keyCode - GLFW.GLFW_KEY_1]);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && Screen.hasControlDown()) {
            int n = Tab.values().length;
            switchTab(Tab.values()[(tab.ordinal() + (Screen.hasShiftDown() ? n - 1 : 1)) % n]);
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && (tab == Tab.ATTRIBUTES || tab == Tab.SKILLS || tab == Tab.FEATS) && buildDirty()) {
            if (buildCheck().ok()) commitBuild();
            else ZsTheme.click(0.5f);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_Z && Screen.hasControlDown() && buildDirty()) {
            resetBuild();
            ZsTheme.click(0.8f);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 未确认改动时关闭面板的二次确认 */
    private boolean discardConfirm = false;

    private void switchTab(Tab t) {
        if (tab == t) return;
        tabChangedAt = ZsAnim.nowMs();
        if (t == Tab.ATTRIBUTES) SgStyle.rollIn();
        tab = t;
        lastTab = t;
        profChooser = -1;
        featSearchFocus = false;
        playClick(1.0f);
    }

    /** 逐层返回 */
    private void back() {
        if (discardConfirm) { discardConfirm = false; playClick(0.8f); return; }
        if (profChooser >= 0) { profChooser = -1; playClick(0.8f); return; }
        if (tab == Tab.SHOP && detailArt >= 0) { detailArt = -1; detailScroll = 0; playClick(0.8f); return; }
        if (tab == Tab.SHOP && artPage > 0) { artPage--; playClick(0.8f); return; }
        if (tab == Tab.SHOP && gearPage) { gearPage = false; detailScroll = 0; playClick(0.8f); return; }
        if (tab == Tab.SHOP && detailSchool >= 0) { detailSchool = -1; playClick(0.8f); return; }
        if (tab == Tab.PRESET && presetTab.back()) return;
        if (buildDirty()) { discardConfirm = true; playClick(0.6f); return; }
        forceClose();
    }

    private int[] discardBox() {
        int w = 200, h = 62;
        return new int[]{panelX + (panelW - w) / 2, panelY + (panelH - h) / 2, w, h};
    }

    private void renderDiscardConfirm(GuiGraphics g, int mouseX, int mouseY) {
        if (!discardConfirm) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xAA000000);
        int[] b = discardBox();
        ZsTheme.panel(g, b[0], b[1], b[2], b[3]);
        g.drawCenteredString(font, Component.translatable("screen.zhushenspace.discard.title"), b[0] + b[2] / 2, b[1] + 10, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("screen.zhushenspace.discard.hint"), b[0] + b[2] / 2, b[1] + 22, 0xFFB0B8C8);
        int by = b[1] + b[3] - 20;
        renderSmallButton(g, mouseX, mouseY, b[0] + 10, by, 56, 14, Component.translatable("screen.zhushenspace.discard.discard"));
        renderSmallButton(g, mouseX, mouseY, b[0] + 72, by, 56, 14, Component.translatable("screen.zhushenspace.discard.apply"));
        renderSmallButton(g, mouseX, mouseY, b[0] + 134, by, 56, 14, Component.translatable("gui.cancel"));
        g.pose().popPose();
    }

    private boolean handleDiscardClick(double mx, double my) {
        int[] b = discardBox();
        int by = b[1] + b[3] - 20;
        if (over(mx, my, b[0] + 10, by, 56, 14)) { resetBuild(); discardConfirm = false; forceClose(); }
        else if (over(mx, my, b[0] + 72, by, 56, 14)) {
            discardConfirm = false;
            if (buildCheck().ok()) { commitBuild(); forceClose(); }
            else { ZsTheme.click(0.5f); if (tab == Tab.PRESET || tab == Tab.SHOP) switchTab(Tab.ATTRIBUTES); }
        } else if (over(mx, my, b[0] + 134, by, 56, 14) || !over(mx, my, b[0], b[1], b[2], b[3])) {
            discardConfirm = false;
            playClick(0.8f);
        }
        return true;
    }

    /** 窗口关闭按钮 / 外部调用：同样走逐层返回 */
    @Override
    public void onClose() {
        back();
    }

    /** 上次停留的选项卡（关闭后再打开、从货币界面返回时恢复） */
    private static Tab lastTab = Tab.ATTRIBUTES;

    /** 关闭面板时返回背包界面（仿照创造选项卡的体验） */
    private void forceClose() {
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

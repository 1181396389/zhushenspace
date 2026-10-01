package com.zhushen.space.screen;

import com.zhushen.space.client.ClientArtData;
import com.zhushen.space.client.ClientEnergyData;
import com.zhushen.space.client.ClientProgressData;
import com.zhushen.space.client.ClientSetup;
import com.zhushen.space.client.ClientSkillData;
import com.zhushen.space.client.ClientUiConfig;
import com.zhushen.space.common.FeatEffects;
import com.zhushen.space.data.ArtSkill;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.network.EquipSkillsPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * 主神面板 · 战斗预设页（两柄剑栏 A/B + 技能库）。
 *
 * <p>交互：
 * <ul>
 *   <li>从技能库拖到格子装备；格子之间拖动 = 交换（跨栏为移动，按住 Ctrl 为复制）；拖出剑栏 = 卸下；拖动中按 Esc 取消</li>
 *   <li>右键格子卸下；右键（或 Shift+左键）技能 = 快速装备到当前栏的第一个空格</li>
 *   <li>悬停技能 / 格子时按 1~9：放入当前栏（悬停格子时为该格所在栏）的对应格</li>
 *   <li>左键剑柄设为当前栏；Shift+右键剑柄清空整栏；Ctrl+Z 撤销（最多 20 步）</li>
 *   <li>技能库：分类筛选（全部 / 基础 / 内力 / 太极拳 / 各能量池技艺）、搜索（Ctrl+F）、分组折叠、悬停详情</li>
 * </ul>
 * 同一栏内不会出现重复技能（服务端会整栏拒绝含重复的预设），放入已存在的技能时原位置与目标格互换。
 */
public final class PresetTab {

    public static final float BAR_SCALE = 1.1f;
    public static final int SLOT_SIZE = BladeBar.slotSize(BAR_SCALE);
    public static final int BAR_H = Math.round(BladeBar.H * BAR_SCALE);
    private static final int CHIP_H = 20, HEAD_H = 14, ROW_GAP = 4, TOOL_H = 12, SEARCH_W = 92;
    private static final int TIP_W = 180, UNDO_MAX = 20;
    private static final long SYNC_GUARD_MS = 800;

    private static final String G_ALL = "all", G_BASIC = "basic", G_NEILI = "neili", G_TAICHI = "tai_chi";

    /** 折叠的分组（本次游戏内记住） */
    private static final Set<String> COLLAPSED = new HashSet<>(Set.of(G_TAICHI));
    private static String filter = G_ALL;

    private final Font font;
    private final int[][] slots = new int[2][9];
    private final Deque<int[][]> undo = new ArrayDeque<>();
    private long lastEditAt;

    // 区域
    private int px, pw, top, bottom;
    // 拖拽
    private int dragging = -1, fromBar = -1, fromSlot = -1;
    private double pressX, pressY;
    // 悬停（上一帧）
    private int hoverAbility = -1, hoverBar = -1, hoverSlot = -1;
    private double mouseX, mouseY;
    // 滚动
    private int scroll;
    private float scrollAnim;
    private int contentH;
    // 搜索
    private String search = "";
    private boolean searchFocus;
    // 反馈
    private int flashBar = -1, flashSlot = -1;
    private long flashAt;
    private Component toast;
    private long toastAt;

    public PresetTab(Font font) {
        this.font = font;
        load();
    }

    /** 从服务端同步的数据载入两栏 */
    public void load() {
        for (int b = 0; b < 2; b++) System.arraycopy(ClientSkillData.bar(b), 0, slots[b], 0, 9);
    }

    public boolean dragging() { return dragging >= 0; }

    /** 技能库顶端（背景压暗用） */
    public int libraryTop(int listTop) { return barY(listTop, 1) + BAR_H + 6; }

    // ===================== 布局 =====================

    private int barX() { return px + (pw - Math.round(BladeBar.W * BAR_SCALE)) / 2; }

    private static int barY(int top, int bar) { return top + 6 + bar * (BAR_H + 8); }

    private int barY(int bar) { return barY(top, bar); }

    private int slotX(int slot) { return BladeBar.slotX(barX(), slot, BAR_SCALE); }

    private int slotY(int bar) { return BladeBar.slotY(barY(bar), BAR_SCALE); }

    private int toolY() { return barY(1) + BAR_H + 6; }

    private int chipTop() { return toolY() + TOOL_H + 6; }

    private int chipBottom() { return bottom - 15; }

    private int chipW() { return (pw - 24) / 2; }

    private int[] slotAt(double mx, double my) {
        for (int b = 0; b < 2; b++)
            for (int s = 0; s < 9; s++)
                if (over(mx, my, slotX(s), slotY(b), SLOT_SIZE, SLOT_SIZE)) return new int[]{b, s};
        return null;
    }

    /** 剑柄（第一个格子左侧的剑柄与护手区域） */
    private int hiltAt(double mx, double my) {
        for (int b = 0; b < 2; b++)
            if (over(mx, my, barX(), barY(b), slotX(0) - 2 - barX(), BAR_H)) return b;
        return -1;
    }

    private boolean overBars(double mx, double my) {
        int w = Math.round(BladeBar.W * BAR_SCALE);
        return over(mx, my, barX() - 4, barY(0) - 4, w + 8, barY(1) + BAR_H + 4 - barY(0) + 4);
    }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ===================== 技能库数据 =====================

    private record Chip(SkillAbility ability, boolean enabled, boolean locked, String group) {}

    private record Group(String id, Component label, int color) {}

    /** 一个绘制元素：header（group != null && chip == null）或技能芯片 */
    private record Placed(Group group, Chip chip, int x, int y, int w, int h) {}

    private static String groupOf(SkillAbility a) {
        if (a.isSchoolAbility()) return G_TAICHI;
        if (a.isNeiliAbility()) return G_NEILI;
        if (a.isArtAbility()) {
            ArtSkill art = ArtSkill.of(a);
            if (art != null) return art.pool == FeatEffects.Pool.NEILI ? G_NEILI : "pool:" + art.pool.id;
        }
        return G_BASIC;
    }

    private List<Chip> chips() {
        List<Chip> list = new ArrayList<>();
        boolean neili = ClientEnergyData.hasPool(ClientEnergyData.NEILI_ID);
        for (SkillAbility a : SkillAbility.unlocked(ClientSkillData.points(), neili, false))
            list.add(new Chip(a, true, false, groupOf(a)));
        for (ArtSkill art : ArtSkill.values())
            if (ClientArtData.owns(art)) list.add(new Chip(art.ability, ClientEnergyData.hasPool(art.pool.id), false, groupOf(art.ability)));
        if (ClientProgressData.taiChiUnlocked()) {
            for (SkillAbility a : SkillAbility.values()) {
                if (!a.isSchoolAbility() || a == SkillAbility.EIGHT_POWERS || a == SkillAbility.COILING_SILK) continue; // 被动招式
                boolean bought = ClientProgressData.skillPurchased(0, a.ordinal());
                list.add(new Chip(a, bought && neili, !bought, G_TAICHI));
            }
        }
        return list;
    }

    private static int poolColor(String id) {
        for (ClientEnergyData.PoolView v : ClientEnergyData.pools()) if (v.id().equals(id)) return 0xFF000000 | v.color();
        return BladeBar.EMBER_HOT;
    }

    private static Group group(String id) {
        return switch (id) {
            case G_BASIC -> new Group(id, Component.translatable("screen.zhushenspace.preset.group.basic"), BladeBar.IRON_TEXT);
            case G_NEILI -> new Group(id, Component.translatable("energy.zhushenspace.neili"), poolColor("neili"));
            case G_TAICHI -> new Group(id, Component.translatable("school.zhushenspace.tai_chi"), 0xFFE8E0D0);
            case G_ALL -> new Group(id, Component.translatable("screen.zhushenspace.preset.group.all"), BladeBar.IRON_TEXT);
            default -> {
                String pool = id.substring(5);
                yield new Group(id, Component.translatable("art.zhushenspace.cat." + pool), poolColor(pool));
            }
        };
    }

    /** 当前存在的分组（固定顺序：基础、内力、太极拳、各能量池） */
    private static List<String> groupOrder(List<Chip> chips) {
        List<String> order = new ArrayList<>(List.of(G_BASIC, G_NEILI, G_TAICHI));
        for (FeatEffects.Pool p : ArtSkill.CATEGORIES) if (p != FeatEffects.Pool.NEILI) order.add("pool:" + p.id);
        Set<String> present = new HashSet<>();
        for (Chip c : chips) present.add(c.group());
        order.removeIf(g -> !present.contains(g));
        return order;
    }

    private static String name(SkillAbility a) {
        return Component.translatable(a.nameKey()).getString();
    }

    private boolean matches(Chip c) {
        if (search.isEmpty()) return true;
        String q = search.toLowerCase(Locale.ROOT);
        return name(c.ability()).toLowerCase(Locale.ROOT).contains(q)
                || c.ability().key().contains(q)
                || group(c.group()).label().getString().toLowerCase(Locale.ROOT).contains(q);
    }

    /** 技能库布局（渲染与点击共用；y 已含滚动偏移） */
    private List<Placed> layout(List<Chip> all) {
        List<Placed> out = new ArrayList<>();
        int cw = chipW(), y = chipTop() - Math.round(scrollAnim);
        boolean headers = G_ALL.equals(filter);
        for (String gid : groupOrder(all)) {
            if (!headers && !gid.equals(filter)) continue;
            List<Chip> items = new ArrayList<>();
            for (Chip c : all) if (c.group().equals(gid) && matches(c)) items.add(c);
            if (items.isEmpty()) continue;
            Group g = group(gid);
            boolean collapsed = headers && search.isEmpty() && COLLAPSED.contains(gid);
            if (headers) {
                out.add(new Placed(g, null, px + 8, y, pw - 16, HEAD_H));
                y += HEAD_H + 2;
            }
            if (collapsed) continue;
            for (int i = 0; i < items.size(); i++) {
                int col = i % 2;
                out.add(new Placed(g, items.get(i), px + 8 + col * (cw + 8), y, cw, CHIP_H));
                if (col == 1 || i == items.size() - 1) y += CHIP_H + ROW_GAP;
            }
            y += 2;
        }
        contentH = y + Math.round(scrollAnim) - chipTop();
        return out;
    }

    // ===================== 渲染 =====================

    public void render(GuiGraphics g, int mx, int my, int px, int pw, int top, int bottom) {
        this.px = px; this.pw = pw; this.top = top; this.bottom = bottom;
        this.mouseX = mx; this.mouseY = my;
        syncFromServer();
        hoverAbility = -1; hoverBar = -1; hoverSlot = -1;
        List<FormattedCharSequence> tip = null;
        long now = ZsAnim.nowMs();
        int active = ClientUiConfig.get().activeBar;
        boolean ctrl = Screen.hasControlDown();
        int[] target = dragging >= 0 ? slotAt(mx, my) : null;

        // ---- 两柄剑栏 ----
        for (int b = 0; b < 2; b++) {
            int by = barY(b), sy = slotY(b);
            BladeBar.draw(g, font, barX(), by, BAR_SCALE, BladeBar.Sword.ofBar(b), b == 0 ? "A" : "B", active == b);
            boolean hilt = dragging < 0 && hiltAt(mx, my) == b;
            if (hilt) {
                g.renderOutline(barX() + 2, by + 2, slotX(0) - 4 - barX(), BAR_H - 4, ZsAnim.withAlpha(BladeBar.EMBER_HOT, 0.5f + 0.4f * ZsAnim.pulse(900)));
                tip = lines(Component.translatable(active == b ? "screen.zhushenspace.preset.hilt_active" : "screen.zhushenspace.preset.hilt",
                        b == 0 ? "A" : "B").withStyle(ChatFormatting.GOLD),
                        Component.translatable("screen.zhushenspace.preset.hilt_tip").withStyle(ChatFormatting.GRAY));
            }
            for (int s = 0; s < 9; s++) {
                int sx = slotX(s), id = slots[b][s];
                boolean origin = dragging >= 0 && b == fromBar && s == fromSlot;
                boolean isTarget = target != null && target[0] == b && target[1] == s;
                boolean hover = dragging < 0 && over(mx, my, sx, sy, SLOT_SIZE, SLOT_SIZE);
                boolean has = id >= 0 && id < SkillAbility.COUNT;
                BladeBar.socket(g, has && !origin ? SkillAbility.values()[id].iconTexture() : null, sx, sy, SLOT_SIZE, 0, 0, hover || isTarget);
                if (origin && has) {
                    // 原位置：半透明残影 + 虚线框
                    g.setColor(1f, 1f, 1f, 0.3f);
                    g.blit(SkillAbility.values()[id].iconTexture(), sx + 2, sy + 2, SLOT_SIZE - 4, SLOT_SIZE - 4, 0f, 0f, 32, 32, 32, 32);
                    g.setColor(1f, 1f, 1f, 1f);
                    dashed(g, sx, sy, SLOT_SIZE, SLOT_SIZE, BladeBar.EMBER);
                }
                if (isTarget) {
                    // 目标格：预览将放入的技能 + 原内容缩小移往来处
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 150);
                    float p = ZsAnim.pulse(600);
                    g.renderOutline(sx - 2, sy - 2, SLOT_SIZE + 4, SLOT_SIZE + 4, ZsAnim.lerpColor(BladeBar.EMBER, 0xFFFFFFFF, p * 0.6f));
                    g.pose().popPose();
                }
                if (dragging >= 0 && !isTarget && !origin) {
                    // 拖动中：同栏已有此技能的格子标记（放入会与之互换）
                    if (id == dragging && !(fromBar == b && ctrl)) dashed(g, sx - 1, sy - 1, SLOT_SIZE + 2, SLOT_SIZE + 2, 0xFFFF6A4A);
                }
                if (flashBar == b && flashSlot == s && now - flashAt < 600) {
                    float f = 1 - (now - flashAt) / 600f;
                    g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, ZsAnim.withAlpha(BladeBar.EMBER_HOT, 0.55f * f));
                    int e = (int) (5 * (1 - f));
                    g.renderOutline(sx - 1 - e, sy - 1 - e, SLOT_SIZE + 2 + e * 2, SLOT_SIZE + 2 + e * 2, ZsAnim.withAlpha(BladeBar.EMBER_HOT, f));
                }
                // 键位角标
                g.pose().pushPose();
                g.pose().translate(sx + SLOT_SIZE - 5, sy + SLOT_SIZE - 6, 200);
                g.pose().scale(0.6f, 0.6f, 1);
                g.drawString(font, String.valueOf(s + 1), 0, 0, BladeBar.EMBER_HOT, true);
                g.pose().popPose();
                if (hover) {
                    hoverBar = b;
                    hoverSlot = s;
                    if (has) tip = slotTip(SkillAbility.values()[id], b, s);
                    else tip = lines(Component.translatable("screen.zhushenspace.preset.empty", b == 0 ? "A" : "B", s + 1).withStyle(ChatFormatting.GOLD),
                            Component.translatable("screen.zhushenspace.preset.empty_tip").withStyle(ChatFormatting.GRAY));
                }
            }
        }

        // ---- 工具行：分类筛选 + 搜索 ----
        List<Chip> all = chips();
        List<String> groups = groupOrder(all);
        if (!G_ALL.equals(filter) && !groups.contains(filter)) filter = G_ALL;
        int ty = toolY();
        BladeBar.separator(g, px + 10, px + pw - 10, ty - 4);
        List<String> pills = new ArrayList<>();
        pills.add(G_ALL);
        pills.addAll(groups);
        boolean compact = pillsWidth(pills, false) > pw - 16 - SEARCH_W - 8;
        int x = px + 8;
        for (String gid : pills) {
            Group gr = group(gid);
            String label = pillLabel(gr, compact);
            int w = font.width(label) + 8;
            boolean sel = gid.equals(filter);
            boolean hv = dragging < 0 && over(mx, my, x, ty, w, TOOL_H);
            float t = ZsAnim.tween(ZsAnim.key(61, x, ty), sel ? 1 : hv ? 0.5f : 0, 18);
            g.fillGradient(x, ty, x + w, ty + TOOL_H, ZsAnim.lerpColor(BladeBar.IRON_BTN, 0xFF7A3414, t), ZsAnim.lerpColor(0xFF180E0A, 0xFF3A140A, t));
            g.renderOutline(x, ty, w, TOOL_H, ZsAnim.lerpColor(0xFF4A3020, BladeBar.EMBER, t));
            if (!G_ALL.equals(gid) && !G_BASIC.equals(gid)) g.fill(x + 1, ty + TOOL_H - 2, x + w - 1, ty + TOOL_H - 1, ZsAnim.withAlpha(gr.color(), 0.5f + 0.5f * t));
            g.drawString(font, label, x + 4, ty + 2, ZsAnim.lerpColor(BladeBar.IRON_SUB, 0xFFFFF4D8, t), false);
            if (hv && compact) tip = lines(gr.label());
            x += w + 3;
        }
        // 搜索框
        int sx0 = px + pw - 8 - SEARCH_W;
        boolean sHover = over(mx, my, sx0, ty, SEARCH_W, TOOL_H);
        g.fill(sx0, ty, sx0 + SEARCH_W, ty + TOOL_H, 0xCC0E0806);
        g.renderOutline(sx0, ty, SEARCH_W, TOOL_H, searchFocus ? BladeBar.EMBER : sHover ? 0xFF8A6040 : 0xFF4A3020);
        String shown = search.isEmpty() && !searchFocus ? Component.translatable("screen.zhushenspace.preset.search").getString()
                : font.plainSubstrByWidth(search, SEARCH_W - 14, true);
        g.drawString(font, shown, sx0 + 4, ty + 2, search.isEmpty() && !searchFocus ? 0xFF7A6050 : BladeBar.IRON_TEXT, false);
        if (searchFocus && (now / 500) % 2 == 0) {
            int cx = sx0 + 4 + font.width(shown);
            g.fill(cx, ty + 2, cx + 1, ty + 10, BladeBar.EMBER_HOT);
        }
        if (!search.isEmpty()) g.drawString(font, "×", sx0 + SEARCH_W - 8, ty + 2, over(mx, my, sx0 + SEARCH_W - 10, ty, 10, TOOL_H) ? 0xFFFFFFFF : 0xFFAA8870, false);

        // ---- 技能库 ----
        int ct = chipTop(), cb = chipBottom();
        List<Placed> placed = layout(all);
        int maxScroll = Math.max(0, contentH - (cb - ct));
        scroll = Mth.clamp(scroll, 0, maxScroll);
        scrollAnim += (scroll - scrollAnim) * 0.35f;
        if (Math.abs(scroll - scrollAnim) < 0.5f) scrollAnim = scroll;
        g.enableScissor(px + 1, ct - 1, px + pw - 1, cb);
        for (Placed p : placed) {
            if (p.y() + p.h() < ct || p.y() > cb) continue;
            boolean visible = mx >= px && my >= ct && my < cb;
            boolean hv = dragging < 0 && visible && over(mx, my, p.x(), p.y(), p.w(), p.h());
            if (p.chip() == null) {
                renderHeader(g, p, hv, all);
            } else {
                renderChip(g, p, hv);
                if (hv) {
                    hoverAbility = p.chip().ability().ordinal();
                    tip = chipTip(p.chip());
                }
            }
        }
        g.disableScissor();
        if (placed.isEmpty()) {
            g.drawCenteredString(font, Component.translatable(search.isEmpty() ? "screen.zhushenspace.preset.none" : "screen.zhushenspace.preset.no_match"),
                    px + pw / 2, ct + 16, BladeBar.IRON_SUB);
        }
        // 滚动条
        if (maxScroll > 0) {
            int trackH = cb - ct, th = Math.max(14, trackH * trackH / Math.max(1, contentH));
            int tyb = ct + (int) ((trackH - th) * (scrollAnim / maxScroll));
            g.fill(px + pw - 5, ct, px + pw - 3, cb, 0x40000000);
            g.fill(px + pw - 5, tyb, px + pw - 3, tyb + th, ZsAnim.withAlpha(BladeBar.EMBER, 0.8f));
            if (scrollAnim > 1) g.fillGradient(px + 1, ct, px + pw - 6, ct + 6, 0x99120806, 0x00120806);
            if (scrollAnim < maxScroll - 1) g.fillGradient(px + 1, cb - 6, px + pw - 6, cb, 0x00120806, 0x99120806);
        }

        // ---- 底栏：提示 / 反馈 + 剑名开关 ----
        int fy = bottom - 12;
        boolean on = ClientUiConfig.get().showSwordNames;
        Component nl = Component.translatable(on ? "screen.zhushenspace.preset.names_on" : "screen.zhushenspace.preset.names_off");
        int nw = font.width(nl) + 10, nx = px + pw - 8 - nw;
        BladeBar.button(g, font, mx, my, nx, fy, nw, 12, nl);
        if (over(mx, my, nx, fy, nw, 12)) tip = lines(Component.translatable("screen.zhushenspace.preset.names_tip"));
        int ux = px + 8;
        if (!undo.isEmpty()) {
            Component ul = Component.translatable("screen.zhushenspace.preset.undo", undo.size());
            int uw = font.width(ul) + 10;
            BladeBar.button(g, font, mx, my, ux, fy, uw, 12, ul);
            ux += uw + 6;
        }
        Component foot;
        int fc = BladeBar.IRON_SUB;
        if (toast != null && now - toastAt < 2200) {
            foot = toast;
            fc = ZsAnim.withAlpha(BladeBar.EMBER_HOT, 1 - Math.max(0, (now - toastAt - 1700) / 500f));
        } else if (dragging >= 0) {
            foot = Component.translatable(target != null ? (fromBar >= 0 && target[0] != fromBar && ctrl ? "screen.zhushenspace.preset.drop_copy"
                    : fromBar >= 0 && slots[target[0]][target[1]] >= 0 ? "screen.zhushenspace.preset.drop_swap" : "screen.zhushenspace.preset.drop_put")
                    : fromBar >= 0 && !overBars(mx, my) ? "screen.zhushenspace.preset.drop_remove" : "screen.zhushenspace.preset.drop_hint");
        } else {
            foot = Component.translatable("screen.zhushenspace.preset.foot", active == 0 ? "A" : "B");
        }
        String fs = font.plainSubstrByWidth(foot.getString(), nx - 6 - ux);
        g.drawString(font, fs, ux, fy + 2, fc, false);

        // ---- 拖动中的技能跟随鼠标 ----
        if (dragging >= 0) {
            SkillAbility a = SkillAbility.values()[dragging];
            boolean remove = target == null && fromBar >= 0 && !overBars(mx, my);
            float p = ZsAnim.pulse(800);
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            int ring = remove ? 0xFFFF5A4A : BladeBar.EMBER_HOT;
            g.fill(mx - 12, my - 12, mx + 12, my + 12, ZsAnim.withAlpha(remove ? 0xFFFF3A2A : BladeBar.EMBER, 0.25f + 0.3f * p));
            g.renderOutline(mx - 12, my - 12, 24, 24, ring);
            if (remove) g.setColor(1f, 0.6f, 0.6f, 0.7f);
            g.blit(a.iconTexture(), mx - 10, my - 10, 20, 20, 0f, 0f, 32, 32, 32, 32);
            g.setColor(1f, 1f, 1f, 1f);
            String n = name(a);
            int lw = font.width(n);
            g.fill(mx + 14, my - 6, mx + 18 + lw, my + 6, 0xCC120806);
            g.drawString(font, n, mx + 16, my - 4, remove ? 0xFFFF8A7A : BladeBar.IRON_TEXT, false);
            if (remove) g.drawString(font, "✕", mx + 6, my - 14, 0xFFFF5A4A, true);
            else if (fromBar >= 0 && ctrl && target != null && target[0] != fromBar) g.drawString(font, "+", mx + 7, my - 14, 0xFF9AFF8A, true);
            g.pose().popPose();
        }

        if (tip != null && dragging < 0) g.renderTooltip(font, tip, mx, my);
    }

    private int pillsWidth(List<String> pills, boolean compact) {
        int w = 0;
        for (String gid : pills) w += font.width(pillLabel(group(gid), compact)) + 8 + 3;
        return w;
    }

    /** 紧凑模式：只保留首字（中文为单字，英文为首字母） */
    private String pillLabel(Group g, boolean compact) {
        String s = g.label().getString();
        if (!compact || G_ALL.equals(g.id()) || s.isEmpty()) return s;
        return s.substring(0, s.offsetByCodePoints(0, 1));
    }

    private void renderHeader(GuiGraphics g, Placed p, boolean hover, List<Chip> all) {
        Group gr = p.group();
        boolean collapsed = search.isEmpty() && COLLAPSED.contains(gr.id());
        int total = 0, owned = 0;
        for (Chip c : all) if (c.group().equals(gr.id())) { total++; if (!c.locked()) owned++; }
        int x = p.x(), y = p.y();
        if (hover) g.fill(x, y, x + p.w(), y + p.h(), 0x30FF9A3C);
        g.fill(x, y + 3, x + 2, y + p.h() - 3, gr.color());
        String arrow = collapsed ? "▶" : "▼";
        g.drawString(font, arrow, x + 5, y + 3, hover ? BladeBar.EMBER_HOT : BladeBar.IRON_SUB, false);
        Component label = gr.label();
        g.drawString(font, label, x + 15, y + 3, hover ? 0xFFFFF4D8 : BladeBar.IRON_TEXT, true);
        String count = owned == total ? String.valueOf(total)
                : Component.translatable("screen.zhushenspace.preset.owned", owned, total).getString();
        int lx = x + 15 + font.width(label) + 6;
        g.drawString(font, count, lx, y + 3, 0xFF8A6A50, false);
        int lineX = lx + font.width(count) + 6;
        if (lineX < x + p.w() - 4) g.fill(lineX, y + 7, x + p.w() - 4, y + 8, ZsAnim.withAlpha(gr.color(), 0.35f));
    }

    private void renderChip(GuiGraphics g, Placed p, boolean hover) {
        Chip c = p.chip();
        SkillAbility a = c.ability();
        int cx = p.x(), cy = p.y(), w = p.w();
        BladeBar.plate(g, cx, cy, w, CHIP_H, hover, c.locked());
        g.blit(a.iconTexture(), cx + 3, cy + 2, 16, 16, 0f, 0f, 32, 32, 32, 32);
        if (c.locked()) g.fill(cx + 3, cy + 2, cx + 19, cy + 18, 0x8C0E1820);
        // 右侧：已装备徽记（A / B）+ 消耗
        int rx = cx + w - 4;
        for (int b = 1; b >= 0; b--) {
            if (indexOf(slots[b], a.ordinal()) < 0) continue;
            String l = b == 0 ? "A" : "B";
            int bw = font.width(l) + 4;
            rx -= bw;
            g.fill(rx, cy + 5, rx + bw, cy + 15, b == 0 ? 0xCC2A4A8A : 0xCC8A2A1A);
            g.drawString(font, l, rx + 2, cy + 6, 0xFFFFF4D8, false);
            rx -= 2;
        }
        String cost = costLabel(a);
        if (cost != null) {
            int cw = font.width(cost);
            rx -= cw + 2;
            g.drawString(font, cost, rx, cy + 6, c.enabled() ? poolColorOf(a) : 0xFF6A5448, true);
        }
        int textColor = c.locked() ? 0xFF6A5448 : !c.enabled() ? 0xFFC9A85C : BladeBar.IRON_TEXT;
        String label = name(a);
        int maxW = rx - 4 - (cx + 23);
        if (font.width(label) > maxW) label = font.plainSubstrByWidth(label, Math.max(0, maxW - font.width("…"))) + "…";
        g.drawString(font, label, cx + 23, cy + (CHIP_H - 8) / 2, textColor, true);
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    /** 消耗短标签：技艺 = 池消耗，太极招式 = 内力 */
    private static String costLabel(SkillAbility a) {
        ArtSkill art = ArtSkill.of(a);
        if (art != null && art.cost > 0) return "◆" + art.cost;
        if (a.isSchoolAbility()) return "◆2";
        return null;
    }

    private static String costPool(SkillAbility a) {
        ArtSkill art = ArtSkill.of(a);
        if (art != null) return art.pool.id;
        if (a.isSchoolAbility() || a.isNeiliAbility()) return "neili";
        return null;
    }

    private static int poolColorOf(SkillAbility a) {
        String id = costPool(a);
        return id == null ? BladeBar.EMBER_HOT : poolColor(id);
    }

    private static void dashed(GuiGraphics g, int x, int y, int w, int h, int col) {
        for (int i = 0; i < w; i += 3) {
            g.fill(x + i, y, x + Math.min(w, i + 2), y + 1, col);
            g.fill(x + i, y + h - 1, x + Math.min(w, i + 2), y + h, col);
        }
        for (int i = 0; i < h; i += 3) {
            g.fill(x, y + i, x + 1, y + Math.min(h, i + 2), col);
            g.fill(x + w - 1, y + i, x + w, y + Math.min(h, i + 2), col);
        }
    }

    // ===================== 提示框 =====================

    private List<FormattedCharSequence> lines(Component... cs) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (Component c : cs) out.addAll(font.split(c, TIP_W));
        return out;
    }

    private List<FormattedCharSequence> abilityLines(SkillAbility a) {
        List<FormattedCharSequence> out = new ArrayList<>();
        out.add(Component.translatable(a.nameKey()).withStyle(Style.EMPTY.withColor(0xFFE6B0).withBold(true)).getVisualOrderText());
        Group gr = group(groupOf(a));
        MutableComponent meta = Component.empty().append(gr.label().copy().withColor(gr.color() & 0xFFFFFF));
        String pool = costPool(a);
        ArtSkill art = ArtSkill.of(a);
        int cost = art != null ? art.cost : a.isSchoolAbility() ? 2 : 0;
        if (pool != null && cost > 0) {
            meta.append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("screen.zhushenspace.preset.tip.cost", cost,
                            Component.translatable("energy.zhushenspace." + pool)).withColor(poolColor(pool) & 0xFFFFFF));
        }
        if (a.cooldownTicks() > 0) {
            float sec = a.cooldownTicks() / 20f;
            String s = sec == Math.floor(sec) ? String.valueOf((int) sec) : String.format(Locale.ROOT, "%.1f", sec);
            meta.append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("screen.zhushenspace.preset.tip.cooldown", s).withStyle(ChatFormatting.GRAY));
        }
        out.addAll(font.split(meta, TIP_W));
        List<String> eq = new ArrayList<>();
        for (int b = 0; b < 2; b++) {
            int i = indexOf(slots[b], a.ordinal());
            if (i >= 0) eq.add((b == 0 ? "A" : "B") + (i + 1));
        }
        if (!eq.isEmpty()) out.addAll(font.split(Component.translatable("screen.zhushenspace.preset.tip.equipped", String.join(" / ", eq))
                .withStyle(ChatFormatting.GOLD), TIP_W));
        out.addAll(font.split(Component.translatable(a.descKey()), TIP_W));
        return out;
    }

    private List<FormattedCharSequence> chipTip(Chip c) {
        List<FormattedCharSequence> out = abilityLines(c.ability());
        if (c.locked()) out.addAll(font.split(Component.translatable("screen.zhushenspace.preset.locked").withStyle(ChatFormatting.RED), TIP_W));
        else if (!c.enabled()) out.addAll(font.split(Component.translatable(c.ability().isArtAbility()
                ? "screen.zhushenspace.preset.no_pool" : "screen.zhushenspace.preset.unequipped").withStyle(ChatFormatting.YELLOW), TIP_W));
        else out.addAll(font.split(Component.translatable("screen.zhushenspace.preset.chip_tip",
                    ClientUiConfig.get().activeBar == 0 ? "A" : "B").withStyle(ChatFormatting.DARK_GRAY), TIP_W));
        return out;
    }

    private List<FormattedCharSequence> slotTip(SkillAbility a, int bar, int slot) {
        List<FormattedCharSequence> out = abilityLines(a);
        out.addAll(font.split(Component.translatable("screen.zhushenspace.preset.slot_tip").withStyle(ChatFormatting.DARK_GRAY), TIP_W));
        return out;
    }

    // ===================== 修改 =====================

    private int[][] snapshot() {
        return new int[][]{slots[0].clone(), slots[1].clone()};
    }

    private boolean same(int[][] s) {
        return Arrays.equals(s[0], slots[0]) && Arrays.equals(s[1], slots[1]);
    }

    /** 本次修改落定：入撤销栈、发送服务端、闪光反馈 */
    private void commit(int[][] before, int fb, int fs, float pitch) {
        if (same(before)) return;
        undo.push(before);
        while (undo.size() > UNDO_MAX) undo.removeLast();
        send();
        if (fb >= 0) { flashBar = fb; flashSlot = fs; flashAt = ZsAnim.nowMs(); }
        ZsTheme.click(pitch);
    }

    private void send() {
        lastEditAt = ZsAnim.nowMs();
        for (int b = 0; b < 2; b++) PacketDistributor.sendToServer(new EquipSkillsPayload(b, slots[b].clone()));
    }

    /** 服务端裁剪后（如技能失效）与本地不一致：编辑停下一会儿后以服务端为准 */
    private void syncFromServer() {
        if (dragging >= 0 || ZsAnim.nowMs() - lastEditAt < SYNC_GUARD_MS || !ClientSkillData.received()) return;
        for (int b = 0; b < 2; b++)
            if (!Arrays.equals(ClientSkillData.bar(b), slots[b])) System.arraycopy(ClientSkillData.bar(b), 0, slots[b], 0, 9);
    }

    private void toast(Component c) {
        toast = c;
        toastAt = ZsAnim.nowMs();
    }

    /** 把技能 a 放进 (bar, slot)；同栏已有时与目标格互换，保证不重复 */
    private void put(int a, int bar, int slot) {
        int prev = slots[bar][slot];
        int dup = indexOf(slots[bar], a);
        if (dup >= 0 && dup != slot) slots[bar][dup] = prev;
        slots[bar][slot] = a;
    }

    /** 快速装备：放进指定栏第一个空格（已在栏中则闪一下原位置） */
    private void quickEquip(int a, int bar) {
        int at = indexOf(slots[bar], a);
        if (at >= 0) {
            flashBar = bar; flashSlot = at; flashAt = ZsAnim.nowMs();
            toast(Component.translatable("screen.zhushenspace.preset.already", bar == 0 ? "A" : "B", at + 1));
            ZsTheme.click(0.9f);
            return;
        }
        int empty = indexOf(slots[bar], -1);
        if (empty < 0) {
            toast(Component.translatable("screen.zhushenspace.preset.full", bar == 0 ? "A" : "B"));
            ZsTheme.click(0.5f);
            return;
        }
        int[][] before = snapshot();
        slots[bar][empty] = a;
        commit(before, bar, empty, 1.15f);
    }

    private void drop(int tb, int ts, boolean copy) {
        int a = dragging;
        int[][] before = snapshot();
        if (fromBar < 0) {
            put(a, tb, ts);
        } else if (tb == fromBar) {
            if (ts == fromSlot) return;
            int prev = slots[tb][ts];
            slots[tb][ts] = a;
            slots[fromBar][fromSlot] = prev; // 同栏：交换
        } else {
            int prev = slots[tb][ts];
            int dup = indexOf(slots[tb], a);
            put(a, tb, ts);
            boolean prevKept = dup >= 0 && dup != ts;
            if (!copy) {
                // 跨栏移动：目标格原有技能换回来处（来处那一栏没有它时）
                slots[fromBar][fromSlot] = !prevKept && prev >= 0 && indexOf(slots[fromBar], prev) < 0 ? prev : -1;
            }
        }
        commit(before, tb, ts, 1.0f);
    }

    private void endDrag() {
        dragging = -1;
        fromBar = -1;
        fromSlot = -1;
    }

    // ===================== 输入 =====================

    public boolean mouseClicked(double mx, double my, int button) {
        boolean shift = Screen.hasShiftDown();
        // 搜索框
        int ty = toolY(), sx0 = px + pw - 8 - SEARCH_W;
        if (over(mx, my, sx0, ty, SEARCH_W, TOOL_H)) {
            if (!search.isEmpty() && (button == 1 || over(mx, my, sx0 + SEARCH_W - 10, ty, 10, TOOL_H))) { search = ""; scroll = 0; }
            searchFocus = true;
            return true;
        }
        searchFocus = false;
        // 底栏
        int fy = bottom - 12;
        if (button == 0) {
            var cfg = ClientUiConfig.get();
            Component nl = Component.translatable(cfg.showSwordNames ? "screen.zhushenspace.preset.names_on" : "screen.zhushenspace.preset.names_off");
            int nw = font.width(nl) + 10;
            if (over(mx, my, px + pw - 8 - nw, fy, nw, 12)) {
                cfg.showSwordNames = !cfg.showSwordNames;
                ClientUiConfig.save();
                ZsTheme.click(cfg.showSwordNames ? 1.2f : 0.9f);
                return true;
            }
            if (!undo.isEmpty()) {
                int uw = font.width(Component.translatable("screen.zhushenspace.preset.undo", undo.size())) + 10;
                if (over(mx, my, px + 8, fy, uw, 12)) { undo(); return true; }
            }
        }
        // 剑柄
        int hilt = hiltAt(mx, my);
        if (hilt >= 0) {
            if (button == 1 && shift) {
                int[][] before = snapshot();
                Arrays.fill(slots[hilt], -1);
                commit(before, -1, -1, 0.7f);
                toast(Component.translatable("screen.zhushenspace.preset.cleared", hilt == 0 ? "A" : "B"));
            } else if (button == 0) {
                var cfg = ClientUiConfig.get();
                if (cfg.activeBar != hilt) {
                    cfg.activeBar = hilt;
                    ClientUiConfig.save();
                    toast(Component.translatable("screen.zhushenspace.preset.switched", hilt == 0 ? "A" : "B"));
                }
                ZsTheme.click(1.2f);
            }
            return true;
        }
        // 格子
        int[] hit = slotAt(mx, my);
        if (hit != null) {
            int id = slots[hit[0]][hit[1]];
            if (button == 1) {
                if (id >= 0) {
                    int[][] before = snapshot();
                    slots[hit[0]][hit[1]] = -1;
                    commit(before, -1, -1, 0.75f);
                }
                return true;
            }
            if (button == 0 && id >= 0) {
                dragging = id;
                fromBar = hit[0];
                fromSlot = hit[1];
                pressX = mx; pressY = my;
                ZsTheme.click(1.1f);
            }
            return true;
        }
        // 分类筛选
        if (button == 0) {
            List<Chip> all = chips();
            List<String> pills = new ArrayList<>();
            pills.add(G_ALL);
            pills.addAll(groupOrder(all));
            boolean compact = pillsWidth(pills, false) > pw - 16 - SEARCH_W - 8;
            int x = px + 8;
            for (String gid : pills) {
                int w = font.width(pillLabel(group(gid), compact)) + 8;
                if (over(mx, my, x, ty, w, TOOL_H)) {
                    filter = gid.equals(filter) && !G_ALL.equals(gid) ? G_ALL : gid;
                    scroll = 0;
                    ZsTheme.click(1.1f);
                    return true;
                }
                x += w + 3;
            }
        }
        // 技能库
        int ct = chipTop(), cb = chipBottom();
        if (my >= ct && my < cb) {
            for (Placed p : layout(chips())) {
                if (!over(mx, my, p.x(), p.y(), p.w(), p.h())) continue;
                if (p.chip() == null) {
                    if (button == 0 && search.isEmpty()) {
                        String id = p.group().id();
                        if (!COLLAPSED.remove(id)) COLLAPSED.add(id);
                        ZsTheme.click(1.05f);
                    }
                    return true;
                }
                Chip c = p.chip();
                if (c.locked() || !c.enabled()) {
                    toast(Component.translatable(c.locked() ? "screen.zhushenspace.preset.locked"
                            : c.ability().isArtAbility() ? "screen.zhushenspace.preset.no_pool" : "screen.zhushenspace.preset.unequipped"));
                    ZsTheme.click(0.5f);
                    return true;
                }
                if (button == 1 || button == 0 && shift) {
                    quickEquip(c.ability().ordinal(), ClientUiConfig.get().activeBar);
                    return true;
                }
                if (button == 0) {
                    dragging = c.ability().ordinal();
                    fromBar = -1;
                    fromSlot = -1;
                    pressX = mx; pressY = my;
                    ZsTheme.click(1.1f);
                }
                return true;
            }
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (button != 0 || dragging < 0) return false;
        int[] hit = slotAt(mx, my);
        if (hit != null) {
            drop(hit[0], hit[1], Screen.hasControlDown());
        } else if (fromBar >= 0 && !overBars(mx, my)) {
            // 拖出剑栏：卸下
            int[][] before = snapshot();
            slots[fromBar][fromSlot] = -1;
            commit(before, -1, -1, 0.7f);
            toast(Component.translatable("screen.zhushenspace.preset.removed", name(SkillAbility.values()[dragging])));
        }
        endDrag();
        return true;
    }

    public boolean mouseScrolled(double mx, double my, double dy) {
        if (dragging >= 0 && overBars(mx, my)) return true;
        scroll = Math.max(0, scroll - (int) Math.signum(dy) * (CHIP_H + ROW_GAP));
        return true;
    }

    public boolean charTyped(char c) {
        if (!searchFocus) return false;
        if (c >= ' ' && search.length() < 24) {
            search += c;
            scroll = 0;
        }
        return true;
    }

    /** @return 是否已处理（预设页优先于面板的全局快捷键） */
    public boolean keyPressed(int key) {
        if (searchFocus) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
                scroll = 0;
            } else if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                searchFocus = false;
            }
            return true; // 输入中屏蔽其他快捷键
        }
        if (key == GLFW.GLFW_KEY_F && Screen.hasControlDown()) {
            searchFocus = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_Z && Screen.hasControlDown() && !undo.isEmpty()) {
            undo();
            return true;
        }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9 && dragging < 0) {
            int n = key - GLFW.GLFW_KEY_1;
            if (hoverAbility >= 0) {
                int bar = ClientUiConfig.get().activeBar;
                int[][] before = snapshot();
                put(hoverAbility, bar, n);
                commit(before, bar, n, 1.15f);
                return true;
            }
            if (hoverBar >= 0 && hoverSlot >= 0 && slots[hoverBar][hoverSlot] >= 0) {
                if (n == hoverSlot) return true;
                int[][] before = snapshot();
                int a = slots[hoverBar][hoverSlot];
                slots[hoverBar][hoverSlot] = slots[hoverBar][n];
                slots[hoverBar][n] = a;
                commit(before, hoverBar, n, 1.05f);
                return true;
            }
        }
        return false;
    }

    private void undo() {
        if (undo.isEmpty()) return;
        int[][] s = undo.pop();
        System.arraycopy(s[0], 0, slots[0], 0, 9);
        System.arraycopy(s[1], 0, slots[1], 0, 9);
        send();
        toast(Component.translatable("screen.zhushenspace.preset.undone"));
        ZsTheme.click(0.8f);
    }

    /** Esc：先取消拖动 / 退出搜索；@return 是否已处理 */
    public boolean back() {
        if (dragging >= 0) {
            endDrag(); // 原位置未改动，直接取消即可
            ZsTheme.click(0.8f);
            return true;
        }
        if (searchFocus) { searchFocus = false; return true; }
        return false;
    }

    /** 切换选项卡时：放弃拖动 */
    public void reset() {
        endDrag();
        searchFocus = false;
    }

    /** 顶部说明（按实际键位） */
    public static Component hint() {
        return Component.translatable("screen.zhushenspace.preset.hint",
                ClientSetup.TOGGLE_COMBAT.getTranslatedKeyMessage(), ClientSetup.SWITCH_SKILL_BAR.getTranslatedKeyMessage());
    }
}

package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.client.ClientEnergyData.PoolView;
import com.zhushen.space.screen.ZsAnim;
import com.zhushen.space.screen.ZsShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 能量池 HUD：屏幕左侧一排细竖条，每条顶部一枚圆形单字徽记（灵 / 精 / 妖 / 佛 / 魔 / 道 / 能 / 内 / 查 / 仙 / 意），
 * 徽记外圈的弧长同样表示剩余量，颜色与条一致，一眼即可分辨是哪个池。
 * <ul>
 *   <li>静息：池满且一段时间未变化 → 条收窄、变暗，只留徽记，尽量不挡画面；</li>
 *   <li>未满：条为中等宽度；</li>
 *   <li>正在使用（数值变化后 3 秒内）→ 条加宽、高亮，竖排浮现具体数值，随后淡出；</li>
 *   <li>扣减时徽记轻弹一下并红闪，回复时白闪；低于两成时徽记外圈红色呼吸；</li>
 *   <li>打开聊天栏时鼠标悬停可查看全名与数值。</li>
 * </ul>
 * 位置与缩放仍可在“主神空间界面设置”中拖拽调整。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public class EnergyHudRenderer {

    // 未缩放基准尺寸
    public static final int SLOT = 13;
    public static final int GAP = 3;
    public static final int BADGE_R = 6;
    public static final int BAR_TOP = 17;
    public static final int BAR_H = 103;
    public static final int TOTAL_H = BAR_TOP + BAR_H;
    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 2.0f;

    private static final long ACTIVE_MS = 3000;
    private static final int GOLD = 0xFFFFC94D;

    /** 每个池的单字徽记 */
    private static final Map<String, String> GLYPH = Map.ofEntries(
            Map.entry("spirit", "灵"), Map.entry("mind", "精"), Map.entry("yokai", "妖"), Map.entry("buddha", "佛"),
            Map.entry("magic", "魔"), Map.entry("dao", "道"), Map.entry("psychic", "能"), Map.entry("neili", "内"),
            Map.entry("chakra", "查"), Map.entry("sage", "仙"), Map.entry("willpower", "意"));

    /** 计算整组能量条的位置与尺寸（位置来自配置，默认屏幕左侧垂直居中，并夹紧在屏幕内） */
    public static float[] layout(int screenW, int screenH, int count, float scale) {
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        float w = (count * SLOT + (count - 1) * GAP) * scale;
        float h = TOTAL_H * scale;
        float x = cfg.energyX < 0 ? 6 : clamp(cfg.energyX, 0, Math.max(0, screenW - w));
        float y = cfg.energyY < 0 ? (screenH - h) / 2f : clamp(cfg.energyY, 0, Math.max(0, screenH - h));
        return new float[]{x, y, w, h};
    }

    /** 游戏内 HUD：只显示当前战斗预设会用到的能量池 */
    public static void render(GuiGraphics g, Font font, int screenW, int screenH) {
        List<PoolView> pools = visiblePools();
        if (pools.isEmpty()) return;
        draw(g, font, screenW, screenH, pools);
    }

    /** 界面设置预览：显示全部能量池（便于摆放位置） */
    public static void renderAll(GuiGraphics g, Font font, int screenW, int screenH) {
        List<PoolView> pools = ClientEnergyData.pools();
        if (pools.isEmpty()) return;
        draw(g, font, screenW, screenH, pools);
    }

    /**
     * 当前使用中的战斗预设栏（A / B）里有技能会消耗的能量池才显示：
     * 技艺 → 其所属能量池（查克拉忍术另含仙术查克拉）；内力系与太极流派技能 → 内力；内力吐息开启中 → 内力。
     * 意志力不由技能消耗（G / B 键使用），始终显示。
     */
    public static List<PoolView> visiblePools() {
        List<PoolView> all = ClientEnergyData.pools();
        if (all.isEmpty()) return all;
        java.util.Set<String> used = new java.util.HashSet<>();
        int bar = ClientUiConfig.get().activeBar;
        for (int slot = 0; slot < 9; slot++) {
            int id = ClientSkillData.slotAbility(bar, slot);
            if (id < 0 || id >= com.zhushen.space.data.SkillAbility.COUNT) continue;
            com.zhushen.space.data.SkillAbility a = com.zhushen.space.data.SkillAbility.values()[id];
            com.zhushen.space.data.ArtSkill s = com.zhushen.space.data.ArtSkill.of(a);
            if (s != null) {
                used.add(s.pool.id);
                if ("chakra".equals(s.pool.id)) used.add("sage");
            } else if (a.isNeiliAbility() || a.isSchoolAbility()) {
                used.add(ClientEnergyData.NEILI_ID);
            }
        }
        if (ClientEnergyData.breathEnabled()) used.add(ClientEnergyData.NEILI_ID);
        List<PoolView> out = new java.util.ArrayList<>(all.size());
        for (PoolView p : all) if (used.contains(p.id()) || "willpower".equals(p.id())) out.add(p);
        return out;
    }

    private static void draw(GuiGraphics g, Font font, int screenW, int screenH, List<PoolView> pools) {
        float scale = ClientUiConfig.get().energyScale;
        float[] pos = layout(screenW, screenH, pools.size(), scale);
        long now = ZsAnim.nowMs();
        g.pose().pushPose();
        g.pose().translate(pos[0], pos[1], 0);
        g.pose().scale(scale, scale, 1f);
        for (int i = 0; i < pools.size(); i++) drawSlot(g, font, i * (SLOT + GAP), pools.get(i), now);
        g.pose().popPose();

        // 聊天栏打开时：悬停显示全名与数值
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ChatScreen) {
            double mx = mc.mouseHandler.xpos() * screenW / Math.max(1, mc.getWindow().getScreenWidth());
            double my = mc.mouseHandler.ypos() * screenH / Math.max(1, mc.getWindow().getScreenHeight());
            double lx = (mx - pos[0]) / scale, ly = (my - pos[1]) / scale;
            if (ly >= -2 && ly <= TOTAL_H + 2 && lx >= 0) {
                int i = (int) (lx / (SLOT + GAP));
                if (i < pools.size() && lx - i * (SLOT + GAP) <= SLOT) {
                    PoolView p = pools.get(i);
                    Component tip = name(p.id()).copy().withColor(p.color() & 0xFFFFFF)
                            .append(Component.literal("  " + p.currentInt() + " / " + p.maxInt()).withColor(0xFFFFFF));
                    g.renderTooltip(font, tip, (int) mx, (int) my);
                }
            }
        }
    }

    // ===== 单个池 =====

    /** 每个池的状态：上次数值、变化时刻、方向（+1 回复 / -1 扣减） */
    private static final Map<String, double[]> LAST = new HashMap<>();

    private static void drawSlot(GuiGraphics g, Font font, float x, PoolView pool, long now) {
        double[] st = LAST.computeIfAbsent(pool.id(), k -> new double[]{pool.current(), 0, 0});
        if (Math.abs(pool.current() - st[0]) >= 0.5) {
            st[2] = pool.current() > st[0] ? 1 : -1;
            st[0] = pool.current();
            st[1] = now;
        }
        long since = now - (long) st[1];
        float frac = pool.max() > 0 ? ZsAnim.clamp01((float) (pool.current() / pool.max())) : 0f;
        int h = pool.id().hashCode();
        float shown = ZsAnim.tween(ZsAnim.key(91, h, 1), frac, 8);
        boolean active = st[1] > 0 && since < ACTIVE_MS;
        float f = ZsAnim.tween(ZsAnim.key(91, h, 2), active ? 1f : frac >= 0.999f ? 0f : 0.45f, 7);
        float alpha = 0.55f + 0.45f * f;
        int col = 0xFF000000 | (pool.color() & 0xFFFFFF);
        boolean breath = ClientEnergyData.NEILI_ID.equals(pool.id()) && ClientEnergyData.breathEnabled();
        float flash = 1f - ZsAnim.clamp01(since / 450f);
        if (st[1] == 0) flash = 0f;
        float low = frac < 0.2f ? ZsAnim.pulse(900) : 0f;
        float cx = x + SLOT / 2f;

        // ── 竖条 ──
        float bw = 3.5f + 5.5f * f;
        float bx = cx - bw / 2f, by = BAR_TOP, bh = BAR_H;
        ZsShapes.roundRect(g, bx - 1, by - 1, bw + 2, bh + 2, (bw + 2) / 2f, ZsShapes.fade(0xE0070910, alpha), ZsShapes.fade(0xE0070910, alpha));
        ZsShapes.roundRect(g, bx, by, bw, bh, bw / 2f, ZsShapes.fade(0xFF1A2030, alpha), ZsShapes.fade(0xFF0D1018, alpha));
        float len = bh * shown;
        if (len > 0.5f) {
            int light = ZsShapes.lerp(col, 0xFFFFFFFF, 0.38f), dark = ZsShapes.lerp(col, 0xFF000000, 0.4f);
            float fy = by + bh - len;
            ZsShapes.roundRect(g, bx, fy, bw, len, Math.min(bw / 2f, len / 2f), ZsShapes.fade(light, alpha), ZsShapes.fade(dark, alpha));
            // 向上流动的光带
            float ph = ZsAnim.phase(2400 + (Math.abs(h) % 600));
            float bc = by + bh - (len + 12) * ph + 6;
            float y0 = Math.max(fy + 1, bc - 7), y1 = Math.min(by + bh - 1, bc + 7);
            if (y1 > y0) {
                int clear = 0x00FFFFFF, glint = ZsShapes.fade(0x66FFFFFF, alpha);
                float m = Math.max(y0, Math.min(y1, bc));
                ZsShapes.quad(g, bx + 0.5f, y0, clear, bx + 0.5f, m, glint, bx + bw - 0.5f, m, glint, bx + bw - 0.5f, y0, clear);
                ZsShapes.quad(g, bx + 0.5f, m, glint, bx + 0.5f, y1, clear, bx + bw - 0.5f, y1, clear, bx + bw - 0.5f, m, glint);
            }
            // 液面亮线
            ZsShapes.line(g, bx + bw * 0.18f, fy + 0.5f, bx + bw * 0.82f, fy + 0.5f, 1f,
                    ZsShapes.fade(0xFFFFFFFF, (0.55f + 0.35f * ZsAnim.pulse(1200)) * alpha), ZsShapes.fade(0xFFFFFFFF, (0.55f + 0.35f * ZsAnim.pulse(1200)) * alpha));
        }
        // 刻度（1/4、1/2、3/4）
        if (f > 0.2f) {
            for (int q = 1; q <= 3; q++) {
                float ty = by + bh * (1f - q / 4f);
                ZsShapes.line(g, bx + 0.5f, ty, bx + bw - 0.5f, ty, q == 2 ? 0.8f : 0.5f,
                        ZsShapes.fade(0x99000000, f), ZsShapes.fade(0x99000000, f));
            }
        }
        // 增减闪光
        if (flash > 0f) {
            int fc = st[2] > 0 ? 0xFFFFFFFF : 0xFFFF5050;
            ZsShapes.roundRect(g, bx, by, bw, bh, bw / 2f, ZsShapes.fade(fc, 0.35f * flash), ZsShapes.fade(fc, 0.35f * flash));
        }
        if (breath) {
            float p = ZsAnim.pulse(1800);
            ZsShapes.roundRectOutline(g, bx - 1.5f, by - 1.5f, bw + 3, bh + 3, (bw + 3) / 2f, 1f, ZsShapes.fade(GOLD, 0.35f + 0.5f * p));
        }
        // 数值：使用中竖排浮现
        float textA = ZsAnim.clamp01((f - 0.62f) / 0.38f);
        if (textA * 255 >= 10) {
            String text = pool.currentInt() + "/" + pool.maxInt();
            g.pose().pushPose();
            g.pose().translate(cx, by + bh / 2f, 0);
            g.pose().mulPose(new Quaternionf().rotateZ((float) Math.toRadians(-90)));
            g.pose().scale(0.7f, 0.7f, 1f);
            g.drawString(font, text, -font.width(text) / 2, -4, ZsAnim.withAlpha(0xFFFFFFFF, textA), true);
            g.pose().popPose();
        }

        // ── 徽记 ──
        float pop = 1f + (st[2] < 0 ? 0.16f * flash * flash : 0f);
        float r = BADGE_R * pop, cy = BADGE_R + 0.5f;
        int ringCol = breath ? ZsShapes.lerp(col, GOLD, 0.5f + 0.5f * ZsAnim.pulse(1800)) : col;
        if (low > 0f) ringCol = ZsShapes.lerp(ringCol, 0xFFFF3B3B, low);
        if (f > 0.05f) ZsShapes.glow(g, cx, cy, r, 3.5f, ZsShapes.fade(ringCol, 0.35f * f + 0.25f * low));
        ZsShapes.disc(g, cx, cy, r, ZsShapes.fade(0xF51A2030, alpha), ZsShapes.fade(0xF50A0D14, alpha));
        // 外圈：暗轨 + 按剩余量点亮的弧（自顶部顺时针）
        ZsShapes.arc(g, cx, cy, r - 1.3f, r, 0, Math.PI * 2, ZsShapes.fade(0xFF2A3142, alpha), ZsShapes.fade(0xFF2A3142, alpha),
                ZsShapes.fade(0xFF2A3142, alpha), ZsShapes.fade(0xFF2A3142, alpha), true);
        if (shown > 0.002f) {
            int lit = ZsShapes.fade(ZsShapes.lerp(ringCol, 0xFFFFFFFF, 0.15f), alpha);
            ZsShapes.arc(g, cx, cy, r - 1.3f, r, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * shown, lit, lit, lit, lit, true);
        }
        // 徽记与竖条之间的细连线
        ZsShapes.line(g, cx, cy + r + 0.5f, cx, by - 1.2f, 0.8f, ZsShapes.fade(col, 0.7f * alpha), ZsShapes.fade(col, 0.2f * alpha));
        // 单字
        String glyph = glyph(pool.id());
        int gc = ZsShapes.fade(ZsShapes.lerp(0xFFFFFFFF, col, 0.22f), 0.75f + 0.25f * alpha);
        g.pose().pushPose();
        g.pose().translate(cx - font.width(glyph) / 2f + 0.5f, cy - 3.5f, 0);
        g.drawString(font, glyph, 0, 0, gc, false);
        g.pose().popPose();
    }

    /** 池的单字徽记：内置池用固定字；自定义池取名称首字 */
    static String glyph(String id) {
        String gl = GLYPH.get(id);
        if (gl != null) return gl;
        String key = "energy.zhushenspace." + id;
        String name = I18n.exists(key) ? I18n.get(key) : id;
        if (name.isEmpty()) return "?";
        int cp = name.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(cp)));
    }

    static Component name(String id) {
        String key = "energy.zhushenspace." + id;
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(id);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    // ===== HUD 事件 =====

    /** 游戏内 HUD：拥有能量池时渲染左侧能量条 */
    @SubscribeEvent
    public static void onHudRender(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (ClientEnergyData.pools().isEmpty()) return;
        if (mc.screen instanceof com.zhushen.space.screen.EnergyUiConfigScreen) return; // 设置界面自己画全部能量池
        ClientUiConfig.saveNow(); // 惰性落盘配置修改
        GuiGraphics g = event.getGuiGraphics();
        render(g, mc.font, g.guiWidth(), g.guiHeight());
    }

    /** 无能量池时渲染一条示例池（界面设置预览用） */
    public static void renderDemo(GuiGraphics g, Font font, int screenW, int screenH) {
        draw(g, font, screenW, screenH, List.of(new PoolView("spirit", 60, 100, 0xFF3BA9E0)));
    }
}

package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.item.MahoragaWheelItem;
import com.zhushen.space.screen.BladeBar;
import com.zhushen.space.screen.ZsAnim;
import com.zhushen.space.screen.ZsShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD 统一排版（每帧在所有 HUD 绘制之前计算一次），解决战斗模式下右侧面板互相重叠的问题。
 *
 * <pre>
 *  ┌──────────────────────────────── 屏幕 ─────────────────────────────────┐
 *  │                         [伤害范围]                    [状态效果小图标] │
 *  │                                                          [伤势面板]   │
 *  │ [能量]                                                   [肢体面板]   │  ← 右侧栏：自上而下堆叠，
 *  │                                                          [法阵·适应]  │     放不下时整体等比缩小
 *  │                                                          [穿脱进度] ▮ │
 *  │                [防御]                         [状态标签……]          ▮ │  ← 迷你物品栏（战斗模式）
 *  │                [血量]   [食物]                [状态标签……]          ▮ │
 *  │              [════════ 巨剑技能栏 ════════]   [体力/水分/精力]       ▮ │
 *  └───────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <ul>
 *   <li>右侧栏：伤势、肢体、法阵、穿脱进度按顺序堆叠；被玩家在「界面设置」里拖动过的面板不参与堆叠。
 *       总高度超出可用空间时整栏等比缩小（不小于 {@link #MIN_SCALE}）；栏底低于迷你物品栏顶部时整栏左移让位。</li>
 *   <li>战斗模式下原版状态效果改为一行小图标（顶部右侧），不再占用两行 24 像素的大图标。</li>
 *   <li>右下角：生存条位于技能栏（战斗模式为巨剑栏）右侧、迷你物品栏左侧；不良状态以紧凑标签堆叠在生存条上方。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class HudLayout {
    private HudLayout() {}

    /** 战斗模式右侧迷你物品栏占用的宽度（含间隙） */
    public static final int SIDE_HOTBAR_W = 27;
    public static final int MARGIN = 4, GAP = 4;
    public static final float MIN_SCALE = 0.55f;
    /** 状态效果小图标 */
    public static final int FX = 13, FX_PITCH = 15;

    /** 右侧栏各面板 {x, y, w, h}；null = 本帧不显示或使用自定义位置 */
    public static float[] wound, limb, adapt, gear;
    /** 右侧栏的整体缩放 */
    public static float columnScale = 1f;
    /** 生存条与状态标签区域：左缘、生存条宽度、状态标签可用宽度、状态标签区域顶部 */
    public static int clusterX, survivalW, chipsW, chipsTop;
    /** 战斗模式小图标状态效果：右缘、顶部、每行个数、行数 */
    public static int fxRight, fxTop, fxPerRow, fxRows;

    private static boolean computed;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPre(RenderGuiEvent.Pre e) {
        compute(Minecraft.getInstance(), e.getGuiGraphics().guiWidth(), e.getGuiGraphics().guiHeight(), false);
    }

    /** 尚未计算过（例如第一帧之前）时补算一次 */
    public static void ensure(int w, int h) {
        if (!computed) compute(Minecraft.getInstance(), w, h, false);
    }

    /**
     * @param preview 界面设置预览：按战斗模式排版，伤势 / 肢体面板总是参与（示例数据），不计状态效果与状态标签
     */
    public static void compute(Minecraft mc, int w, int h, boolean preview) {
        computed = true;
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        Font font = mc.font;
        boolean combat = preview || CombatModeClient.combatMode();
        int hotbarTop = h - 26 - 9 * 16 - 4;

        // ── 右下角：生存条 + 状态标签 ──
        int right;
        if (combat) {
            clusterX = w / 2 + BladeBar.W / 2 + 5;
            right = w - SIDE_HOTBAR_W - 2;
        } else {
            clusterX = w / 2 + 91 + 6;
            right = w - MARGIN;
        }
        int avail = right - clusterX;
        if (avail < 44) { // 窄屏：退回到技能栏右端上方
            clusterX = Math.max(0, right - 60);
            avail = right - clusterX;
        }
        survivalW = Math.max(40, Math.min(78, avail));
        chipsW = Math.max(40, Math.min(combat ? avail : 150, avail));
        int rows = preview ? 0 : ClientCondition.chipRows(font, chipsW);
        chipsTop = h - 23 - rows * (ClientCondition.CHIP_H + ClientCondition.CHIP_GAP) + (rows > 0 ? ClientCondition.CHIP_GAP : 0);

        // ── 顶部右侧：状态效果 ──
        int top = MARGIN;
        fxRows = 0;
        fxRight = w - MARGIN;
        fxTop = 3;
        if (!preview && mc.player != null) {
            int n = 0;
            boolean ben = false, harm = false;
            for (MobEffectInstance inst : mc.player.getActiveEffects()) {
                if (!inst.showIcon()) continue;
                n++;
                if (inst.getEffect().value().isBeneficial()) ben = true;
                else harm = true;
            }
            if (combat) {
                fxPerRow = Math.max(4, Math.min(10, (w / 3) / FX_PITCH));
                fxRows = n == 0 ? 0 : (n + fxPerRow - 1) / fxPerRow;
                if (fxRows > 0) top = fxTop + fxRows * FX_PITCH + 2;
            } else {
                // 原版大图标：增益一行（y 1~25），减益一行（y 27~51）
                top = harm ? 54 : ben ? 28 : MARGIN;
            }
        }

        // ── 右侧栏 ──
        wound = limb = adapt = gear = null;
        List<float[]> items = new ArrayList<>();
        List<Integer> which = new ArrayList<>();
        if (combat && cfg.woundX < 0 && cfg.woundY < 0) {
            items.add(new float[]{WoundHudRenderer.W * cfg.woundScale, WoundHudRenderer.H * cfg.woundScale});
            which.add(0);
        }
        if (combat && cfg.limbHudEnabled && (cfg.limbX < 0 || cfg.limbY < 0)) {
            float[] ls = LimbHudRenderer.baseSize();
            items.add(new float[]{ls[0] * cfg.limbScale, ls[1] * cfg.limbScale});
            which.add(1);
        }
        if (!preview && combat && mc.player != null
                && mc.player.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof MahoragaWheelItem) {
            items.add(new float[]{GearHudRenderer.ADAPT_W, GearHudRenderer.adaptHeight()});
            which.add(2);
        }
        if (!preview) {
            int gh = GearHudRenderer.gearHeight();
            if (gh > 0) {
                items.add(new float[]{GearHudRenderer.GEAR_W, gh});
                which.add(3);
            }
        }
        if (items.isEmpty()) return;
        float total = 0;
        for (float[] it : items) total += it[1];
        total += GAP * (items.size() - 1);
        int bottom = Math.min(h - MARGIN, chipsTop - GAP);
        if (!combat) bottom = h - MARGIN - 40; // 非战斗：右侧栏只在上半部分
        float space = Math.max(20, bottom - top);
        float k = total > space ? Math.max(MIN_SCALE, space / total) : 1f;
        columnScale = k;
        boolean low = combat && top + total * k > hotbarTop - 2;
        float colRight = low ? w - SIDE_HOTBAR_W - 1 : w - MARGIN;
        float y = top;
        for (int i = 0; i < items.size(); i++) {
            float iw = items.get(i)[0] * k, ih = items.get(i)[1] * k;
            float[] r = {colRight - iw, y, iw, ih};
            switch (which.get(i)) {
                case 0 -> wound = r;
                case 1 -> limb = r;
                case 2 -> adapt = r;
                default -> gear = r;
            }
            y += ih + GAP * k;
        }
    }

    // ===== 战斗模式：状态效果改为一行小图标 =====

    @SubscribeEvent
    public static void onEffectsLayer(RenderGuiLayerEvent.Pre e) {
        if (CombatModeClient.combatMode() && VanillaGuiLayers.EFFECTS.equals(e.getName())) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPost(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (!CombatModeClient.combatMode() || mc.player == null || mc.options.hideGui || fxRows == 0) return;
        GuiGraphics g = e.getGuiGraphics();
        List<MobEffectInstance> list = new ArrayList<>();
        for (MobEffectInstance inst : mc.player.getActiveEffects()) if (inst.showIcon()) list.add(inst);
        // 增益在前，减益在后；同类按剩余时间
        list.sort((a, b) -> {
            boolean ba = a.getEffect().value().isBeneficial(), bb = b.getEffect().value().isBeneficial();
            if (ba != bb) return ba ? -1 : 1;
            return a.compareTo(b);
        });
        Font font = mc.font;
        var tm = mc.getMobEffectTextures();
        for (int i = 0; i < list.size(); i++) {
            MobEffectInstance inst = list.get(i);
            int row = i / fxPerRow, col = i % fxPerRow;
            int x = fxRight - FX - col * FX_PITCH, y = fxTop + row * FX_PITCH;
            boolean good = inst.getEffect().value().isBeneficial();
            int edge = good ? 0xFF7FD8FF : 0xFFFF6B6B;
            float a = 1f;
            if (!inst.isInfiniteDuration() && inst.endsWithin(200)) {
                // 即将结束：闪烁（与原版一致的节奏）
                int d = inst.getDuration();
                a = Math.max(0.25f, d / 200f * 0.6f + 0.4f * (float) Math.abs(Math.cos(d * Math.PI / 5.0)));
            }
            ZsShapes.roundRect(g, x - 1, y - 1, FX + 2, FX + 2, 3, ZsShapes.fade(0xE0151A24, a), ZsShapes.fade(0xE00B0E14, a));
            ZsShapes.roundRectOutline(g, x - 1, y - 1, FX + 2, FX + 2, 3, inst.isAmbient() ? 0.5f : 0.9f, ZsShapes.fade(edge, 0.85f * a));
            g.flush();
            TextureAtlasSprite sprite = tm.get(inst.getEffect());
            g.setColor(1f, 1f, 1f, a);
            g.blit(x + 1, y + 1, 0, FX - 2, FX - 2, sprite);
            g.setColor(1f, 1f, 1f, 1f);
            // 等级（II 以上）与剩余时间（1 分钟内）用小字标在角上
            int amp = inst.getAmplifier();
            if (amp > 0) small(g, font, roman(amp + 1), x + FX - 1, y - 1, 0xFFFFE9A8, true);
            if (!inst.isInfiniteDuration() && inst.getDuration() < 1200) {
                small(g, font, String.valueOf(Math.max(1, inst.getDuration() / 20)), x + FX - 1, y + FX - 4, 0xFFFFFFFF, true);
            }
        }
    }

    private static void small(GuiGraphics g, Font font, String s, float rightX, float y, int color, boolean alignRight) {
        g.pose().pushPose();
        float sc = 0.5f;
        g.pose().translate(alignRight ? rightX - font.width(s) * sc : rightX, y, 200);
        g.pose().scale(sc, sc, 1f);
        g.drawString(font, s, 0, 0, color, true);
        g.pose().popPose();
    }

    private static String roman(int n) {
        return switch (n) {
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    /** 动画用：当前时间 */
    static long now() { return ZsAnim.nowMs(); }
}

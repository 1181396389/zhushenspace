package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.client.ClientEnergyData.PoolView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Quaternionf;

import java.util.List;

/**
 * 能量池 HUD 渲染：屏幕左侧竖向能量条。
 *
 * - 仅在玩家拥有能量池时渲染
 * - 多个能量池并排显示，每个池颜色恒定（按 id）
 * - 当前值/上限以竖排数字内嵌在条内，不增加条的长度
 * - 位置与缩放可在"主神空间界面设置"中拖拽调整
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public class EnergyHudRenderer {

    // 未缩放基准尺寸
    public static final int BAR_W = 10;
    public static final int BAR_H = 120;
    public static final int GAP = 4;
    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 2.0f;

    private static final int BG = 0xEE0A1622;
    private static final int BORDER = 0xFF5B9BD5;
    private static final int TEXT = 0xFFFFFFFF;

    /** 计算整组能量条的位置与尺寸（位置来自配置，默认屏幕左侧垂直居中，并夹紧在屏幕内） */
    public static float[] layout(int screenW, int screenH, int count, float scale) {
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        float w = (count * BAR_W + (count - 1) * GAP) * scale;
        float h = BAR_H * scale;
        float x = cfg.energyX < 0 ? 6 : clamp(cfg.energyX, 0, Math.max(0, screenW - w));
        float y = cfg.energyY < 0 ? (screenH - h) / 2f : clamp(cfg.energyY, 0, Math.max(0, screenH - h));
        return new float[]{x, y, w, h};
    }

    public static void render(GuiGraphics g, Font font, int screenW, int screenH) {
        List<PoolView> pools = ClientEnergyData.pools();
        if (pools.isEmpty()) return;
        float scale = ClientUiConfig.get().energyScale;
        float[] pos = layout(screenW, screenH, pools.size(), scale);
        int bw = Math.max(2, Math.round(BAR_W * scale));
        int bh = Math.max(2, Math.round(BAR_H * scale));
        int gap = Math.max(1, Math.round(GAP * scale));

        for (int i = 0; i < pools.size(); i++) {
            int bx = (int) pos[0] + i * (bw + gap);
            drawBar(g, font, bx, (int) pos[1], bw, bh, pools.get(i), scale);
        }
    }

    private static void drawBar(GuiGraphics g, Font font, int x, int y, int w, int h, PoolView pool, float scale) {
        g.fill(x, y, x + w, y + h, BG);
        double frac = pool.max() > 0 ? pool.current() / pool.max() : 0;
        int fillH = (int) Math.round((h - 2) * Math.min(1.0, Math.max(0.0, frac)));
        if (fillH > 0) {
            int fill = 0xDD000000 | (pool.color() & 0x00FFFFFF);
            g.fill(x + 1, y + h - 1 - fillH, x + w - 1, y + h - 1, fill);
        }
        g.renderOutline(x, y, w, h, barBorder(pool));

        // 具体数字：竖排（自下而上）内嵌在条中，不增加条的长度
        String text = pool.currentInt() + "/" + pool.maxInt();
        float ts = Math.min(1.1f, 0.9f * scale);
        g.pose().pushPose();
        g.pose().translate(x + w / 2f, y + h / 2f, 0);
        g.pose().mulPose(new Quaternionf().rotateZ((float) Math.toRadians(-90)));
        g.pose().scale(ts, ts, 1f);
        int tw = font.width(text);
        g.drawString(font, text, -tw / 2, -4, TEXT, true);
        g.pose().popPose();
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    /** 能量条描边：内力吐息开启时内力池使用鎏金描边高亮 */
    private static int barBorder(PoolView pool) {
        if (ClientEnergyData.NEILI_ID.equals(pool.id()) && ClientEnergyData.breathEnabled()) {
            return 0xFFFFC94D;
        }
        return BORDER;
    }

    // ===== HUD 事件 =====

    /** 游戏内 HUD：拥有能量池时渲染左侧竖向能量条 */
    @SubscribeEvent
    public static void onHudRender(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (ClientEnergyData.pools().isEmpty()) return;
        ClientUiConfig.saveNow(); // 惰性落盘配置修改
        GuiGraphics g = event.getGuiGraphics();
        render(g, mc.font, g.guiWidth(), g.guiHeight());
    }

    /** 无能量池时渲染一条示例池（界面设置预览用，不影响布局计算之外的任何状态） */
    public static void renderDemo(GuiGraphics g, Font font, int screenW, int screenH) {
        float scale = ClientUiConfig.get().energyScale;
        float[] pos = layout(screenW, screenH, 1, scale);
        int bw = Math.max(2, Math.round(BAR_W * scale));
        int bh = Math.max(2, Math.round(BAR_H * scale));
        drawBar(g, font, (int) pos[0], (int) pos[1], bw, bh, new PoolView("demo", 60, 100, 0xFF3BA9E0), scale);
    }
}

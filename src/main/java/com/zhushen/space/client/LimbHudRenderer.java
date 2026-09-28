package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.LimbPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 肢体状态人形图：位于伤势面板下方（战斗模式，或有部位受伤 / 断肢时常驻）。
 * 颜色：青（完好）→ 黄 → 红（濒危）；断肢为暗灰叉；头部清空红色闪烁；受击部位白闪。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class LimbHudRenderer {

    private LimbHudRenderer() {
    }

    private static final int U = 2; // 1 模型像素 = 2 屏幕像素

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        int mask = ClientLimbData.mask(mc.player.getId());
        if (!CombatModeClient.combatMode() && mask == 0 && !ClientLimbData.anyDamage()) return;
        GuiGraphics g = event.getGuiGraphics();
        float scale = ClientUiConfig.get().woundScale;
        float[] pos = WoundHudRenderer.layout(g.guiWidth(), g.guiHeight(), scale);
        int w = 16 * U;
        int x = Math.round(pos[0] + pos[2] - w - 4);
        int y = Math.round(pos[1] + pos[3] + 6);
        if (y + 34 * U > g.guiHeight()) y = Math.max(0, Math.round(pos[1]) - 34 * U - 6);
        long now = System.currentTimeMillis();

        // 底板
        g.fill(x - 3, y - 3, x + w + 3, y + 32 * U + 12, 0x88101820);
        // 头 8x8，躯干 8x12，臂 4x12，腿 4x12（模型像素）
        part(g, x + 4 * U, y, 8, 8, LimbPart.HEAD, mask, now);
        g.fill(x + 4 * U, y + 8 * U, x + 12 * U, y + 20 * U, 0xFF3E8FA3); // 躯干（由总伤势承担）
        part(g, x, y + 8 * U, 4, 12, LimbPart.RIGHT_ARM, mask, now);
        part(g, x + 12 * U, y + 8 * U, 4, 12, LimbPart.LEFT_ARM, mask, now);
        part(g, x + 4 * U, y + 20 * U, 4, 12, LimbPart.RIGHT_LEG, mask, now);
        part(g, x + 8 * U, y + 20 * U, 4, 12, LimbPart.LEFT_LEG, mask, now);

        Component label = Component.translatable("hud.zhushenspace.limb.title");
        g.drawCenteredString(mc.font, label, x + w / 2, y + 32 * U + 2, 0xFF8FC7D6);
    }

    private static void part(GuiGraphics g, int x, int y, int pw, int ph, LimbPart p, int mask, long now) {
        int w = pw * U, h = ph * U;
        if ((mask & p.bit()) != 0) {
            // 断肢：暗灰虚框 + 红叉
            g.fill(x, y, x + w, y + h, 0x55303030);
            for (int i = 0; i < Math.min(w, h); i++) {
                int yy = y + i * h / Math.min(w, h);
                g.fill(x + i * w / Math.min(w, h), yy, x + i * w / Math.min(w, h) + 1, yy + 1, 0xFFB02030);
                g.fill(x + w - 1 - i * w / Math.min(w, h), yy, x + w - i * w / Math.min(w, h), yy + 1, 0xFFB02030);
            }
            return;
        }
        float r = ClientLimbData.current(p) / (float) ClientLimbData.max(p);
        int col = r > 0.6f ? 0xFF4FC3D7 : r > 0.3f ? 0xFFF5D76E : r > 0f ? 0xFFE8603A : 0xFFFF2030;
        if (r <= 0f && (now / 250) % 2 == 0) col = 0xFF601018; // 头部清空：闪烁
        g.fill(x, y, x + w, y + h, 0xFF1A2A30);
        int fillH = Math.max(r > 0 ? 1 : 0, Math.round(h * r));
        g.fill(x, y + h - fillH, x + w, y + h, col);
        long since = now - ClientLimbData.hitAt(p);
        if (since < 300) {
            int a = (int) (200 * (1 - since / 300f));
            g.fill(x, y, x + w, y + h, (a << 24) | 0xFFFFFF);
        }
    }
}

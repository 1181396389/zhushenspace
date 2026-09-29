package com.zhushen.space.screen;

import com.zhushen.space.client.ClientArtData;
import com.zhushen.space.client.ClientSetup;
import com.zhushen.space.data.ArtSkill;
import com.zhushen.space.network.ArtActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 技艺轮盘：留手（点击开关，滚轮 ±5%）、增幅（开关）、八阵图元素（点击 / 滚轮切换）。
 * 按住轮盘键时显示，松开关闭；也可按 Esc。
 */
public class ArtWheelScreen extends Screen {
    private static final int R = 62, BTN = 26;
    private final long openedAt = System.currentTimeMillis();

    public ArtWheelScreen() { super(Component.translatable("screen.zhushenspace.wheel")); }

    @Override
    public boolean isPauseScreen() { return false; }

    private int[] pos(int i) {
        double a = -Math.PI / 2 + i * (Math.PI * 2 / 3);
        return new int[]{width / 2 + (int) (Math.cos(a) * R), height / 2 + (int) (Math.sin(a) * R)};
    }

    private int hovered(double mx, double my) {
        for (int i = 0; i < 3; i++) {
            int[] p = pos(i);
            if ((mx - p[0]) * (mx - p[0]) + (my - p[1]) * (my - p[1]) <= BTN * BTN) return i;
        }
        return -1;
    }

    private static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.sqrt(r * r - dy * dy);
            g.fill(cx - dx, cy + dy, cx + dx, cy + dy + 1, color);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x66000010);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        float in = Math.min(1f, (System.currentTimeMillis() - openedAt) / 160f);
        int cx = width / 2, cy = height / 2;
        disc(g, cx, cy, (int) ((R + BTN + 6) * in), 0x88100C20);
        disc(g, cx, cy, (int) ((R - BTN - 4) * in), 0xCC1A1430);
        int hv = hovered(mx, my);
        String[] labels = new String[3];
        String[] values = new String[3];
        int hb = ClientArtData.holdback();
        labels[0] = Component.translatable("screen.zhushenspace.wheel.holdback").getString();
        values[0] = hb < 0 ? Component.translatable("screen.zhushenspace.wheel.off").getString() : hb + "%";
        labels[1] = Component.translatable("screen.zhushenspace.wheel.amplify").getString();
        values[1] = Component.translatable(ClientArtData.amplify() ? "screen.zhushenspace.wheel.full"
                : "screen.zhushenspace.wheel.off").getString();
        labels[2] = Component.translatable("ability.zhushenspace.art_eight_formation").getString();
        ArtSkill ef = ArtSkill.EIGHT_FORMATION;
        values[2] = ClientArtData.owns(ef)
                ? Component.translatable(ef.optionKey(Math.min(ef.options.length - 1, ClientArtData.current(ef)))).getString()
                : Component.translatable("screen.zhushenspace.wheel.none").getString();
        for (int i = 0; i < 3; i++) {
            int[] p = pos(i);
            int px = cx + (int) ((p[0] - cx) * in), py = cy + (int) ((p[1] - cy) * in);
            disc(g, px, py, BTN, hv == i ? 0xEE6B4EC8 : 0xDD2E2450);
            disc(g, px, py, BTN - 2, hv == i ? 0xEE3A2A78 : 0xDD1A1430);
            g.drawCenteredString(font, labels[i], px, py - 9, 0xFFEDE8F6);
            g.drawCenteredString(font, values[i], px, py + 3, 0xFFE8C77E);
        }
        g.drawCenteredString(font, Component.translatable("screen.zhushenspace.wheel.hint"), cx, cy - 4, 0xFFBBAEDD);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int hv = hovered(mx, my);
        if (hv == 0) {
            int hb = ClientArtData.holdback();
            send(3, -1, hb < 0 ? 50 : -1);
        } else if (hv == 1) {
            send(4, -1, ClientArtData.amplify() ? 0 : 1);
        } else if (hv == 2) {
            send(5, ArtSkill.EIGHT_FORMATION.ordinal(), button == 1 ? -1 : 1);
        } else {
            return super.mouseClicked(mx, my, button);
        }
        ZsTheme.click(1.2f);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int hv = hovered(mx, my);
        int dir = sy > 0 ? 1 : -1;
        if (hv == 0) {
            int hb = ClientArtData.holdback();
            send(3, -1, Math.max(1, Math.min(100, (hb < 0 ? 100 : hb) + dir * 5)));
            return true;
        }
        if (hv == 2) { send(5, ArtSkill.EIGHT_FORMATION.ordinal(), dir); return true; }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (ClientSetup.ART_WHEEL.matches(key, scan)) { onClose(); return true; }
        return super.keyReleased(key, scan, mods);
    }

    private static void send(int action, int art, int value) {
        PacketDistributor.sendToServer(new ArtActionPayload(action, Math.max(0, art), value));
    }
}

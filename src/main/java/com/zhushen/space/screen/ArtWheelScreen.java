package com.zhushen.space.screen;

import com.zhushen.space.client.ClientArtData;
import com.zhushen.space.client.ClientSetup;
import com.zhushen.space.client.ClientWillpower;
import com.zhushen.space.data.ArtSkill;
import com.zhushen.space.network.ArtActionPayload;
import com.zhushen.space.network.WillpowerActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * 动作轮盘（默认 Z，按住显示、松开关闭）：收纳「主动使用但不占技能栏 / 按键」的效果。
 * 新增此类效果只需在 {@link #ENTRIES} 中注册一个 {@link Entry}。
 */
public class ArtWheelScreen extends Screen {

    /**
     * 轮盘条目：label 名称；value 状态文字；visible 是否显示；
     * click(button) 点击（0 左键 / 1 右键）；scroll(dir) 滚轮（可为 null）。
     */
    public record Entry(Supplier<String> label, Supplier<String> value, BooleanSupplier visible,
                        IntConsumer click, IntConsumer scroll) {}

    public static final List<Entry> ENTRIES = new ArrayList<>();

    private static String tr(String k) { return Component.translatable(k).getString(); }

    static {
        // 意志力：强撑（昏迷时）/ 意志加持（下一次检定 +9）
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.will"),
                () -> tr(ClientWillpower.armedCheck() ? "screen.zhushenspace.wheel.armed" : "screen.zhushenspace.wheel.ready"),
                () -> true, b -> PacketDistributor.sendToServer(new WillpowerActionPayload(0)), null));
        // 意志守御（下一次受击 +9 护甲 / 韧性）
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.guard"),
                () -> tr(ClientWillpower.armedGuard() ? "screen.zhushenspace.wheel.armed" : "screen.zhushenspace.wheel.ready"),
                () -> true, b -> PacketDistributor.sendToServer(new WillpowerActionPayload(1)), null));
        // 留手：点击开关，滚轮 ±5%
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.holdback"),
                () -> ClientArtData.holdback() < 0 ? tr("screen.zhushenspace.wheel.off") : ClientArtData.holdback() + "%",
                () -> true, b -> art(3, 0, ClientArtData.holdback() < 0 ? 50 : -1),
                d -> art(3, 0, Math.max(1, Math.min(100, (ClientArtData.holdback() < 0 ? 100 : ClientArtData.holdback()) + d * 5)))));
        // 增幅：关 / 用满
        ENTRIES.add(new Entry(() -> tr("screen.zhushenspace.wheel.amplify"),
                () -> tr(ClientArtData.amplify() ? "screen.zhushenspace.wheel.full" : "screen.zhushenspace.wheel.off"),
                () -> true, b -> art(4, 0, ClientArtData.amplify() ? 0 : 1), null));
        // 八阵图元素（习得后显示）
        ArtSkill ef = ArtSkill.EIGHT_FORMATION;
        ENTRIES.add(new Entry(() -> tr(ef.ability.nameKey()),
                () -> tr(ef.optionKey(Math.min(ef.options.length - 1, ClientArtData.current(ef)))),
                () -> ClientArtData.owns(ef), b -> art(5, ef.ordinal(), b == 1 ? -1 : 1), d -> art(5, ef.ordinal(), d)));
    }

    private static void art(int action, int art, int value) {
        PacketDistributor.sendToServer(new ArtActionPayload(action, art, value));
    }

    private static final int BTN = 26;
    private final long openedAt = System.currentTimeMillis();

    public ArtWheelScreen() { super(Component.translatable("screen.zhushenspace.wheel")); }

    @Override
    public boolean isPauseScreen() { return false; }

    private List<Entry> visible() {
        List<Entry> l = new ArrayList<>();
        for (Entry e : ENTRIES) if (e.visible().getAsBoolean()) l.add(e);
        return l;
    }

    private int radius(int n) { return Math.max(62, (int) (n * (BTN * 2 + 8) / (2 * Math.PI))); }

    private int[] pos(int i, int n) {
        double a = -Math.PI / 2 + i * (Math.PI * 2 / n);
        int r = radius(n);
        return new int[]{width / 2 + (int) (Math.cos(a) * r), height / 2 + (int) (Math.sin(a) * r)};
    }

    private int hovered(double mx, double my, int n) {
        for (int i = 0; i < n; i++) {
            int[] p = pos(i, n);
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
        List<Entry> es = visible();
        int n = es.size();
        float in = Math.min(1f, (System.currentTimeMillis() - openedAt) / 160f);
        int cx = width / 2, cy = height / 2, r = radius(n);
        disc(g, cx, cy, (int) ((r + BTN + 6) * in), 0x88100C20);
        disc(g, cx, cy, (int) (Math.max(10, r - BTN - 4) * in), 0xCC1A1430);
        int hv = hovered(mx, my, n);
        for (int i = 0; i < n; i++) {
            int[] p = pos(i, n);
            int px = cx + (int) ((p[0] - cx) * in), py = cy + (int) ((p[1] - cy) * in);
            disc(g, px, py, BTN, hv == i ? 0xEE6B4EC8 : 0xDD2E2450);
            disc(g, px, py, BTN - 2, hv == i ? 0xEE3A2A78 : 0xDD1A1430);
            g.drawCenteredString(font, es.get(i).label().get(), px, py - 9, 0xFFEDE8F6);
            g.drawCenteredString(font, es.get(i).value().get(), px, py + 3, 0xFFE8C77E);
        }
        g.drawCenteredString(font, Component.translatable("screen.zhushenspace.wheel.hint"), cx, cy - 4, 0xFFBBAEDD);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        List<Entry> es = visible();
        int hv = hovered(mx, my, es.size());
        if (hv < 0) return super.mouseClicked(mx, my, button);
        es.get(hv).click().accept(button);
        ZsTheme.click(1.2f);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        List<Entry> es = visible();
        int hv = hovered(mx, my, es.size());
        if (hv >= 0 && es.get(hv).scroll() != null) {
            es.get(hv).scroll().accept(sy > 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (ClientSetup.ART_WHEEL.matches(key, scan)) { onClose(); return true; }
        return super.keyReleased(key, scan, mods);
    }
}

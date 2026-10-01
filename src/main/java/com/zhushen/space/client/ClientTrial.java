package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.TrialTemplate;
import com.zhushen.space.network.TrialActionPayload;
import com.zhushen.space.network.TrialStatePayload;
import com.zhushen.space.screen.TrialPickScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 新手试炼（客户端）：状态缓存、左上角目标卡片、关卡标题、结算横幅，以及战斗模式 / 选择界面的联动。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientTrial {
    private ClientTrial() {}

    public static final int STAGES = 6;
    private static final int[] GOALS = {1, 1, 3, 3, 2, 1};
    private static final int HITS = 3;

    private static final int ACCENT = 0xFF35D0FF, ACCENT_DIM = 0xFF1B6F8C, OK = 0xFF5CFF9A;
    private static final int BG = 0xD60B1420, BG2 = 0xE0101C2C, TEXT = 0xFFEAF6FF, SUB = 0xFF8FA7B8;

    private static int status, stage, done, count, template = -1, deaths, completions;
    private static long ticks;
    private static int pickNonce = -1, finishNonce = -1;
    private static boolean synced;

    private static long stageAt, finishAt = -1, combatNoteAt;
    private static final long[] goalAt = new long[4];

    public static boolean active() { return status == 1; }

    public static int stage() { return stage; }

    /** 尚未进行过试炼（也未跳过）：使用邀请函后询问是否进入 */
    public static boolean shouldPrompt() { return synced && status == 0; }

    private static long now() { return System.currentTimeMillis(); }

    public static void state(TrialStatePayload p) {
        long t = now();
        if (p.status() == 1 && (status != 1 || p.stage() != stage)) stageAt = t;
        if (p.status() == 1 && p.stage() == stage) {
            for (int i = 0; i < 4; i++) if ((p.done() & (1 << i)) != 0 && (done & (1 << i)) == 0) goalAt[i] = t;
        } else java.util.Arrays.fill(goalAt, 0);
        boolean openPick = synced && p.pickNonce() != pickNonce && p.status() == 1;
        boolean showFinish = synced && p.finishNonce() != finishNonce && p.status() == 2;
        status = p.status();
        stage = p.stage();
        done = p.done();
        count = p.count();
        template = p.template();
        deaths = p.deaths();
        ticks = p.ticks();
        completions = p.completions();
        pickNonce = p.pickNonce();
        finishNonce = p.finishNonce();
        synced = true;
        Minecraft mc = Minecraft.getInstance();
        if (openPick && mc.player != null) mc.setScreen(new TrialPickScreen());
        if (showFinish) {
            finishAt = t;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.2f, 0.6f));
        }
    }

    public static void send(int action, int value) {
        if (Minecraft.getInstance().getConnection() != null) PacketDistributor.sendToServer(new TrialActionPayload(action, value));
    }

    /** 主神面板打开时（GodPanelScreen.init 调用） */
    public static void notePanel() {
        if (active() && stage == 0 && (done & 1) == 0) send(TrialActionPayload.NOTE_PANEL, 0);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        status = 0; stage = 0; done = 0; count = 0; template = -1; synced = false;
        pickNonce = -1; finishNonce = -1; finishAt = -1;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        if (!active() || stage != 2 || (done & 1) != 0) return;
        if (CombatModeClient.combatMode() && now() - combatNoteAt > 1000) {
            combatNoteAt = now();
            send(TrialActionPayload.NOTE_COMBAT, 0);
        }
    }

    // ===================== HUD =====================

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        GuiGraphics g = e.getGuiGraphics();
        Font font = mc.font;
        if (active()) {
            card(g, font, mc);
            stageTitle(g, font, g.guiWidth());
        }
        if (finishAt >= 0) finishBanner(g, font, g.guiWidth(), g.guiHeight());
    }

    private static float ease(float t) {
        t = Mth.clamp(t, 0, 1);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private static int alpha(int c, float a) {
        int al = (int) (((c >>> 24) & 0xFF) * Mth.clamp(a, 0, 1));
        return (al << 24) | (c & 0xFFFFFF);
    }

    private static Object[] keyArgs(Minecraft mc) {
        return new Object[]{
                mc.options.keyInventory.getTranslatedKeyMessage(),
                ClientSetup.TOGGLE_COMBAT.getTranslatedKeyMessage(),
                ClientSetup.ART_WHEEL.getTranslatedKeyMessage(),
                mc.options.keyJump.getTranslatedKeyMessage(),
                mc.options.keyAttack.getTranslatedKeyMessage()};
    }

    /** 左上角目标卡片：关卡进度、目标勾选、操作提示 */
    private static void card(GuiGraphics g, Font font, Minecraft mc) {
        long t = now();
        int w = 178, pad = 7;
        float in = ease((t - stageAt) / 380f);
        int x = (int) (6 - (1 - in) * (w + 10)), y = 6;
        int goals = GOALS[Mth.clamp(stage, 0, STAGES - 1)];
        boolean all = done == (1 << goals) - 1;

        List<FormattedCharSequence> hint = font.split(
                Component.translatable("trial.zhushenspace.hint." + stage, keyArgs(mc)), w - pad * 2);
        int h = 30 + goals * 12 + 4 + hint.size() * 9 + (all && stage < STAGES - 1 ? 13 : 0) + pad;

        // 底板 + 左侧强调条 + 顶部细线
        g.fill(x, y, x + w, y + h, BG);
        g.fillGradient(x, y, x + w, y + 22, BG2, BG);
        g.fill(x, y, x + 2, y + h, ACCENT);
        g.fill(x + 2, y, x + w, y + 1, alpha(ACCENT, 0.55f));
        g.fill(x + 2, y + h - 1, x + w, y + h, alpha(ACCENT, 0.25f));
        // 角标
        g.fill(x + w - 7, y + h - 2, x + w, y + h - 1, ACCENT);
        g.fill(x + w - 2, y + h - 7, x + w - 1, y + h - 1, ACCENT);

        // 标题：主神试炼 · n/6   + 关卡名
        String head = Component.translatable("trial.zhushenspace.hud.title", stage + 1, STAGES).getString();
        g.drawString(font, head, x + pad, y + 4, SUB, false);
        g.drawString(font, Component.translatable("trial.zhushenspace.room." + stage).getString(), x + pad, y + 14, TEXT, true);
        // 进度格
        int segW = 9, segX = x + w - pad - STAGES * (segW + 2) + 2;
        for (int i = 0; i < STAGES; i++) {
            int sx = segX + i * (segW + 2);
            int col = i < stage ? ACCENT : i == stage ? alpha(0xFFFFFFFF, 0.55f + 0.45f * pulse(t, 1200)) : 0x40FFFFFF;
            g.fill(sx, y + 6, sx + segW, y + 8, col);
        }

        // 目标
        int gy = y + 30;
        for (int i = 0; i < goals; i++) {
            boolean ok = (done & (1 << i)) != 0;
            float flash = goalAt[i] > 0 ? 1 - Mth.clamp((t - goalAt[i]) / 700f, 0, 1) : 0;
            if (flash > 0) g.fill(x + 3, gy - 2, x + w - 3, gy + 10, alpha(OK, 0.35f * flash));
            int bx = x + pad, by = gy;
            if (ok) {
                g.fill(bx, by, bx + 8, by + 8, OK);
                // 勾
                g.fill(bx + 1, by + 4, bx + 3, by + 5, 0xFF0B1420);
                g.fill(bx + 2, by + 5, bx + 4, by + 6, 0xFF0B1420);
                g.fill(bx + 3, by + 6, bx + 5, by + 7, 0xFF0B1420);
                g.fill(bx + 4, by + 4, bx + 6, by + 6, 0xFF0B1420);
                g.fill(bx + 5, by + 2, bx + 7, by + 4, 0xFF0B1420);
            } else {
                g.renderOutline(bx, by, 8, 8, alpha(ACCENT, 0.6f + 0.4f * pulse(t, 1400)));
            }
            String label = Component.translatable("trial.zhushenspace.goal." + stage + "." + i).getString();
            if (stage == 2 && i == 1 && !ok) label += "  " + count + "/" + HITS;
            g.drawString(font, label, bx + 13, by, ok ? SUB : TEXT, false);
            if (ok) g.fill(bx + 13, by + 4, bx + 13 + font.width(label), by + 5, alpha(SUB, 0.7f));
            gy += 12;
        }

        // 提示
        gy += 3;
        g.fill(x + pad, gy - 3, x + w - pad, gy - 2, 0x30FFFFFF);
        for (FormattedCharSequence line : hint) {
            g.drawString(font, line, x + pad, gy, SUB, false);
            gy += 9;
        }
        if (all && stage < STAGES - 1) {
            gy += 3;
            float p = pulse(t, 900);
            g.drawString(font, "▶ " + Component.translatable("trial.zhushenspace.hud.door").getString(),
                    x + pad, gy, alpha(ACCENT, 0.6f + 0.4f * p), false);
        }
    }

    private static float pulse(long t, long period) {
        return 0.5f + 0.5f * (float) Math.sin(t % period / (double) period * Math.PI * 2);
    }

    /** 进入新关卡时屏幕上方的大标题（约 2.6 秒） */
    private static void stageTitle(GuiGraphics g, Font font, int sw) {
        long e = now() - stageAt;
        if (e > 2600 || stageAt == 0) return;
        float a = e < 250 ? e / 250f : e > 2000 ? 1 - (e - 2000) / 600f : 1;
        float slide = ease(e / 400f);
        String top = Component.translatable("trial.zhushenspace.hud.stage", stage + 1).getString();
        String name = Component.translatable("trial.zhushenspace.room." + stage).getString();
        int cy = 46;
        int lw = (int) (140 * slide);
        g.fill(sw / 2 - lw, cy - 2, sw / 2 + lw, cy - 1, alpha(ACCENT, a));
        g.fill(sw / 2 - lw, cy + 27, sw / 2 + lw, cy + 28, alpha(ACCENT, a * 0.6f));
        g.drawCenteredString(font, top, sw / 2, cy + 2, alpha(0xFF8FD8FF, a));
        g.pose().pushPose();
        g.pose().translate(sw / 2f, cy + 12, 0);
        g.pose().scale(1.6f, 1.6f, 1);
        g.drawCenteredString(font, name, 0, 0, alpha(0xFFFFFFFF, a));
        g.pose().popPose();
    }

    /** 结算横幅（约 7 秒） */
    private static void finishBanner(GuiGraphics g, Font font, int sw, int sh) {
        long e = now() - finishAt;
        if (e > 7000) { finishAt = -1; return; }
        float a = e < 300 ? e / 300f : e > 6000 ? 1 - (e - 6000) / 1000f : 1;
        float open = ease(e / 500f);
        int cy = (int) (sh * 0.24f);
        int bw = (int) (sw * 0.36f * open);
        g.fillGradient(sw / 2 - bw, cy - 8, sw / 2 + bw, cy + 52, alpha(0xC0081420, a), alpha(0x60081420, a));
        g.fill(sw / 2 - bw, cy - 8, sw / 2 + bw, cy - 7, alpha(ACCENT, a));
        g.fill(sw / 2 - bw, cy + 51, sw / 2 + bw, cy + 52, alpha(ACCENT, a * 0.6f));
        g.pose().pushPose();
        g.pose().translate(sw / 2f, cy, 0);
        g.pose().scale(2.2f, 2.2f, 1);
        g.drawCenteredString(font, Component.translatable("trial.zhushenspace.hud.clear").getString(), 0, 0, alpha(0xFFFFFFFF, a));
        g.pose().popPose();
        String s = Component.translatable("trial.zhushenspace.hud.result", fmt(ticks), deaths).getString();
        g.drawCenteredString(font, s, sw / 2, cy + 24, alpha(0xFF9FE6FF, a));
        g.drawCenteredString(font, Component.translatable("trial.zhushenspace.hud.next").getString(), sw / 2, cy + 37, alpha(SUB | 0xFF000000, a));
    }

    private static String fmt(long ticks) {
        long s = ticks / 20;
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** 选择界面用：当前已选角色卡（-1 = 未选） */
    public static int template() { return template; }

    public static TrialTemplate templateOrNull() {
        return template >= 0 && template < TrialTemplate.VALUES.length ? TrialTemplate.VALUES[template] : null;
    }
}

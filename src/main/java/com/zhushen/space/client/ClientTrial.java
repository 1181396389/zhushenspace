package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.TrialTemplate;
import com.zhushen.space.network.TrialActionPayload;
import com.zhushen.space.network.TrialStatePayload;
import com.zhushen.space.screen.TrialPickScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
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
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 新手试炼（客户端）：状态缓存、左上角目标卡片、关卡标题、主神旁白字幕（打字机效果，空格跳过）、
 * 结算面板（用时 / 倒下 / 受伤 / 技能使用 / 击败），以及战斗模式 / 选择界面的联动。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientTrial {
    private ClientTrial() {}

    public static final int STAGES = 10;
    private static final int[] GOALS = {1, 1, 3, 3, 2, 3, 2, 2, 2, 1};
    /** 计数型目标：{关卡, 目标序号, 需要的次数} */
    private static final int[][] COUNTED = {{3, 1, 3}, {4, 1, 3}, {7, 0, 3}};

    private static final int ACCENT = 0xFF35D0FF, OK = 0xFF5CFF9A, GOLD = 0xFFFFD36A;
    private static final int BG = 0xD60B1420, BG2 = 0xE0101C2C, TEXT = 0xFFEAF6FF, SUB = 0xFF8FA7B8;

    private static int status, stage, done, count, template = -1, deaths, completions, hits, skills, kills;
    private static long ticks, best;
    private static boolean newBest;
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
        boolean newStage = p.status() == 1 && (status != 1 || p.stage() != stage);
        if (newStage) stageAt = t;
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
        hits = p.hits();
        skills = p.skills();
        kills = p.kills();
        best = p.best();
        newBest = p.newBest();
        pickNonce = p.pickNonce();
        finishNonce = p.finishNonce();
        synced = true;
        if (newStage) Narration.play(stage);
        if (status != 1) Narration.stop();
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
        Narration.stop();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        Narration.tick();
        if (!active() || stage != 3 || (done & 1) != 0) return;
        if (CombatModeClient.combatMode() && now() - combatNoteAt > 1000) {
            combatNoteAt = now();
            send(TrialActionPayload.NOTE_COMBAT, 0);
        }
    }

    /** 旁白显示中按跳跃键：补完 / 下一句（不会触发跳跃） */
    @SubscribeEvent
    public static void onKey(InputEvent.Key e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || e.getAction() != GLFW.GLFW_PRESS) return;
        if (!Narration.visible()) return;
        if (mc.options.keyJump.matches(e.getKey(), e.getScanCode())) Narration.skip();
    }

    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent e) {
        if (Narration.swallowJump()) e.getInput().jumping = false;
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
            Narration.render(g, font, g.guiWidth(), g.guiHeight(), mc);
        }
        if (finishAt >= 0) finishPanel(g, font, g.guiWidth(), g.guiHeight());
    }

    private static float ease(float t) {
        t = Mth.clamp(t, 0, 1);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private static int alpha(int c, float a) {
        int al = (int) (((c >>> 24) & 0xFF) * Mth.clamp(a, 0, 1));
        return (al << 24) | (c & 0xFFFFFF);
    }

    /**
     * 提示文本里的按键（按实际键位显示）：%1$s 背包 %2$s 战斗模式 %3$s 动作轮盘 %4$s 跳跃 %5$s 攻击
     * %6$s 意志守御 %7$s 潜行 %8$s 使用
     */
    static Object[] keyArgs(Minecraft mc) {
        return new Object[]{
                mc.options.keyInventory.getTranslatedKeyMessage(),
                ClientSetup.TOGGLE_COMBAT.getTranslatedKeyMessage(),
                ClientSetup.ART_WHEEL.getTranslatedKeyMessage(),
                mc.options.keyJump.getTranslatedKeyMessage(),
                mc.options.keyAttack.getTranslatedKeyMessage(),
                ClientSetup.WILLPOWER_GUARD.getTranslatedKeyMessage(),
                mc.options.keyShift.getTranslatedKeyMessage(),
                mc.options.keyUse.getTranslatedKeyMessage()};
    }

    private static int countTarget(int st, int goal) {
        for (int[] c : COUNTED) if (c[0] == st && c[1] == goal) return c[2];
        return 0;
    }

    /** 左上角目标卡片：关卡进度、目标勾选、操作提示 */
    private static void card(GuiGraphics g, Font font, Minecraft mc) {
        long t = now();
        int w = 182, pad = 7;
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

        // 标题：主神试炼 · n/10   + 关卡名
        String head = Component.translatable("trial.zhushenspace.hud.title", stage + 1, STAGES).getString();
        g.drawString(font, head, x + pad, y + 4, SUB, false);
        g.drawString(font, Component.translatable("trial.zhushenspace.room." + stage).getString(), x + pad, y + 14, TEXT, true);
        // 进度格（10 关）
        int segW = 6, segX = x + w - pad - STAGES * (segW + 2) + 2;
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
            int target = countTarget(stage, i);
            if (target > 0 && !ok) label += "  " + Math.min(count, target) + "/" + target;
            label = font.plainSubstrByWidth(label, w - pad - 13 - 4);
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

    /** 结算面板（约 10 秒）：标题 → 用时（新纪录标记）→ 四项统计依次弹出 → 下一步提示 */
    private static void finishPanel(GuiGraphics g, Font font, int sw, int sh) {
        long e = now() - finishAt;
        if (e > 10000) { finishAt = -1; return; }
        float a = e < 300 ? e / 300f : e > 9000 ? 1 - (e - 9000) / 1000f : 1;
        float open = ease(e / 500f);
        int pw = Math.min(300, sw - 40), ph = 128;
        int cx = sw / 2, top = (int) (sh * 0.16f);
        int half = (int) (pw / 2f * open);
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fillGradient(cx - half, top, cx + half, top + ph, alpha(0xE0081420, a), alpha(0xB0081420, a));
        g.fill(cx - half, top, cx + half, top + 1, alpha(ACCENT, a));
        g.fill(cx - half, top + ph - 1, cx + half, top + ph, alpha(ACCENT, a * 0.6f));
        g.fill(cx - half, top, cx - half + 2, top + ph, alpha(ACCENT, a * 0.8f));
        g.fill(cx + half - 2, top, cx + half, top + ph, alpha(ACCENT, a * 0.8f));
        if (open < 0.95f) { g.pose().popPose(); return; }

        // 标题
        g.pose().pushPose();
        g.pose().translate(cx, top + 9, 0);
        g.pose().scale(2.0f, 2.0f, 1);
        g.drawCenteredString(font, Component.translatable("trial.zhushenspace.hud.clear").getString(), 0, 0, alpha(0xFFFFFFFF, a));
        g.pose().popPose();
        // 用时 + 新纪录
        float tA = a * ease((e - 450) / 300f);
        String time = Component.translatable("trial.zhushenspace.result.time", fmt(ticks)).getString();
        g.drawCenteredString(font, time, cx, top + 32, alpha(0xFF9FE6FF, tA));
        if (newBest) {
            float p = pulse(now(), 700);
            String nb = Component.translatable("trial.zhushenspace.result.best").getString();
            g.drawString(font, nb, cx + font.width(time) / 2 + 6, top + 32, alpha(GOLD, tA * (0.6f + 0.4f * p)), true);
        } else if (best > 0) {
            String b = Component.translatable("trial.zhushenspace.result.record", fmt(best)).getString();
            g.drawCenteredString(font, b, cx, top + 43, alpha(SUB | 0xFF000000, tA));
        }
        // 四项统计：倒下 / 受伤 / 技能 / 击败
        int[] vals = {deaths, hits, skills, kills};
        String[] keys = {"deaths", "hits", "skills", "kills"};
        int tileW = (pw - 24) / 4, ty = top + 56;
        for (int i = 0; i < 4; i++) {
            float ta = a * ease((e - 700 - i * 160) / 260f);
            if (ta <= 0.01f) continue;
            int tx = cx - pw / 2 + 12 + i * tileW;
            float pop = 1 + 0.25f * (1 - ease((e - 700 - i * 160) / 220f));
            g.fill(tx + 2, ty, tx + tileW - 2, ty + 36, alpha(0x40FFFFFF, ta * 0.5f));
            g.fill(tx + 2, ty, tx + tileW - 2, ty + 1, alpha(ACCENT, ta * 0.7f));
            g.pose().pushPose();
            g.pose().translate(tx + tileW / 2f, ty + 6, 0);
            g.pose().scale(1.6f * pop, 1.6f * pop, 1);
            g.drawCenteredString(font, String.valueOf(vals[i]), 0, 0, alpha(i == 0 && vals[i] == 0 ? OK : 0xFFFFFFFF, ta));
            g.pose().popPose();
            g.drawCenteredString(font, Component.translatable("trial.zhushenspace.result." + keys[i]).getString(),
                    tx + tileW / 2, ty + 24, alpha(SUB | 0xFF000000, ta));
        }
        float nA = a * ease((e - 1500) / 400f);
        g.drawCenteredString(font, Component.translatable("trial.zhushenspace.hud.next").getString(), cx, top + ph - 14,
                alpha(ACCENT, nA * (0.7f + 0.3f * pulse(now(), 1400))));
        g.pose().popPose();
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

    // ===================== 主神旁白 =====================

    /**
     * 主神旁白字幕：进入每关时播放该关的台词（lang：trial.zhushenspace.narr.N，换行分句）。
     * 打字机效果逐字显示；按跳跃键先补完当前句，再按进入下一句；整句显示完后自动停留片刻再继续。
     */
    private static final class Narration {
        private static final float CPS = 26f;
        private static final List<String> lines = new ArrayList<>();
        private static int idx = -1;
        private static long lineAt, fullAt = -1, skipAt, endAt = -1;
        private static int lastTyped;

        static void play(int st) {
            lines.clear();
            String key = "trial.zhushenspace.narr." + st;
            if (!I18n.exists(key)) { idx = -1; return; }
            for (String s : I18n.get(key).split("\n")) if (!s.isBlank()) lines.add(s.trim());
            idx = lines.isEmpty() ? -1 : 0;
            lineAt = now() + 500; // 关卡大标题出现后稍等再开口
            fullAt = -1;
            endAt = -1;
            lastTyped = 0;
        }

        static void stop() { lines.clear(); idx = -1; endAt = -1; }

        static boolean visible() { return idx >= 0 && idx < lines.size() && now() >= lineAt; }

        static boolean swallowJump() { return now() - skipAt < 350; }

        private static int typed(long t) {
            if (idx < 0 || idx >= lines.size()) return 0;
            if (fullAt >= 0) return lines.get(idx).length();
            return Mth.clamp((int) ((t - lineAt) * CPS / 1000f), 0, lines.get(idx).length());
        }

        static void skip() {
            skipAt = now();
            if (idx < 0 || idx >= lines.size()) return;
            if (fullAt < 0) fullAt = now();
            else next();
        }

        private static void next() {
            idx++;
            lineAt = now();
            fullAt = -1;
            lastTyped = 0;
            if (idx >= lines.size()) { endAt = now(); idx = -1; }
        }

        static void tick() {
            if (idx < 0 || idx >= lines.size()) return;
            long t = now();
            if (t < lineAt) return;
            String s = lines.get(idx);
            int n = typed(t);
            if (n >= s.length() && fullAt < 0) fullAt = t;
            // 轻微的打字声（每两个字一下）
            if (n / 2 > lastTyped / 2 && n < s.length()) {
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.9f, 0.12f));
            }
            lastTyped = n;
            // 读完停留：基础 1.8 秒 + 每字 45 毫秒
            if (fullAt >= 0 && t - fullAt > 1800 + s.length() * 45L) next();
        }

        static void render(GuiGraphics g, Font font, int sw, int sh, Minecraft mc) {
            long t = now();
            boolean show = visible();
            float fade = show ? 1 : endAt >= 0 ? 1 - Mth.clamp((t - endAt) / 400f, 0, 1) : 0;
            if (fade <= 0) return;
            if (!show) return; // 结束后不再绘制文本（淡出交给下一句之间的空隙）
            String s = lines.get(idx);
            String shown = s.substring(0, typed(t));
            int bw = Math.min(340, sw - 40), x = (sw - bw) / 2;
            List<FormattedCharSequence> wrapped = font.split(Component.literal(shown), bw - 20);
            List<FormattedCharSequence> full = font.split(Component.literal(s), bw - 20);
            int rows = Math.max(1, full.size());
            int bh = 18 + rows * 10 + 6;
            int y = sh - 78 - bh;
            float in = ease((t - lineAt) / 220f);
            g.pose().pushPose();
            g.pose().translate(0, (1 - in) * 6, 300);
            g.fillGradient(x, y, x + bw, y + bh, alpha(0xD0081420, fade), alpha(0xA0081420, fade));
            g.fill(x, y, x + 2, y + bh, alpha(ACCENT, fade));
            g.fill(x + 2, y, x + bw, y + 1, alpha(ACCENT, 0.5f * fade));
            // 说话人
            String who = Component.translatable("trial.zhushenspace.narr.speaker").getString();
            g.drawString(font, who, x + 9, y + 5, alpha(ACCENT, fade), false);
            int ww = font.width(who);
            g.fill(x + 9 + ww + 4, y + 9, x + 9 + ww + 30, y + 10, alpha(ACCENT, 0.4f * fade));
            // 台词
            int ty = y + 17;
            for (FormattedCharSequence line : wrapped) {
                g.drawString(font, line, x + 10, ty, alpha(TEXT, fade), true);
                ty += 10;
            }
            // 光标 / 继续提示
            boolean complete = shown.length() >= s.length();
            if (!complete && (t / 300) % 2 == 0 && !wrapped.isEmpty()) {
                int lx = x + 10 + font.width(wrapped.get(wrapped.size() - 1));
                int ly = y + 17 + (wrapped.size() - 1) * 10;
                g.fill(lx + 1, ly, lx + 2, ly + 8, alpha(ACCENT, fade));
            }
            String tip = Component.translatable(complete ? "trial.zhushenspace.narr.next" : "trial.zhushenspace.narr.skip",
                    mc.options.keyJump.getTranslatedKeyMessage()).getString()
                    + "  " + (idx + 1) + "/" + lines.size();
            g.drawString(font, tip, x + bw - 6 - font.width(tip), y + 5, alpha(SUB | 0xFF000000, fade * (complete ? 0.7f + 0.3f * pulse(t, 900) : 0.6f)), false);
            g.pose().popPose();
        }
    }
}

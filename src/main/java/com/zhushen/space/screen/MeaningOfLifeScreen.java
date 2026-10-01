package com.zhushen.space.screen;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.zhushen.space.sound.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/**
 * 邀请信封界面：
 * 黑屏 + 白色线框向中心收缩（类 SAO 开机）→ 中心白点 → 显像管开机（横线 → 满屏）→
 * 复古电脑屏幕覆盖整个屏幕，打字机输出问句 →
 * 点 NO：故障闪烁后 NO 突然变成 YES；点 YES：以点击处为中心整屏碎裂、碎片坠落 → 主神面板。
 */
public class MeaningOfLifeScreen extends Screen {

    private static final String FULL_TEXT = "你想要明白生命的意义吗……你想要真正的活着吗？";
    private static final long CHAR_INTERVAL_MS = 55;

    private static final long RINGS_MS = 1000, DOT_MS = 170, CRT_ON_MS = 420;
    private static final long BOOT_MS = RINGS_MS + DOT_MS + CRT_ON_MS;
    private static final long CRACK_MS = 260, FALL_MS = 1500;

    private static final int PH = 0xFF3CFF78, PH_DIM = 0xFF12602C, PH_BG0 = 0xFF04140A, PH_BG1 = 0xFF010603;

    private long start = -1;
    private boolean crtSound;
    private int chars = 0;
    private long lastChar = 0;

    private boolean noGlitch = false, noIsYes = false;
    private long noGlitchAt;

    private long shatterAt = -1;
    private float impactX, impactY;
    private RenderTarget snapshot;
    private boolean captured;
    private final List<Shard> shards = new ArrayList<>();

    private record Shard(float[] xs, float[] ys, float cx, float cy, float vx, float vy, float spin, float delay) {}

    public MeaningOfLifeScreen() {
        super(Component.translatable("screen.zhushenspace.meaning_of_life.title"));
    }

    @Override
    protected void init() {
        super.init();
        if (start < 0) {
            start = now();
            ui(SoundEvents.BEACON_DEACTIVATE, 1.8f, 0.5f);
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void ui(net.minecraft.sounds.SoundEvent e, float pitch, float vol) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(e, pitch, vol));
    }

    private static void ui(net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> e, float pitch, float vol) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(e.value(), pitch, vol));
    }

    private boolean ready() {
        return chars >= FULL_TEXT.length() && shatterAt < 0;
    }

    // ===================== 布局 =====================

    private int btnW() { return 96; }
    private int btnH() { return 24; }
    private int btnY() { return height / 2 + 40; }
    private int yesX() { return width / 2 - btnW() - 16; }
    private int noX() { return width / 2 + 16; }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ===================== 逻辑 =====================

    @Override
    public void tick() {
        super.tick();
        long t = now() - start;
        if (shatterAt >= 0) {
            if (now() - shatterAt >= CRACK_MS + FALL_MS) {
                // 第一次接入：询问是否进入新手试炼；否则直接打开主神面板
                Minecraft.getInstance().setScreen(com.zhushen.space.client.ClientTrial.shouldPrompt()
                        ? new TrialPromptScreen() : new GodPanelScreen());
            }
            return;
        }
        if (t < RINGS_MS + DOT_MS) return;
        if (!crtSound) { crtSound = true; ui(SoundEvents.BEACON_POWER_SELECT, 2.0f, 0.35f); }
        if (t < BOOT_MS + 200) return;
        if (chars < FULL_TEXT.length() && now() - lastChar >= CHAR_INTERVAL_MS) {
            chars++;
            lastChar = now();
            if (chars % 2 == 0) ui(SoundEvents.NOTE_BLOCK_HAT, 1.8f, 0.12f);
        }
        if (noGlitch && !noIsYes && now() - noGlitchAt > 480) {
            noIsYes = true;
            ui(SoundEvents.NOTE_BLOCK_BIT, 0.6f, 0.5f);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !ready()) return true;
        if (over(mx, my, yesX(), btnY(), btnW(), btnH())) {
            startShatter(yesX() + btnW() / 2f, btnY() + btnH() / 2f);
            return true;
        }
        if (over(mx, my, noX(), btnY(), btnW(), btnH())) {
            if (noIsYes) {
                startShatter(noX() + btnW() / 2f, btnY() + btnH() / 2f);
            } else if (!noGlitch) {
                noGlitch = true;
                noGlitchAt = now();
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) ui(ModSounds.GLITCH.get(), 0.7f, 1f);
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER && ready()) {
            startShatter(yesX() + btnW() / 2f, btnY() + btnH() / 2f);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void startShatter(float x, float y) {
        if (shatterAt >= 0) return;
        shatterAt = now();
        impactX = x;
        impactY = y;
        captured = false;
        buildShards();
        ui(SoundEvents.GLASS_BREAK, 0.8f, 1f);
        ui(SoundEvents.GLASS_BREAK, 0.55f, 0.8f);
    }

    /** 以撞击点为中心的放射 + 同心裂纹：射线 × 环 构成四边形碎片 */
    private void buildShards() {
        shards.clear();
        int rays = 18;
        float maxR = (float) Math.hypot(width, height) + 40;
        float[] rings = {0, 18, 46, 90, 150, 230, 330, 460, maxR};
        int R = rings.length;
        float[][] px = new float[R][rays], py = new float[R][rays];
        float[] ang = new float[rays];
        for (int i = 0; i < rays; i++) ang[i] = (float) ((i + (Math.random() - 0.5) * 0.6) * Math.PI * 2 / rays);
        for (int j = 0; j < R; j++) {
            for (int i = 0; i < rays; i++) {
                float r = j == 0 ? 0 : rings[j] * (float) (0.82 + Math.random() * 0.36);
                double a = ang[i] + (j == 0 ? 0 : (Math.random() - 0.5) * 0.12);
                px[j][i] = impactX + (float) Math.cos(a) * r;
                py[j][i] = impactY + (float) Math.sin(a) * r;
            }
        }
        for (int j = 0; j < R - 1; j++) {
            for (int i = 0; i < rays; i++) {
                int i2 = (i + 1) % rays;
                float[] xs = {px[j][i], px[j][i2], px[j + 1][i2], px[j + 1][i]};
                float[] ys = {py[j][i], py[j][i2], py[j + 1][i2], py[j + 1][i]};
                float cx = (xs[0] + xs[1] + xs[2] + xs[3]) / 4, cy = (ys[0] + ys[1] + ys[2] + ys[3]) / 4;
                float dx = cx - impactX, dy = cy - impactY;
                float d = Math.max(1, (float) Math.sqrt(dx * dx + dy * dy));
                float sp = (0.10f + (float) Math.random() * 0.14f) * (1.4f - Math.min(1f, d / 500f));
                shards.add(new Shard(xs, ys, cx, cy, dx / d * sp, dy / d * sp - 0.05f,
                        (float) ((Math.random() - 0.5) * 0.006), d * 0.35f + (float) Math.random() * 80));
            }
        }
    }

    // ===================== 绘制 =====================

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long t = now() - start;
        if (shatterAt >= 0 && captured) {
            renderShatter(g);
            return;
        }
        g.fill(0, 0, width, height, 0xFF000000);
        if (t < RINGS_MS) {
            renderRings(g, t);
        } else if (t < RINGS_MS + DOT_MS) {
            float k = (t - RINGS_MS) / (float) DOT_MS;
            float r = k < 0.4f ? 2 + k * 10 : 6 * (1 - (k - 0.4f) / 0.6f) + 1;
            JjkStyle.disk(g, width / 2f, height / 2f, r * 2, 1, JjkStyle.alpha(0xFFFFFFFF, 0.25f));
            JjkStyle.disk(g, width / 2f, height / 2f, r, 1, 0xFFFFFFFF);
        } else {
            float k = (t - RINGS_MS - DOT_MS) / (float) CRT_ON_MS;
            if (k < 1f) {
                // 显像管开机：横线展开 → 纵向撑满
                float hk = ZsAnim.easeOutCubic(Math.min(1f, k / 0.35f));
                float vk = ZsAnim.easeOutCubic(ZsAnim.clamp01((k - 0.35f) / 0.65f));
                int hw = Math.round(width / 2f * hk), hh = Math.max(1, Math.round(height / 2f * vk));
                g.enableScissor(width / 2 - hw, height / 2 - hh, width / 2 + hw, height / 2 + hh);
                renderCrt(g, mouseX, mouseY);
                g.disableScissor();
                g.fill(width / 2 - hw, height / 2 - hh, width / 2 + hw, height / 2 + hh,
                        JjkStyle.alpha(0xFFE8FFE8, 1 - vk * 0.95f));
            } else {
                renderCrt(g, mouseX, mouseY);
            }
        }
        if (shatterAt >= 0 && !captured) {
            g.flush();
            capture();
        }
    }

    /** 黑屏上白色线框自四周向中心收缩（多层错开）+ 汇向中心的光线 */
    private void renderRings(GuiGraphics g, long t) {
        float cx = width / 2f, cy = height / 2f;
        for (int i = 0; i < 12; i++) {
            float st = i * 50f;
            float k = ZsAnim.clamp01((t - st) / 520f);
            if (k <= 0 || k >= 1) continue;
            float e = k * k * (3 - 2 * k);
            int hw = Math.round(cx * (1 - e) * 1.05f), hh = Math.round(cy * (1 - e) * 1.05f);
            int col = JjkStyle.alpha(0xFFFFFFFF, Math.min(1f, k * 4) * (0.35f + 0.65f * e));
            int x0 = Math.round(cx) - hw, y0 = Math.round(cy) - hh, x1 = Math.round(cx) + hw, y1 = Math.round(cy) + hh;
            g.fill(x0, y0, x1, y0 + 1, col);
            g.fill(x0, y1 - 1, x1, y1, col);
            g.fill(x0, y0, x0 + 1, y1, col);
            g.fill(x1 - 1, y0, x1, y1, col);
        }
        for (int i = 0; i < 40; i++) {
            float st = JjkStyle.hash(i, 1) * 600;
            float k = ZsAnim.clamp01((t - st) / 380f);
            if (k <= 0 || k >= 1) continue;
            double a = JjkStyle.hash(i, 2) * Math.PI * 2;
            float R = (float) Math.hypot(cx, cy);
            float r0 = R * (1 - k), r1 = Math.max(0, r0 - 30 - JjkStyle.hash(i, 3) * 60);
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            JjkStyle.line(g, cx + c * r0, cy + s * r0, cx + c * r1, cy + s * r1, 1,
                    JjkStyle.alpha(0xFFFFFFFF, 0.3f + 0.7f * k));
        }
        // 最后收成一条横线
        float k = ZsAnim.clamp01((t - 780) / 220f);
        if (k > 0) {
            int hw = Math.round(cx * (1 - k * k));
            g.fill(Math.round(cx) - hw, Math.round(cy), Math.round(cx) + hw, Math.round(cy) + 1, 0xFFFFFFFF);
        }
    }

    /** 复古电脑屏幕：荧光绿、扫描线、滚动亮带、暗角、圆角显像管边缘 */
    private void renderCrt(GuiGraphics g, int mouseX, int mouseY) {
        long n = now();
        var font = Minecraft.getInstance().font;
        boolean glitch = noGlitch && !noIsYes;
        float flick = 0.94f + 0.06f * (float) Math.sin(n / 37.0) * (float) Math.sin(n / 91.0);
        g.fillGradient(0, 0, width, height, PH_BG0, PH_BG1);
        // 荧光余晖噪点
        for (int i = 0; i < 90; i++) {
            int x = (int) (JjkStyle.hash(i, n / 80) * width), y = (int) (JjkStyle.hash(i + 99, n / 80) * height);
            g.fill(x, y, x + 1, y + 1, 0x1A3CFF78);
        }

        int shake = glitch ? (int) ((Math.random() - 0.5) * 6) : 0;
        g.pose().pushPose();
        g.pose().translate(shake, 0, 0);

        // 顶部状态行
        String head = "ZHUSHEN-OS  BIOS v0.99   MEM 640K OK   " + (n / 500 % 2 == 0 ? "■" : " ");
        g.drawString(font, head, 18, 16, JjkStyle.alpha(PH, 0.7f * flick), false);
        g.fill(18, 28, width - 18, 29, JjkStyle.alpha(PH_DIM, 0.9f));
        g.drawString(font, "C:\\>INVITE.EXE", 18, 36, JjkStyle.alpha(PH, 0.6f * flick), false);

        // 问句（2 倍字号，居中换行）+ 光标
        String shown = FULL_TEXT.substring(0, chars);
        float sc = width > 420 ? 2f : 1.5f;
        int maxW = (int) ((width - 80) / sc);
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char c : shown.toCharArray()) {
            if (font.width(cur.toString() + c) > maxW && cur.length() > 0) { lines.add(cur.toString()); cur = new StringBuilder(); }
            cur.append(c);
        }
        lines.add(cur.toString());
        int fullW = Math.min(maxW, font.width(FULL_TEXT));
        float tx = width / 2f - fullW * sc / 2f;
        float ty = height / 2f - 30 - lines.size() * 11 * sc / 2f;
        for (int li = 0; li < lines.size(); li++) {
            String l = lines.get(li);
            g.pose().pushPose();
            g.pose().translate(tx, ty + li * 11 * sc, 0);
            g.pose().scale(sc, sc, 1);
            g.drawString(font, l, 1, 0, JjkStyle.alpha(PH, 0.25f), false);       // 荧光晕
            g.drawString(font, l, 0, 1, JjkStyle.alpha(PH, 0.25f), false);
            g.drawString(font, l, 0, 0, JjkStyle.alpha(PH, flick), false);
            if (li == lines.size() - 1 && (n / 400) % 2 == 0) {
                g.drawString(font, "█", font.width(l) + 1, 0, JjkStyle.alpha(PH, flick), false);
            }
            g.pose().popPose();
        }

        // 按钮
        if (chars >= FULL_TEXT.length()) {
            button(g, font, mouseX, mouseY, yesX(), Component.translatable("screen.zhushenspace.yes").getString(), false);
            button(g, font, mouseX, mouseY, noX(), Component.translatable(noIsYes ? "screen.zhushenspace.yes"
                    : "screen.zhushenspace.no").getString(), glitch);
        }
        g.pose().popPose();

        // 故障：错位切片 + 色偏
        if (glitch) {
            for (int i = 0; i < 6; i++) {
                int y = (int) (Math.random() * height), h = 2 + (int) (Math.random() * 10);
                int off = (int) ((Math.random() - 0.5) * 40);
                g.fill(Math.max(0, off), y, width + Math.min(0, off), y + h, 0x553CFF78);
                g.fill(0, y + h, width, y + h + 1, 0x88FF3050);
            }
        }

        // 扫描线 + 滚动亮带
        for (int y = 0; y < height; y += 2) g.fill(0, y, width, y + 1, 0x30000000);
        int band = (int) ((n / 6) % (height + 80)) - 40;
        g.fillGradient(0, band, width, band + 40, 0x003CFF78, 0x103CFF78);
        // 暗角
        int v = Math.min(width, height) / 5;
        g.fillGradient(0, 0, width, v, 0x99000000, 0x00000000);
        g.fillGradient(0, height - v, width, height, 0x00000000, 0x99000000);
        for (int i = 0; i < v; i += 2) {
            int a = (int) (0x99 * (1 - i / (float) v));
            g.fill(i, 0, i + 2, height, a << 24);
            g.fill(width - i - 2, 0, width - i, height, a << 24);
        }
        // 显像管圆角
        int rr = 26;
        for (int y = 0; y < rr; y++) {
            int cut = rr - (int) Math.sqrt(rr * rr - (rr - y) * (rr - y));
            g.fill(0, y, cut, y + 1, 0xFF000000);
            g.fill(width - cut, y, width, y + 1, 0xFF000000);
            g.fill(0, height - 1 - y, cut, height - y, 0xFF000000);
            g.fill(width - cut, height - 1 - y, width, height - y, 0xFF000000);
        }
    }

    private void button(GuiGraphics g, net.minecraft.client.gui.Font font, int mx, int my, int x, String label, boolean glitch) {
        int y = btnY(), w = btnW(), h = btnH();
        boolean hov = ready() && over(mx, my, x, y, w, h);
        if (hov) g.fill(x, y, x + w, y + h, PH);
        g.renderOutline(x, y, w, h, PH);
        g.renderOutline(x + 2, y + 2, w - 4, h - 4, JjkStyle.alpha(PH, 0.35f));
        String s = "[ " + label.toUpperCase() + " ]";
        if (glitch) {
            char[] cs = s.toCharArray();
            for (int i = 0; i < cs.length; i++) if (Math.random() < 0.4) cs[i] = "#%&@$?!/\\"
                    .charAt((int) (Math.random() * 9));
            s = new String(cs);
        }
        int sw = font.width(s);
        g.drawString(font, s, x + (w - sw) / 2, y + (h - 8) / 2, hov ? PH_BG1 : PH, false);
    }

    // ===================== 碎屏 =====================

    /** 把当前帧（复古屏幕）拷贝到离屏纹理，作为碎片贴图 */
    private void capture() {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        int w = main.width, h = main.height;
        if (snapshot == null || snapshot.width != w || snapshot.height != h) {
            if (snapshot != null) snapshot.destroyBuffers();
            snapshot = new TextureTarget(w, h, false, Minecraft.ON_OSX);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, snapshot.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, w, h, 0, 0, w, h, GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);
        main.bindWrite(true);
        captured = true;
    }

    private void renderShatter(GuiGraphics g) {
        long e = now() - shatterAt;
        g.fill(0, 0, width, height, 0xFF000000);
        g.flush();
        Matrix4f m = g.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, snapshot.getColorTextureId());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        float fall = Math.max(0, e - CRACK_MS);
        for (Shard s : shards) {
            float tt = Math.max(0, fall - s.delay());
            float ox = s.vx() * tt, oy = s.vy() * tt + 0.0009f * tt * tt;
            float rot = s.spin() * tt;
            float sc = 1 - Math.min(0.35f, tt / 4000f);
            float br = 1 - Math.min(0.7f, tt / 1400f);
            float cos = (float) Math.cos(rot), sin = (float) Math.sin(rot);
            float[] X = new float[4], Y = new float[4];
            for (int k = 0; k < 4; k++) {
                float dx = (s.xs()[k] - s.cx()) * sc, dy = (s.ys()[k] - s.cy()) * sc;
                X[k] = s.cx() + ox + dx * cos - dy * sin;
                Y[k] = s.cy() + oy + dx * sin + dy * cos;
            }
            int[][] tris = {{0, 1, 2}, {0, 2, 3}};
            for (int[] tri : tris) {
                for (int k : tri) {
                    float u = s.xs()[k] / width, v = 1 - s.ys()[k] / height;
                    b.addVertex(m, X[k], Y[k], 0).setUv(u, v).setColor(br, br, br, 1f);
                }
            }
        }
        var mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        RenderSystem.disableBlend();

        // 裂纹：自撞击点向外蔓延的白色细线（仅开裂阶段与刚开始坠落时）
        float crack = ZsAnim.clamp01(e / (float) CRACK_MS);
        float fade = 1 - ZsAnim.clamp01((e - CRACK_MS) / 300f);
        if (fade > 0) {
            float reach = crack * (float) Math.hypot(width, height);
            for (Shard s : shards) {
                for (int k = 0; k < 4; k++) {
                    int k2 = (k + 1) % 4;
                    float mx = (s.xs()[k] + s.xs()[k2]) / 2 - impactX, my = (s.ys()[k] + s.ys()[k2]) / 2 - impactY;
                    if (mx * mx + my * my > reach * reach) continue;
                    JjkStyle.line(g, s.xs()[k], s.ys()[k], s.xs()[k2], s.ys()[k2], 1, JjkStyle.alpha(0xFFFFFFFF, 0.8f * fade));
                }
            }
            if (e < 90) g.fill(0, 0, width, height, JjkStyle.alpha(0xFFFFFFFF, 0.5f * (1 - e / 90f)));
        }
    }

    @Override
    public void removed() {
        super.removed();
        if (snapshot != null) {
            snapshot.destroyBuffers();
            snapshot = null;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

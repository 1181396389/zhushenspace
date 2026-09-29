package com.zhushen.space.screen;

import com.zhushen.space.sound.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.lwjgl.glfw.GLFW;

public class MeaningOfLifeScreen extends Screen {

    // 主对话文本
    private static final String FULL_TEXT = "你想要明白生命的意义吗……你想要真正的活着吗？";
    private int displayedCharCount = 0;
    private long lastCharTime = 0;
    private static final long CHAR_INTERVAL_MS = 35;

    // 按钮
    private Button yesButton;
    private Button noButton;

    // 故障效果相关
    private boolean noGlitched = false;
    private long glitchStartTime = 0;
    private static final long GLITCH_DURATION_MS = 1200;
    private static final long TRANSITION_DELAY_MS = 400; // No变Yes后延迟
    private boolean glitchButtonChanged = false;
    private long glitchButtonChangedTime = 0;

    // 粒子效果
    private int blueParticles = 0;

    // 渐入动画
    private float fadeInAlpha = 0.0f;
    private static final float FADE_SPEED = 0.01f;

    // 淡蓝色主题颜色
    private static final int BOX_BG_COLOR = 0xCC120E1C;
    private static final int BOX_BORDER_COLOR = 0xFF8C6FE0;
    private static final int TEXT_COLOR = 0xFFE0D6F6;
    private static final int ACCENT_COLOR = 0xFFCDBEF5;

    // 穿越漩涡：开场（吸入 → 对话浮现）/ 退场（冲出 → 白光 → 主神面板）
    private long introStart = -1, exitStart = -1;
    private static final long INTRO_MS = 1500, EXIT_MS = 1600;

    public MeaningOfLifeScreen() {
        super(Component.translatable("screen.zhushenspace.meaning_of_life.title"));
    }

    @Override
    protected void init() {
        super.init();
        if (introStart < 0) {
            introStart = System.currentTimeMillis();
            Minecraft.getInstance().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.PORTAL_TRIGGER, 1.6f, 0.35f));
        }

        int dialogWidth = Mth.clamp(this.width - 80, 320, 600);
        int dialogHeight = 180;
        int dialogX = (this.width - dialogWidth) / 2;
        int dialogY = (this.height - dialogHeight) / 2;

        int buttonWidth = 100;
        int buttonHeight = 26;
        int buttonY = dialogY + dialogHeight - buttonHeight - 20;
        int yesX = dialogX + dialogWidth / 2 - buttonWidth - 10;
        int noX = dialogX + dialogWidth / 2 + 10;

        yesButton = new ZsButton(yesX, buttonY, buttonWidth, buttonHeight,
                Component.translatable("screen.zhushenspace.yes"),
                this::onYesClick);

        noButton = new ZsButton(noX, buttonY, buttonWidth, buttonHeight,
                Component.translatable("screen.zhushenspace.no"),
                this::onNoClick);

        this.addRenderableWidget(yesButton);
        this.addRenderableWidget(noButton);

        yesButton.active = false;
        noButton.active = false;
    }

    private void onYesClick(Button button) {
        transitionToAllocation();
    }

    private void onNoClick(Button button) {
        if (noGlitched) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.level.playSound(mc.player, mc.player.blockPosition(),
                    ModSounds.GLITCH.get(), SoundSource.PLAYERS, 1.5f, 0.7f);
        }

        noGlitched = true;
        glitchStartTime = System.currentTimeMillis();
        noButton.active = false;
        yesButton.active = false;
        glitchButtonChanged = false;

        blueParticles = 30;
    }

    /** 开始穿越：漩涡反转向外冲出，结束后进入主神面板 */
    private void transitionToAllocation() {
        if (exitStart >= 0) return;
        exitStart = System.currentTimeMillis();
        yesButton.active = false;
        noButton.active = false;
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.PORTAL_TRAVEL, 1.4f, 0.3f));
        if (mc.player != null && mc.level != null) {
            for (int i = 0; i < 60; i++) {
                double a = Math.random() * Math.PI * 2, r = 0.6 + Math.random();
                mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.PORTAL,
                        mc.player.getX() + Math.cos(a) * r, mc.player.getY() + Math.random() * 2, mc.player.getZ() + Math.sin(a) * r,
                        -Math.cos(a) * 0.6, 0.1, -Math.sin(a) * 0.6);
            }
        }
    }

    private void finishTransition() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Minecraft.getInstance().setScreen(new GodPanelScreen());
        }
    }

    /**
     * 穿越漩涡：旋转的同心光环 + 5 条螺旋臂粒子流 + 中心光核。
     * outward=false 为吸入（开场），true 为向外冲出（退场）；strength 控制整体不透明度。
     */
    private void drawVortex(GuiGraphics g, float t, float strength, boolean outward, float zoom) {
        if (strength <= 0.01f) return;
        float cx = width / 2f, cy = height / 2f;
        float maxR = (float) Math.hypot(width, height) * 0.6f * zoom;
        // 同心光环（向内/向外流动，逐层扭转）
        for (int k = 0; k < 16; k++) {
            float u = (k / 16f + t * (outward ? 0.9f : -0.45f)) % 1f;
            if (u < 0) u += 1;
            float r = u * u * maxR;
            int col = lerp(0xFF6A3FD0, 0xFF5FE0FF, u);
            JjkStyle.ring(g, cx, cy, r, r * 0.62f, t * 1.8f + k * 0.45f, 36 + k * 8,
                    JjkStyle.alpha(col, strength * (0.25f + 0.6f * u)));
        }
        // 螺旋臂
        for (int arm = 0; arm < 5; arm++) {
            for (int j = 0; j < 70; j++) {
                float u = (j / 70f + t * (outward ? 0.8f : 0.5f)) % 1f;
                float d = outward ? u : 1 - u;               // 0 = 中心，1 = 外缘
                float r = (float) Math.pow(d, 1.7) * maxR;
                double a = arm * Math.PI * 2 / 5 + (1 - d) * 7 + t * 3.2;
                float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r * 0.62f;
                double a2 = a - (outward ? -0.12 : 0.12);
                float px = cx + (float) Math.cos(a2) * r * 0.97f, py = cy + (float) Math.sin(a2) * r * 0.62f * 0.97f;
                int col = j % 9 == 0 ? 0xFFFFFFFF : arm % 2 == 0 ? 0xFFB28CFF : 0xFF7FE8FF;
                float a0 = strength * (0.3f + 0.7f * d);
                JjkStyle.line(g, px, py, x, y, d > 0.6f ? 2 : 1, JjkStyle.alpha(col, a0));
            }
        }
        // 光核
        float pulse = 1 + 0.15f * (float) Math.sin(t * 6);
        JjkStyle.disk(g, cx, cy, 26 * pulse * zoom, 0.62f, JjkStyle.alpha(0xFF6A3FD0, strength * 0.35f));
        JjkStyle.disk(g, cx, cy, 14 * pulse * zoom, 0.62f, JjkStyle.alpha(0xFFB9A4FF, strength * 0.6f));
        JjkStyle.disk(g, cx, cy, 6 * pulse * zoom, 0.62f, JjkStyle.alpha(0xFFFFFFFF, strength * 0.9f));
    }

    private static int lerp(int a, int b, float k) {
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * k);
        int gg = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * k);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * k);
        return 0xFF000000 | (r << 16) | (gg << 8) | bl;
    }

    @Override
    public void tick() {
        super.tick();

        long currentTime = System.currentTimeMillis();
        if (exitStart >= 0) {
            if (currentTime - exitStart >= EXIT_MS) finishTransition();
            return;
        }
        // 漩涡开场结束前，对话框不出现
        if (currentTime - introStart < INTRO_MS * 2 / 3) return;
        if (fadeInAlpha < 1.0f) {
            fadeInAlpha = Math.min(1.0f, fadeInAlpha + FADE_SPEED * 4);
        }
        if (currentTime - introStart < INTRO_MS) return;

        // 打字机效果
        if (displayedCharCount < FULL_TEXT.length()) {
            if (currentTime - lastCharTime >= CHAR_INTERVAL_MS) {
                displayedCharCount++;
                lastCharTime = currentTime;

                if (displayedCharCount >= FULL_TEXT.length()) {
                    yesButton.active = true;
                    noButton.active = true;
                }
            }
        }

        // 故障效果处理（纯 tick 驱动，无 Thread.sleep）
        if (noGlitched) {
            long elapsed = currentTime - glitchStartTime;

            // 粒子效果
            if (blueParticles > 0) {
                spawnBlueParticles(1);
                blueParticles--;
            }

            // 第一阶段：闪烁干扰
            if (elapsed >= GLITCH_DURATION_MS && !glitchButtonChanged) {
                // 修改按钮文字为 Yes
                noButton.setMessage(Component.translatable("screen.zhushenspace.yes"));
                glitchButtonChanged = true;
                glitchButtonChangedTime = currentTime;
            }

            // 第二阶段：延迟后自动进入加点界面
            if (glitchButtonChanged && (currentTime - glitchButtonChangedTime >= TRANSITION_DELAY_MS)) {
                transitionToAllocation();
            }
        }
    }

    private void spawnBlueParticles(int count) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        for (int i = 0; i < count; i++) {
            double x = mc.player.getX() + (Math.random() - 0.5) * 2.0;
            double y = mc.player.getY() + (Math.random() - 0.5) * 2.0;
            double z = mc.player.getZ() + (Math.random() - 0.5) * 2.0;

            mc.level.addParticle(
                    net.minecraft.core.particles.ParticleTypes.DRAGON_BREATH,
                    x, y, z,
                    (Math.random() - 0.5) * 0.3,
                    Math.random() * 0.3 + 0.1,
                    (Math.random() - 0.5) * 0.3
            );
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        long nowMs = System.currentTimeMillis();
        float vt = (nowMs - introStart) / 1000f;
        if (exitStart >= 0) {
            float k = Math.min(1f, (nowMs - exitStart) / (float) EXIT_MS);
            graphics.fill(0, 0, width, height, ((int) (230 * Math.min(1f, k * 2)) << 24) | 0x05030C);
            drawVortex(graphics, vt, 1f, true, 1 + k * k * 2.5f);
            float white = Math.max(0, (k - 0.65f) / 0.35f);
            if (white > 0) graphics.fill(0, 0, width, height, JjkStyle.alpha(0xFFFFFFFF, white));
            return;
        }
        float intro = Math.min(1f, (nowMs - introStart) / (float) INTRO_MS);
        yesButton.visible = noButton.visible = intro > 0.66f;
        if (intro < 1f) graphics.fill(0, 0, width, height, ((int) (200 * Math.min(1f, intro * 3)) << 24) | 0x05030C);
        // 开场强烈吸入，之后作为对话背后的淡漩涡持续旋转
        drawVortex(graphics, vt, intro < 1f ? 1f - intro * 0.7f : 0.3f, false, intro < 1f ? 1.6f - intro * 0.6f : 1f);

        int dialogWidth = Mth.clamp(this.width - 80, 320, 600);
        int dialogHeight = 180;
        int dialogX = (this.width - dialogWidth) / 2;
        int dialogY = (this.height - dialogHeight) / 2;

        renderDialogBox(graphics, dialogX, dialogY, dialogWidth, dialogHeight);

        String displayedText = FULL_TEXT.substring(0, displayedCharCount);
        renderDialogText(graphics, displayedText, dialogX, dialogY, dialogWidth);

        if (noGlitched) {
            renderGlitchOverlay(graphics, dialogX, dialogY, dialogWidth, dialogHeight);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderDialogBox(GuiGraphics graphics, int x, int y, int width, int height) {
        int alpha = (int) (fadeInAlpha * 255);
        int borderColor = (alpha << 24) | (BOX_BORDER_COLOR & 0x00FFFFFF);

        // 星云动态底纹 + 半透明蒙层 + 中央缓慢旋转的法阵（随淡入出现）
        int tint = (alpha << 24) | 0xFFFFFF;
        ZsAnim.HERTA_SKY.draw(graphics, x, y, width, height, tint);
        graphics.fill(x, y, x + width, y + height, ((int) (alpha * 0.8f) << 24) | (BOX_BG_COLOR & 0x00FFFFFF));
        int sz = height - 20;
        ZsTheme.sigil(graphics, x + width / 2f, y + 10 + sz / 2f, sz, ((int) (alpha * 0.22f) << 24) | 0x8C9CFF);

        graphics.fill(x, y, x + width, y + 4, borderColor);
        graphics.fill(x, y + height - 4, x + width, y + height, borderColor);
        graphics.fill(x, y, x + 4, y + height, borderColor);
        graphics.fill(x + width - 4, y, x + width, y + height, borderColor);

        int cornerSize = 12;
        int accentColor = (alpha << 24) | (ACCENT_COLOR & 0x00FFFFFF);

        graphics.fill(x + 4, y + 4, x + 4 + cornerSize, y + 6, accentColor);
        graphics.fill(x + 4, y + 4, x + 6, y + 4 + cornerSize, accentColor);
        graphics.fill(x + width - 4 - cornerSize, y + height - 6, x + width - 4, y + height - 4, accentColor);
        graphics.fill(x + width - 6, y + height - 4 - cornerSize, x + width - 4, y + height - 4, accentColor);
    }

    private void renderDialogText(GuiGraphics graphics, String text, int dialogX, int dialogY, int dialogWidth) {
        int textColor = (int) (fadeInAlpha * 255) << 24 | (TEXT_COLOR & 0x00FFFFFF);
        int textX = dialogX + 24;
        int textY = dialogY + 40;
        int maxWidth = dialogWidth - 48;

        Minecraft mc = Minecraft.getInstance();
        int lineHeight = mc.font.lineHeight;

        String[] chars = text.split("");
        StringBuilder currentLine = new StringBuilder();
        int currentY = textY;

        for (String c : chars) {
            String testLine = currentLine.toString() + c;
            int width = mc.font.width(testLine);

            if (width > maxWidth && currentLine.length() > 0) {
                graphics.drawString(mc.font, currentLine.toString(), textX, currentY, textColor, false);
                currentLine = new StringBuilder(c);
                currentY += lineHeight + 4;
            } else {
                currentLine.append(c);
            }
        }

        if (!currentLine.isEmpty()) {
            graphics.drawString(mc.font, currentLine.toString(), textX, currentY, textColor, false);
        }

        if (displayedCharCount < FULL_TEXT.length() && System.currentTimeMillis() % 500 < 250) {
            int cursorX = textX + mc.font.width(currentLine.toString()) + 2;
            graphics.drawString(mc.font, "▌", cursorX, currentY, textColor, false);
        }
    }

    private void renderGlitchOverlay(GuiGraphics graphics, int x, int y, int width, int height) {
        if (Math.random() > 0.3) {
            int glitchColor = 0x669B7BEA | ((int) (Math.random() * 100) << 24);
            int glitchX = x + (int) (Math.random() * width);
            int glitchY = y + (int) (Math.random() * height);
            int glitchW = (int) (Math.random() * 30) + 5;
            int glitchH = 2;

            graphics.fill(glitchX, glitchY, glitchX + glitchW, glitchY + glitchH, glitchColor);
        }

        for (int i = 0; i < 3; i++) {
            int lineY = y + (int) (Math.random() * height);
            int lineXOffset = (int) (Math.random() * 20 - 10);
            graphics.fill(x + lineXOffset, lineY, x + width + lineXOffset, lineY + 1, 0x559B7BEA);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int alpha = (int) (fadeInAlpha * 200);
        graphics.fill(0, 0, this.width, this.height, (alpha << 24) | 0x051019);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER && yesButton != null && yesButton.active && !noGlitched) {
            onYesClick(yesButton);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}

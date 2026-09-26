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
    private static final int BOX_BG_COLOR = 0xCC0B1B2A;
    private static final int BOX_BORDER_COLOR = 0xFF5B9BD5;
    private static final int TEXT_COLOR = 0xFFA8D8F0;
    private static final int ACCENT_COLOR = 0xFF3BA9E0;

    public MeaningOfLifeScreen() {
        super(Component.translatable("screen.zhushenspace.meaning_of_life.title"));
    }

    @Override
    protected void init() {
        super.init();

        int dialogWidth = Mth.clamp(this.width - 80, 320, 600);
        int dialogHeight = 180;
        int dialogX = (this.width - dialogWidth) / 2;
        int dialogY = (this.height - dialogHeight) / 2;

        int buttonWidth = 100;
        int buttonHeight = 26;
        int buttonY = dialogY + dialogHeight - buttonHeight - 20;
        int yesX = dialogX + dialogWidth / 2 - buttonWidth - 10;
        int noX = dialogX + dialogWidth / 2 + 10;

        yesButton = Button.builder(
                Component.translatable("screen.zhushenspace.yes"),
                this::onYesClick
        ).bounds(yesX, buttonY, buttonWidth, buttonHeight).build();

        noButton = Button.builder(
                Component.translatable("screen.zhushenspace.no"),
                this::onNoClick
        ).bounds(noX, buttonY, buttonWidth, buttonHeight).build();

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

    private void transitionToAllocation() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new AttributeAllocationScreen());
        }
    }

    @Override
    public void tick() {
        super.tick();

        if (fadeInAlpha < 1.0f) {
            fadeInAlpha = Math.min(1.0f, fadeInAlpha + FADE_SPEED);
        }

        long currentTime = System.currentTimeMillis();

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
        int bgColor = (alpha << 24) | (BOX_BG_COLOR & 0x00FFFFFF);
        int borderColor = (alpha << 24) | (BOX_BORDER_COLOR & 0x00FFFFFF);

        graphics.fill(x, y, x + width, y + height, bgColor);

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
            int glitchColor = 0x663BA9E0 | ((int) (Math.random() * 100) << 24);
            int glitchX = x + (int) (Math.random() * width);
            int glitchY = y + (int) (Math.random() * height);
            int glitchW = (int) (Math.random() * 30) + 5;
            int glitchH = 2;

            graphics.fill(glitchX, glitchY, glitchX + glitchW, glitchY + glitchH, glitchColor);
        }

        for (int i = 0; i < 3; i++) {
            int lineY = y + (int) (Math.random() * height);
            int lineXOffset = (int) (Math.random() * 20 - 10);
            graphics.fill(x + lineXOffset, lineY, x + width + lineXOffset, lineY + 1, 0x553BA9E0);
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

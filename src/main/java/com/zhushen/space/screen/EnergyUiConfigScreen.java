package com.zhushen.space.screen;

import com.zhushen.space.client.ClientEnergyData;
import com.zhushen.space.client.ClientUiConfig;
import com.zhushen.space.client.EnergyHudRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * 主神空间界面设置：
 * - 按住鼠标拖动能量池 HUD 调整位置
 * - 滚轮 / +/- 按钮调整整组能量池的缩放（0.5x ~ 2.0x）
 * - 位置与缩放持久化到客户端配置
 *
 * 无能量池时展示一条示例池，便于预先调整位置。
 */
public class EnergyUiConfigScreen extends Screen {

    private final Screen parent;
    private boolean dragging;
    private float dragOffsetX, dragOffsetY;
    private Button scaleLabel;

    public EnergyUiConfigScreen(Screen parent) {
        super(Component.translatable("screen.zhushenspace.energy_config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        addRenderableWidget(new ZsButton(cx - 130, this.height - 52, 40, 20,
                Component.translatable("screen.zhushenspace.energy_config.scale_down"),
                        b -> adjustScale(-0.05f)));
        scaleLabel = addRenderableWidget(new ZsButton(cx - 86, this.height - 52, 92, 20,
                scaleText(), b -> { }));
        scaleLabel.active = false;
        addRenderableWidget(new ZsButton(cx + 10, this.height - 52, 40, 20,
                Component.translatable("screen.zhushenspace.energy_config.scale_up"),
                        b -> adjustScale(0.05f)));
        addRenderableWidget(new ZsButton(cx - 130, this.height - 28, 130, 20,
                Component.translatable("screen.zhushenspace.energy_config.reset"),
                        b -> {
                            ClientUiConfig.Data cfg = ClientUiConfig.get();
                            cfg.energyX = -1;
                            cfg.energyY = -1;
                            cfg.energyScale = 1.0f;
                            ClientUiConfig.save();
                            scaleLabel.setMessage(scaleText());
                        }));
        addRenderableWidget(new ZsButton(cx + 10, this.height - 28, 130, 20,
                CommonComponents.GUI_DONE, b -> onClose()));
    }

    private Component scaleText() {
        return Component.translatable("screen.zhushenspace.energy_config.scale_value",
                Math.round(ClientUiConfig.get().energyScale * 100));
    }

    private void adjustScale(float delta) {
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        float next = cfg.energyScale + delta;
        next = Math.max(EnergyHudRenderer.MIN_SCALE, Math.min(EnergyHudRenderer.MAX_SCALE, next));
        cfg.energyScale = Math.round(next * 100f) / 100f;
        ClientUiConfig.save();
        scaleLabel.setMessage(scaleText());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // 能量池实时预览（无池时显示示例）
        if (ClientEnergyData.pools().isEmpty()) {
            EnergyHudRenderer.renderDemo(g, font, this.width, this.height);
        } else {
            EnergyHudRenderer.render(g, font, this.width, this.height);
        }

        g.drawCenteredString(font, title, this.width / 2, 14, 0xFF9FD8F8);
        g.drawCenteredString(font,
                Component.translatable("screen.zhushenspace.energy_config.hint").getString(),
                this.width / 2, 30, 0xFF8FC6EE);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 半透明遮罩，保留世界画面便于观察位置效果
        g.fillGradient(0, 0, this.width, this.height, 0x66060D16, 0x66060D16);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            float[] pos = EnergyHudRenderer.layout(this.width, this.height,
                    Math.max(1, ClientEnergyData.pools().size()), ClientUiConfig.get().energyScale);
            if (mouseX >= pos[0] - 4 && mouseX <= pos[0] + pos[2] + 4
                    && mouseY >= pos[1] - 4 && mouseY <= pos[1] + pos[3] + 4) {
                dragging = true;
                dragOffsetX = (float) (mouseX - pos[0]);
                dragOffsetY = (float) (mouseY - pos[1]);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            ClientUiConfig.Data cfg = ClientUiConfig.get();
            cfg.energyX = (float) mouseX - dragOffsetX;
            cfg.energyY = (float) mouseY - dragOffsetY;
            ClientUiConfig.save();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            ClientUiConfig.save();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            adjustScale((float) Math.signum(scrollY) * 0.05f);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        ClientUiConfig.saveNow();
        minecraft.setScreen(parent);
    }
}

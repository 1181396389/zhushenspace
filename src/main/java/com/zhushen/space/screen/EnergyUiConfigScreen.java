package com.zhushen.space.screen;

import com.zhushen.space.client.ClientEnergyData;
import com.zhushen.space.client.ClientUiConfig;
import com.zhushen.space.client.EnergyHudRenderer;
import com.zhushen.space.client.WoundHudRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * 主神空间界面设置：
 * - 按住鼠标拖动能量池 HUD / 战斗模式伤势 HUD 调整位置
 * - 滚轮（悬停在哪个元素上就调整哪个）/ +/- 按钮（调整最近点击过的元素）调整缩放（0.5x ~ 2.0x）
 * - 位置与缩放持久化到客户端配置
 *
 * 无能量池时展示一条示例池；伤势 HUD 无伤势时展示示例数值，便于预先调整位置。
 */
public class EnergyUiConfigScreen extends Screen {

    /** 可调整的 HUD 元素 */
    private enum Target { ENERGY, WOUND }

    private final Screen parent;
    /** 正在拖拽的元素（null = 未拖拽） */
    private Target dragging;
    /** 最近一次点击 / 滚轮作用的元素：+/- 按钮与缩放标签针对它 */
    private Target selected = Target.ENERGY;
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
                            cfg.woundX = -1;
                            cfg.woundY = -1;
                            cfg.woundScale = 1.0f;
                            ClientUiConfig.save();
                            scaleLabel.setMessage(scaleText());
                        }));
        addRenderableWidget(new ZsButton(cx + 10, this.height - 28, 130, 20,
                CommonComponents.GUI_DONE, b -> onClose()));
    }

    private Component scaleText() {
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        float scale = selected == Target.WOUND ? cfg.woundScale : cfg.energyScale;
        String key = selected == Target.WOUND
                ? "screen.zhushenspace.energy_config.scale_value_wound"
                : "screen.zhushenspace.energy_config.scale_value";
        return Component.translatable(key, Math.round(scale * 100));
    }

    private void adjustScale(float delta) {
        adjustScale(selected, delta);
    }

    private void adjustScale(Target target, float delta) {
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        selected = target;
        if (target == Target.WOUND) {
            float next = cfg.woundScale + delta;
            next = Math.max(WoundHudRenderer.MIN_SCALE, Math.min(WoundHudRenderer.MAX_SCALE, next));
            cfg.woundScale = Math.round(next * 100f) / 100f;
        } else {
            float next = cfg.energyScale + delta;
            next = Math.max(EnergyHudRenderer.MIN_SCALE, Math.min(EnergyHudRenderer.MAX_SCALE, next));
            cfg.energyScale = Math.round(next * 100f) / 100f;
        }
        ClientUiConfig.save();
        scaleLabel.setMessage(scaleText());
    }

    private float[] energyLayout() {
        return EnergyHudRenderer.layout(this.width, this.height,
                Math.max(1, ClientEnergyData.pools().size()), ClientUiConfig.get().energyScale);
    }

    private float[] woundLayout() {
        return WoundHudRenderer.layout(this.width, this.height, ClientUiConfig.get().woundScale);
    }

    private static boolean inside(double mx, double my, float[] pos) {
        return mx >= pos[0] - 4 && mx <= pos[0] + pos[2] + 4
                && my >= pos[1] - 4 && my <= pos[1] + pos[3] + 4;
    }

    /** 鼠标所在的 HUD 元素（伤势面板优先，因为它默认在角落且较小） */
    private Target hovered(double mx, double my) {
        if (inside(mx, my, woundLayout())) return Target.WOUND;
        if (inside(mx, my, energyLayout())) return Target.ENERGY;
        return null;
    }

    /** 当前选中元素的高亮外框 */
    private void selectionFrame(GuiGraphics g, float[] pos) {
        int x0 = Math.round(pos[0]) - 3, y0 = Math.round(pos[1]) - 3;
        int x1 = Math.round(pos[0] + pos[2]) + 3, y1 = Math.round(pos[1] + pos[3]) + 3;
        int c = ZsAnim.withAlpha(ZsTheme.ACCENT_LIGHT, 0.5f + 0.5f * ZsAnim.pulse(1200));
        g.fill(x0, y0, x1, y0 + 1, c);
        g.fill(x0, y1 - 1, x1, y1, c);
        g.fill(x0, y0, x0 + 1, y1, c);
        g.fill(x1 - 1, y0, x1, y1, c);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int cx = this.width / 2;
        // 按钮底板（大黑塔卡片）先画，按钮在其上
        int cardX = cx - 138, cardY = this.height - 60, cardW = 286, cardH = 56;
        ZsTheme.card(g, cardX, cardY, cardW, cardH, false);
        ZsTheme.petals(g, cardX, cardY, cardW, cardH, 4);
        ZsTheme.flower(g, cardX, cardY, 10, ZsAnim.nowMs() / 60f % 360, 0.9f);
        super.render(g, mouseX, mouseY, partialTick);
        HertaChibi.render(g, font, cx + 64, cardY, mouseX, mouseY);
        // 能量池实时预览（无池时显示示例）
        if (ClientEnergyData.pools().isEmpty()) {
            EnergyHudRenderer.renderDemo(g, font, this.width, this.height);
        } else {
            EnergyHudRenderer.render(g, font, this.width, this.height);
        }
        // 战斗模式伤势 HUD 预览（无伤势时显示示例数值）
        WoundHudRenderer.renderPreview(g, font, this.width, this.height);
        selectionFrame(g, selected == Target.WOUND ? woundLayout() : energyLayout());

        g.drawCenteredString(font, title, this.width / 2, 14, ZsTheme.TEXT_TITLE);
        ZsTheme.hat(g, this.width / 2f + font.width(title) / 2f + 2, 3, 16);
        g.drawCenteredString(font,
                Component.translatable("screen.zhushenspace.energy_config.hint").getString(),
                this.width / 2, 30, ZsTheme.TEXT_SUB);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 半透明遮罩，保留世界画面便于观察位置效果
        g.fillGradient(0, 0, this.width, this.height, 0x550A0714, 0x881C1232);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && HertaChibi.click(mouseX, mouseY)) return true;
        if (button == 0) {
            Target t = hovered(mouseX, mouseY);
            if (t != null) {
                float[] pos = t == Target.WOUND ? woundLayout() : energyLayout();
                dragging = t;
                selected = t;
                scaleLabel.setMessage(scaleText());
                dragOffsetX = (float) (mouseX - pos[0]);
                dragOffsetY = (float) (mouseY - pos[1]);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging != null) {
            ClientUiConfig.Data cfg = ClientUiConfig.get();
            if (dragging == Target.WOUND) {
                cfg.woundX = (float) mouseX - dragOffsetX;
                cfg.woundY = (float) mouseY - dragOffsetY;
            } else {
                cfg.energyX = (float) mouseX - dragOffsetX;
                cfg.energyY = (float) mouseY - dragOffsetY;
            }
            ClientUiConfig.save();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging != null) {
            dragging = null;
            ClientUiConfig.save();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            Target t = hovered(mouseX, mouseY);
            adjustScale(t != null ? t : selected, (float) Math.signum(scrollY) * 0.05f);
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

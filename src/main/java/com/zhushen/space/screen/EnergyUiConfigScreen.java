package com.zhushen.space.screen;

import com.zhushen.space.client.ClientEnergyData;
import com.zhushen.space.client.ClientUiConfig;
import com.zhushen.space.client.DamageRangeHud;
import com.zhushen.space.client.DefenseHudRenderer;
import com.zhushen.space.client.EnergyHudRenderer;
import com.zhushen.space.client.LimbHudRenderer;
import com.zhushen.space.client.WoundHudRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * 主神空间界面设置（底部按钮卡片上移，留出快捷栏 / 状态条区域用于预览防御 HUD）：
 * - 按住鼠标拖动能量池 HUD / 战斗模式伤势 HUD / 战斗模式伤害区间 HUD 调整位置
 * - 滚轮（悬停在哪个元素上就调整哪个）/ +/- 按钮（调整最近点击过的元素）调整缩放（0.5x ~ 2.0x）
 * - 位置与缩放持久化到客户端配置
 *
 * 无能量池时展示一条示例池；伤势 HUD 无伤势时展示示例数值，便于预先调整位置。
 */
public class EnergyUiConfigScreen extends Screen {

    /** 可调整的 HUD 元素 */
    private enum Target { ENERGY, WOUND, DAMAGE, LIMB, DEFENSE }

    private final Screen parent;
    /** 正在拖拽的元素（null = 未拖拽） */
    private Target dragging;
    /** 最近一次点击 / 滚轮作用的元素：+/- 按钮与缩放标签针对它 */
    private Target selected = Target.ENERGY;
    private float dragOffsetX, dragOffsetY;
    private Button scaleLabel;
    private Button limbShowBtn, limbStyleBtn, limbQuipBtn, defShowBtn;

    public EnergyUiConfigScreen(Screen parent) {
        super(Component.translatable("screen.zhushenspace.energy_config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        addRenderableWidget(new ZsButton(cx - 130, this.height - 124, 40, 20,
                Component.translatable("screen.zhushenspace.energy_config.scale_down"),
                        b -> adjustScale(-0.05f)));
        scaleLabel = addRenderableWidget(new ZsButton(cx - 86, this.height - 124, 92, 20,
                scaleText(), b -> { }));
        scaleLabel.active = false;
        addRenderableWidget(new ZsButton(cx + 10, this.height - 124, 40, 20,
                Component.translatable("screen.zhushenspace.energy_config.scale_up"),
                        b -> adjustScale(0.05f)));
        addRenderableWidget(new ZsButton(cx - 130, this.height - 100, 130, 20,
                Component.translatable("screen.zhushenspace.energy_config.reset"),
                        b -> {
                            ClientUiConfig.Data cfg = ClientUiConfig.get();
                            cfg.energyX = -1;
                            cfg.energyY = -1;
                            cfg.energyScale = 1.0f;
                            cfg.woundX = -1;
                            cfg.woundY = -1;
                            cfg.woundScale = 1.0f;
                            cfg.damageX = -1;
                            cfg.damageY = -1;
                            cfg.damageScale = 1.0f;
                            cfg.limbX = -1;
                            cfg.limbY = -1;
                            cfg.limbScale = 1.0f;
                            cfg.defX = -1;
                            cfg.defY = -1;
                            cfg.defScale = 1.0f;
                            ClientUiConfig.save();
                            scaleLabel.setMessage(scaleText());
                        }));
        addRenderableWidget(new ZsButton(cx + 10, this.height - 100, 130, 20,
                CommonComponents.GUI_DONE, b -> onClose()));
        // 肢体 HUD 开关（显示 / 风格 / 吐槽气泡）
        limbShowBtn = addRenderableWidget(new ZsButton(cx - 150, 44, 96, 18, limbShowText(), b -> {
            ClientUiConfig.get().limbHudEnabled = !ClientUiConfig.get().limbHudEnabled;
            ClientUiConfig.save();
            b.setMessage(limbShowText());
        }));
        limbStyleBtn = addRenderableWidget(new ZsButton(cx - 48, 44, 96, 18, limbStyleText(), b -> {
            // 艾克赛德 → 终端 → 大黑塔 → 艾克赛德
            int st = ClientUiConfig.get().limbHudStyle;
            ClientUiConfig.get().limbHudStyle = st == 2 ? 0 : st == 0 ? 1 : 2;
            ClientUiConfig.save();
            b.setMessage(limbStyleText());
        }));
        limbQuipBtn = addRenderableWidget(new ZsButton(cx + 54, 44, 96, 18, limbQuipText(), b -> {
            ClientUiConfig.get().limbHudQuips = !ClientUiConfig.get().limbHudQuips;
            ClientUiConfig.save();
            b.setMessage(limbQuipText());
        }));
        // 防御 / 豁免 HUD 开关（关闭后恢复原版护甲条）
        defShowBtn = addRenderableWidget(new ZsButton(cx - 70, 66, 140, 18, defShowText(), b -> {
            ClientUiConfig.get().defHudEnabled = !ClientUiConfig.get().defHudEnabled;
            ClientUiConfig.save();
            b.setMessage(defShowText());
        }));
    }

    private static Component onOff(boolean v) {
        return Component.translatable(v ? "options.on" : "options.off");
    }

    private Component limbShowText() {
        return Component.translatable("screen.zhushenspace.limb_hud.show", onOff(ClientUiConfig.get().limbHudEnabled));
    }

    private Component limbStyleText() {
        return Component.translatable("screen.zhushenspace.limb_hud.style",
                Component.translatable(switch (ClientUiConfig.get().limbHudStyle) {
                    case 1 -> "screen.zhushenspace.limb_hud.style.herta";
                    case 2 -> "screen.zhushenspace.limb_hud.style.exaid";
                    default -> "screen.zhushenspace.limb_hud.style.terminal";
                }));
    }

    private Component defShowText() {
        return Component.translatable("screen.zhushenspace.def_hud.show", onOff(ClientUiConfig.get().defHudEnabled));
    }

    private Component limbQuipText() {
        return Component.translatable("screen.zhushenspace.limb_hud.quips", onOff(ClientUiConfig.get().limbHudQuips));
    }

    private Component scaleText() {
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        float scale = switch (selected) {
            case WOUND -> cfg.woundScale;
            case DAMAGE -> cfg.damageScale;
            case LIMB -> cfg.limbScale;
            case DEFENSE -> cfg.defScale;
            default -> cfg.energyScale;
        };
        String key = switch (selected) {
            case WOUND -> "screen.zhushenspace.energy_config.scale_value_wound";
            case DAMAGE -> "screen.zhushenspace.energy_config.scale_value_damage";
            case LIMB -> "screen.zhushenspace.energy_config.scale_value_limb";
            case DEFENSE -> "screen.zhushenspace.energy_config.scale_value_def";
            default -> "screen.zhushenspace.energy_config.scale_value";
        };
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
        } else if (target == Target.DAMAGE) {
            float next = cfg.damageScale + delta;
            next = Math.max(DamageRangeHud.MIN_SCALE, Math.min(DamageRangeHud.MAX_SCALE, next));
            cfg.damageScale = Math.round(next * 100f) / 100f;
        } else if (target == Target.DEFENSE) {
            float next = cfg.defScale + delta;
            next = Math.max(DefenseHudRenderer.MIN_SCALE, Math.min(DefenseHudRenderer.MAX_SCALE, next));
            cfg.defScale = Math.round(next * 100f) / 100f;
        } else if (target == Target.LIMB) {
            float next = cfg.limbScale + delta;
            next = Math.max(LimbHudRenderer.MIN_SCALE, Math.min(LimbHudRenderer.MAX_SCALE, next));
            cfg.limbScale = Math.round(next * 100f) / 100f;
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

    private float[] damageLayout() {
        return DamageRangeHud.layout(font, this.width, this.height, ClientUiConfig.get().damageScale, true);
    }

    private float[] layoutOf(Target t) {
        return switch (t) {
            case WOUND -> woundLayout();
            case DAMAGE -> damageLayout();
            case LIMB -> LimbHudRenderer.layout(this.width, this.height, ClientUiConfig.get().limbScale);
            case DEFENSE -> DefenseHudRenderer.layout(this.width, this.height, ClientUiConfig.get().defScale);
            default -> energyLayout();
        };
    }

    private static boolean inside(double mx, double my, float[] pos) {
        return mx >= pos[0] - 4 && mx <= pos[0] + pos[2] + 4
                && my >= pos[1] - 4 && my <= pos[1] + pos[3] + 4;
    }

    /** 鼠标所在的 HUD 元素（伤势面板优先，因为它默认在角落且较小） */
    private Target hovered(double mx, double my) {
        if (ClientUiConfig.get().limbHudEnabled && inside(mx, my, layoutOf(Target.LIMB))) return Target.LIMB;
        if (ClientUiConfig.get().defHudEnabled && inside(mx, my, layoutOf(Target.DEFENSE))) return Target.DEFENSE;
        if (inside(mx, my, damageLayout())) return Target.DAMAGE;
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
        // 预览按战斗模式的统一排版（右侧栏堆叠）
        com.zhushen.space.client.HudLayout.compute(net.minecraft.client.Minecraft.getInstance(), this.width, this.height, true);
        int cx = this.width / 2;
        // 按钮底板（大黑塔卡片）先画，按钮在其上
        int cardX = cx - 138, cardY = this.height - 132, cardW = 286, cardH = 56;
        ZsTheme.card(g, cardX, cardY, cardW, cardH, false);
        ZsTheme.petals(g, cardX, cardY, cardW, cardH, 4);
        ZsTheme.flower(g, cardX, cardY, 10, ZsAnim.nowMs() / 60f % 360, 0.9f);
        super.render(g, mouseX, mouseY, partialTick);
        HertaChibi.render(g, font, cx + 64, cardY, mouseX, mouseY);
        // 能量池实时预览（无池时显示示例）
        if (ClientEnergyData.pools().isEmpty()) {
            EnergyHudRenderer.renderDemo(g, font, this.width, this.height);
        } else {
            EnergyHudRenderer.renderAll(g, font, this.width, this.height);
        }
        // 战斗模式伤势 HUD 预览（无伤势时显示示例数值）
        WoundHudRenderer.renderPreview(g, font, this.width, this.height);
        // 战斗模式伤害区间 HUD 预览（无数据时显示示例 4 ~ 20）
        DamageRangeHud.renderPreview(g, font, this.width, this.height);
        // 战斗模式肢体 HUD 预览（无断肢时显示示例：断左臂）
        if (ClientUiConfig.get().limbHudEnabled) LimbHudRenderer.renderPreview(g, font, this.width, this.height);
        // 防御 / 豁免 HUD 预览（无数据时显示示例）
        if (ClientUiConfig.get().defHudEnabled) DefenseHudRenderer.renderPreview(g, font, this.width, this.height);
        selectionFrame(g, layoutOf(selected));

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
            // 按钮优先（HUD 预览与按钮重叠时不抢点击）
            for (var w : children()) if (w.isMouseOver(mouseX, mouseY)) return super.mouseClicked(mouseX, mouseY, button);
            Target t = hovered(mouseX, mouseY);
            if (t != null) {
                float[] pos = layoutOf(t);
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
            } else if (dragging == Target.DAMAGE) {
                cfg.damageX = (float) mouseX - dragOffsetX;
                cfg.damageY = (float) mouseY - dragOffsetY;
            } else if (dragging == Target.LIMB) {
                cfg.limbX = Math.max(0, (float) mouseX - dragOffsetX);
                cfg.limbY = Math.max(0, (float) mouseY - dragOffsetY);
            } else if (dragging == Target.DEFENSE) {
                cfg.defX = Math.max(0, (float) mouseX - dragOffsetX);
                cfg.defY = Math.max(0, (float) mouseY - dragOffsetY);
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

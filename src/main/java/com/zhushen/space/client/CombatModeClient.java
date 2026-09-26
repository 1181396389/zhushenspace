package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.screen.ZsAnim;
import com.zhushen.space.screen.ZsTheme;
import com.zhushen.space.network.UseSkillPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 战斗模式（默认 Alt 切换）：
 * - 隐藏原版物品栏，替换为主神空间风格的九宫格技能栏
 * - 血量/饱食度等原版 HUD 位于技能栏上方，无需额外处理
 * - 按 1~9 使用对应槽位的主动技能，格子显示技能短标签与冷却
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public class CombatModeClient {

    // ===== 淡蓝色主题 =====
    private static final int BAR_BG = 0xD80A1622;
    private static final int BAR_BORDER = 0xFF5B9BD5;
    private static final int SLOT_BG = 0x99132B42;
    private static final int SLOT_BORDER = 0x883BA9E0;
    private static final int SLOT_EMPTY_BG = 0x660D1B2A;
    private static final int TEXT_MAIN = 0xFFD9EEFF;
    private static final int COOLDOWN_TEXT = 0xFFFFD966;

    private static boolean combatMode = false;

    public static boolean combatMode() {
        return combatMode;
    }

    // ===== 按键处理 =====

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        // Alt：切换战斗模式
        if (event.getKey() == ClientSetup.TOGGLE_COMBAT.getKey().getValue()
                && event.getAction() == GLFW.GLFW_PRESS) {
            combatMode = !combatMode;
            if (combatMode) combatSince = ZsAnim.nowMs();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.UI_BUTTON_CLICK.value(), combatMode ? 1.2f : 0.8f));
            return;
        }

        // V：切换战斗预设技能栏（A/B），两栏共享已解锁技能
        if (combatMode && event.getKey() == ClientSetup.SWITCH_SKILL_BAR.getKey().getValue()
                && event.getAction() == GLFW.GLFW_PRESS) {
            ClientUiConfig.Data cfg = ClientUiConfig.get();
            cfg.activeBar = cfg.activeBar == 0 ? 1 : 0;
            ClientUiConfig.save();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.UI_BUTTON_CLICK.value(), cfg.activeBar == 0 ? 1.0f : 1.1f));
            return;
        }

        // 1~9：使用当前技能栏对应槽位技能（昏迷时禁止释放）
        if (combatMode && event.getAction() == GLFW.GLFW_PRESS
                && event.getKey() >= GLFW.GLFW_KEY_1 && event.getKey() <= GLFW.GLFW_KEY_9) {
            if (UnconsciousClient.isUnconscious(mc.player)) return;
            int slot = event.getKey() - GLFW.GLFW_KEY_1;
            int bar = ClientUiConfig.get().activeBar;
            if (ClientSkillData.slotAbility(bar, slot) >= 0) {
                PacketDistributor.sendToServer(new UseSkillPayload(bar, slot));
            }
            return;
        }
    }

    /**
     * 战斗模式锁定物品栏数字键：在原版处理按键（handleKeybinds）之前吞掉 1~9 物品栏键的点击，
     * 物品栏只能用滚轮切换；数字键仅用于释放技能。
     */
    @SubscribeEvent
    public static void onClientTickPre(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
        if (!combatMode) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        for (var key : mc.options.keyHotbarSlots) {
            while (key.consumeClick()) { }
        }
    }

    // ===== HUD 渲染 =====

    /** 进入战斗模式时刻（技能栏滑入动画） */
    private static long combatSince;
    /** 每个技能本轮冷却的总时长（取观测到的最大剩余值）与冷却完成时刻（完成闪光；-1=冷却中，0=无） */
    private static final long[] cdTotal = new long[SkillAbility.COUNT];
    private static final long[] cdReadyAt = new long[SkillAbility.COUNT];

    /** 战斗模式下隐藏原版物品栏 */
    @SubscribeEvent
    public static void onHotbarLayer(RenderGuiLayerEvent.Pre event) {
        if (combatMode && VanillaGuiLayers.HOTBAR.equals(event.getName())) {
            event.setCanceled(true);
        }
    }

    /** 战斗模式下绘制主神空间风格技能栏（位置与原版物品栏一致，血量自动位于其上方）：
     *  左侧为当前预设栏 A/B 指示，九宫格显示当前栏位的技能与冷却 */
    @SubscribeEvent
    public static void onHudRender(RenderGuiEvent.Post event) {
        if (!combatMode) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int barX = g.guiWidth() / 2 - 91;
        int barY = g.guiHeight() - 22;
        int activeBar = ClientUiConfig.get().activeBar;

        // 面板背景与边框（左侧扩展 14px 放置栏位指示），进入战斗模式时自下而上滑入
        float in = ZsAnim.easeOutCubic((ZsAnim.nowMs() - combatSince) / 250f);
        g.pose().pushPose();
        g.pose().translate(0, (1 - in) * 26, 0);
        float glow = ZsAnim.pulse(3000);
        g.fill(barX - 16, barY - 2, barX + 184, barY + 24, ZsAnim.withAlpha(0xFF3BA9E0, 0.10f + 0.12f * glow));
        g.fill(barX - 15, barY - 1, barX + 183, barY + 23, BAR_BORDER);
        g.fill(barX - 14, barY, barX + 182, barY + 22, BAR_BG);
        ZsAnim.NEBULA.draw(g, barX - 14, barY, 196, 22, 0x55FFFFFF);

        // 栏位指示（A/B）
        g.fill(barX - 13, barY + 1, barX - 1, barY + 21, SLOT_BG);
        g.renderOutline(barX - 13, barY + 1, 12, 20, SLOT_BORDER);
        g.drawCenteredString(font, activeBar == 0 ? "A" : "B", barX - 7, barY + 7, COOLDOWN_TEXT);

        for (int slot = 0; slot < 9; slot++) {
            int sx = barX + 1 + slot * 20;
            int sy = barY + 1;
            int abilityId = ClientSkillData.slotAbility(activeBar, slot);

            if (abilityId < 0) {
                g.fill(sx, sy, sx + 20, sy + 20, SLOT_EMPTY_BG);
                g.renderOutline(sx, sy, 20, 20, SLOT_BORDER);
                continue;
            }
            SkillAbility ability = SkillAbility.values()[abilityId];

            g.fill(sx, sy, sx + 20, sy + 20, SLOT_BG);
            g.renderOutline(sx, sy, 20, 20, SLOT_BORDER);

            // 技能图标（16×16 居中）
            g.blit(ability.iconTexture(), sx + 2, sy + 2, 16, 16, 0f, 0f, 32, 32, 32, 32);

            // 冷却遮罩与剩余秒数
            long remainMs = ClientSkillData.cooldownRemainingMs(abilityId);
            if (remainMs > 0) {
                if (remainMs > cdTotal[abilityId]) cdTotal[abilityId] = remainMs;
                ZsTheme.cooldown(g, sx, sy, 20, remainMs / (float) Math.max(1, cdTotal[abilityId]));
                cdReadyAt[abilityId] = -1;
                int seconds = (int) Math.ceil(remainMs / 1000.0);
                g.drawCenteredString(font, String.valueOf(seconds), sx + 10, sy + 6, COOLDOWN_TEXT);
            } else {
                // 冷却结束：金色闪光 0.5 秒
                if (cdReadyAt[abilityId] == -1) cdReadyAt[abilityId] = ZsAnim.nowMs();
                cdTotal[abilityId] = 0;
                float f = cdReadyAt[abilityId] > 0
                        ? 1 - ZsAnim.clamp01((ZsAnim.nowMs() - cdReadyAt[abilityId]) / 500f) : 0;
                if (f > 0) {
                    g.fill(sx, sy, sx + 20, sy + 20, ZsAnim.withAlpha(0xFFFFD966, 0.45f * f));
                    g.renderOutline(sx - 1, sy - 1, 22, 22, ZsAnim.withAlpha(0xFFFFD966, f));
                }
            }
        }

        g.pose().popPose();

        // 原版物品栏（战斗模式下缩小为右侧无边框竖排）
        renderSideHotbar(g, mc, font);
    }

    // ===== 右侧迷你物品栏 =====

    /** 迷你物品栏：0.75 缩放（物品 12px），槽距 16px，右缘锚定底部 */
    private static final float SIDE_HOTBAR_SCALE = 0.75f;
    private static final int SIDE_HOTBAR_PITCH = 16;
    /** 选中槽高亮（无边框设计：仅半透明淡光） */
    private static final int SIDE_SELECTED_TINT = 0x45FFFFFF;
    /** 整列极淡底衬（提高物品在世界画面上的可读性，非边框） */
    private static final int SIDE_STRIP_BG = 0x330A1622;

    /** 战斗模式的原版物品栏：缩小后竖排于屏幕右下角，无边框，滚轮仍可切换选中槽位 */
    private static void renderSideHotbar(GuiGraphics g, Minecraft mc, Font font) {
        var inv = mc.player.getInventory();
        int slots = 9;
        int startX = g.guiWidth() - 22;
        int startY = g.guiHeight() - 26 - slots * SIDE_HOTBAR_PITCH;

        // 极淡整列底衬（无边框）
        g.fill(startX - 3, startY - 3, startX + 15, startY + slots * SIDE_HOTBAR_PITCH + 2, SIDE_STRIP_BG);

        for (int i = 0; i < slots; i++) {
            int sy = startY + i * SIDE_HOTBAR_PITCH;
            if (i == inv.selected) {
                // 选中槽：半透明白色淡光，无边框
                g.fill(startX - 1, sy - 1, startX + 14, sy + 14, SIDE_SELECTED_TINT);
            }
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            g.pose().pushPose();
            g.pose().translate(startX, sy, 0);
            g.pose().scale(SIDE_HOTBAR_SCALE, SIDE_HOTBAR_SCALE, 1.0f);
            g.renderItem(mc.player, stack, 0, 0, 0);
            if (stack.getCount() > 1) {
                String cnt = String.valueOf(stack.getCount());
                g.drawString(font, cnt, 17 - font.width(cnt), 9, 0xFFFFFF, true);
            }
            g.pose().popPose();
        }
    }
}

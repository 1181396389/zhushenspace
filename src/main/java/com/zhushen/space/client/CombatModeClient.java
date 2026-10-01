package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.screen.ZsAnim;
import com.zhushen.space.screen.BladeBar;
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

    private static boolean combatMode = false;

    public static boolean combatMode() {
        return combatMode;
    }

    /** 本次进入战斗模式的时刻（毫秒），供伤势 HUD 等做展开动画 */
    public static long combatSince() {
        return combatSince;
    }

    private static int chargeKey = -1, chargeBar, chargeSlot, chargeNonce;
    private static void finishCharge(boolean release) {
        if (chargeKey < 0) return;
        chargeKey = -1;
        ClientCharge.clientEnd();
        if (Minecraft.getInstance().getConnection() != null)
            PacketDistributor.sendToServer(new com.zhushen.space.network.ChargeSkillPayload(chargeBar, chargeSlot,
                    release ? 1 : 2, chargeNonce));
    }

    // ===== 按键处理 =====

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) { finishCharge(false); return; }
        if (event.getKey() == chargeKey && event.getAction() == GLFW.GLFW_RELEASE) {
            finishCharge(true); return;
        }

        // Alt：切换战斗模式
        if (event.getKey() == ClientSetup.TOGGLE_COMBAT.getKey().getValue()
                && event.getAction() == GLFW.GLFW_PRESS) {
            finishCharge(false);
            combatMode = !combatMode;
            if (combatMode) {
                combatSince = ZsAnim.nowMs();
                DrawFx.play(BladeBar.Sword.ofBar(ClientUiConfig.get().activeBar)); // 宝具拔出演出
            } else {
                DrawFx.stop();
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 0.8f));
            }
            return;
        }

        // V：切换战斗预设技能栏（A/B），两栏共享已解锁技能
        if (combatMode && event.getKey() == ClientSetup.SWITCH_SKILL_BAR.getKey().getValue()
                && event.getAction() == GLFW.GLFW_PRESS) {
            finishCharge(false);
            ClientUiConfig.Data cfg = ClientUiConfig.get();
            cfg.activeBar = cfg.activeBar == 0 ? 1 : 0;
            ClientUiConfig.save();
            DrawFx.play(BladeBar.Sword.ofBar(cfg.activeBar)); // 换剑：重新拔出
            return;
        }

        // 1~9：使用当前技能栏对应槽位技能（昏迷时禁止释放）
        if (combatMode && event.getAction() == GLFW.GLFW_PRESS
                && event.getKey() >= GLFW.GLFW_KEY_1 && event.getKey() <= GLFW.GLFW_KEY_9) {
            if (UnconsciousClient.isUnconscious(mc.player)) return;
            int slot = event.getKey() - GLFW.GLFW_KEY_1;
            int bar = ClientUiConfig.get().activeBar;
            if (ClientSkillData.slotAbility(bar, slot) >= 0) {
                if (chargeKey >= 0) return;
                var art = com.zhushen.space.data.ArtSkill.of(SkillAbility.values()[ClientSkillData.slotAbility(bar, slot)]);
                if (com.zhushen.space.common.ArtCharge.supports(art)) {
                    chargeKey = event.getKey(); chargeBar = bar; chargeSlot = slot; chargeNonce++;
                    PacketDistributor.sendToServer(new com.zhushen.space.network.ChargeSkillPayload(bar, slot, 0, chargeNonce));
                } else PacketDistributor.sendToServer(new UseSkillPayload(bar, slot));
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
        Minecraft mc = Minecraft.getInstance();
        if (chargeKey >= 0) {
            if (mc.player == null || !combatMode || mc.screen != null || !mc.isWindowActive()
                    || !mc.player.isAlive() || UnconsciousClient.isUnconscious(mc.player)) finishCharge(false);
            else if (GLFW.glfwGetKey(mc.getWindow().getWindow(), chargeKey) == GLFW.GLFW_RELEASE) finishCharge(true);
        }
        if (!combatMode || mc.player == null) return;
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
            // 巨剑栏比原版物品栏高 8px：血量/饥饿等状态条整体上移，避免重叠
            Minecraft.getInstance().gui.leftHeight += BladeBar.H - 22;
            Minecraft.getInstance().gui.rightHeight += BladeBar.H - 22;
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
        int barX = g.guiWidth() / 2 - BladeBar.W / 2;
        int barY = g.guiHeight() - BladeBar.H - 1;
        int activeBar = ClientUiConfig.get().activeBar;

        // 进入战斗模式：巨剑自下方升起，同时剑身由护手向剑尖「出鞘」展开
        float in = ZsAnim.easeOutCubic((ZsAnim.nowMs() - combatSince) / 250f);
        float draw = DrawFx.drawProgress();
        DrawFx.renderUnder(g, barX, barY);
        g.pose().pushPose();
        g.pose().translate(0, (1 - in) * 30, 0);
        int reveal = barX + 30 + (int) ((BladeBar.W - 30) * draw);
        g.enableScissor(barX - 4, barY - 8, reveal, barY + BladeBar.H + 34); // 下沿含升起位移
        // A 栏誓约胜利之剑 / B 栏乖离剑
        BladeBar.draw(g, font, barX, barY, 1f, BladeBar.Sword.ofBar(activeBar), activeBar == 0 ? "A" : "B", true);

        for (int slot = 0; slot < 9; slot++) {
            int sx = BladeBar.slotX(barX, slot, 1f);
            int sy = BladeBar.slotY(barY, 1f);
            int abilityId = ClientSkillData.slotAbility(activeBar, slot);
            if (abilityId < 0) continue; // 空槽：保留剑身凹槽原貌
            SkillAbility ability = SkillAbility.values()[abilityId];

            long remainMs = ClientSkillData.cooldownRemainingMs(abilityId);
            float remain = 0, flash = 0;
            if (remainMs > 0) {
                if (remainMs > cdTotal[abilityId]) cdTotal[abilityId] = remainMs;
                remain = remainMs / (float) Math.max(1, cdTotal[abilityId]);
                cdReadyAt[abilityId] = -1;
            } else {
                // 冷却结束：火花迸发 0.5 秒
                if (cdReadyAt[abilityId] == -1) cdReadyAt[abilityId] = ZsAnim.nowMs();
                cdTotal[abilityId] = 0;
                flash = cdReadyAt[abilityId] > 0
                        ? 1 - ZsAnim.clamp01((ZsAnim.nowMs() - cdReadyAt[abilityId]) / 500f) : 0;
            }
            BladeBar.socket(g, ability.iconTexture(), sx, sy, BladeBar.SLOT, remain, flash, false);
            if (remainMs > 0) {
                int seconds = (int) Math.ceil(remainMs / 1000.0);
                g.drawCenteredString(font, String.valueOf(seconds), sx + 10, sy + 6, BladeBar.EMBER_HOT);
            }
        }
        g.disableScissor();

        g.pose().popPose();
        DrawFx.renderOver(g, font, barX, barY);

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

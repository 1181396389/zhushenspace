package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.common.PoolEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.HashSet;
import java.util.Set;

/** 能量池客户端效果：魔力感知发光（仅自己可见）、蛛行术、水面行走、佛力辨识、状态提示 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public class ClientPoolFx {
    private static Set<Integer> sensed = new HashSet<>();
    private static long senseUntil;

    public static void sense(int[] ids, int ticks) {
        Minecraft mc = Minecraft.getInstance();
        Set<Integer> next = new HashSet<>();
        for (int id : ids) next.add(id);
        if (mc.level != null) {
            for (int id : sensed) {
                if (next.contains(id)) continue;
                Entity e = mc.level.getEntity(id);
                if (e != null) e.setGlowingTag(false);
            }
        }
        sensed = ticks > 0 ? next : new HashSet<>();
        senseUntil = System.currentTimeMillis() + ticks * 50L;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        // 魔力感知：每刻重新点亮（服务器同步实体标志时会覆盖）
        for (int id : sensed) {
            Entity en = mc.level.getEntity(id);
            if (en != null) en.setGlowingTag(System.currentTimeMillis() < senseUntil);
        }
        Vec3 v = p.getDeltaMovement();
        // 蛛行术：贴墙时 前进 = 向上爬，潜行 = 贴住不动，否则缓慢下滑
        if (ClientArtData.flag(PoolEffects.F_SPIDER) && p.horizontalCollision && !p.getAbilities().flying) {
            double vy = p.input.forwardImpulse > 0 || p.input.jumping ? 0.22 : p.isShiftKeyDown() ? 0 : Math.max(v.y, -0.08);
            p.setDeltaMovement(v.x, vy, v.z);
            p.resetFallDistance();
        }
        // 水面行走：站在水面上（潜行 = 沉入）
        if (ClientArtData.flag(PoolEffects.F_WATER) && !p.isShiftKeyDown() && !p.getAbilities().flying) {
            boolean waterBelow = mc.level.getFluidState(p.blockPosition().below()).is(FluidTags.WATER)
                    || mc.level.getFluidState(p.blockPosition()).is(FluidTags.WATER);
            if (waterBelow && !p.isUnderWater()) {
                double surface = p.blockPosition().getY();
                if (mc.level.getFluidState(p.blockPosition()).is(FluidTags.WATER)) surface += 1;
                if (p.getY() < surface) p.setDeltaMovement(v.x, Math.max(v.y, 0.12), v.z);
                else if (v.y < 0) { p.setDeltaMovement(v.x, 0, v.z); p.setOnGround(true); }
                p.resetFallDistance();
            }
        }
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        GuiGraphics g = e.getGuiGraphics();
        // 佛力：池不为空时辨识黑暗 / 不死生物
        boolean buddha = false;
        for (var pool : ClientEnergyData.pools()) if (pool.id().equals("buddha") && pool.current() > 0) buddha = true;
        HitResult hr = mc.hitResult;
        if (buddha && hr instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity le
                && le.getType().is(EntityTypeTags.UNDEAD)) {
            g.drawCenteredString(mc.font, Component.translatable("hud.zhushenspace.pool.undead"),
                    g.guiWidth() / 2, g.guiHeight() / 2 - 20, 0xFFFFD27F);
        }
        // 状态角标
        int y = g.guiHeight() - 60;
        String[][] tags = {{"" + PoolEffects.F_SENSE, "hud.zhushenspace.pool.sense"}, {"" + PoolEffects.F_SPIDER, "hud.zhushenspace.pool.spider"},
                {"" + PoolEffects.F_WATER, "hud.zhushenspace.pool.water"}, {"" + PoolEffects.F_SIGHT, "hud.zhushenspace.pool.sight"},
                {"" + PoolEffects.F_REST, "hud.zhushenspace.pool.rest"}, {"" + PoolEffects.F_BOOST, "hud.zhushenspace.pool.boost"}};
        for (String[] t : tags) {
            if (!ClientArtData.flag(Integer.parseInt(t[0]))) continue;
            g.drawString(mc.font, Component.translatable(t[1]), 6, y, 0xFFBFE6FF, true);
            y -= 10;
        }
    }
}

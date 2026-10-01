package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.common.WildChild;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 狼孩 / 人猿泰山（客户端）：文盲（书本 / 讲台 / 告示牌界面打不开，告示牌文字显示为乱码）、泰山的攀爬与跳跃。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientWildChild {
    private ClientWildChild() {}

    /** 告示牌：{原正面, 原背面, 乱码正面, 乱码背面} */
    private static final Map<SignBlockEntity, SignText[]> SIGNS = new WeakHashMap<>();

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Opening e) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !WildChild.illiterate(p)) return;
        var s = e.getNewScreen();
        if (s instanceof BookViewScreen || s instanceof BookEditScreen || s instanceof LecternScreen || s instanceof AbstractSignEditScreen) {
            e.setCanceled(true);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.wild.illiterate"), true);
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        boolean tarzan = WildChild.tarzan(p);
        if (tarzan) climb(mc, p);
        if (p.tickCount % 20 == 3) signs(mc, p, WildChild.illiterate(p));
    }

    /** 泰山：贴墙时按前进或跳跃向上攀爬，速度 = 基础移动速度的一半；潜行贴墙不动，松开则缓慢下滑 */
    private static void climb(Minecraft mc, LocalPlayer p) {
        if (!p.horizontalCollision || p.isPassenger() || p.getAbilities().flying || p.isInWater()) return;
        Vec3 v = p.getDeltaMovement();
        double up = p.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) * 2.1585 * 0.5;
        double vy;
        if (p.isShiftKeyDown()) vy = 0;
        else if (mc.options.keyUp.isDown() || mc.options.keyJump.isDown()) vy = up;
        else vy = Math.max(v.y, -0.15);
        p.setDeltaMovement(v.x, vy, v.z);
        p.fallDistance = 0;
    }

    /** 泰山：站立 / 行走起跳也获得疾跑起跳的水平冲量 */
    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent e) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || e.getEntity() != p || p.isSprinting() || !WildChild.tarzan(p)) return;
        float yaw = p.getYRot() * Mth.DEG_TO_RAD;
        p.setDeltaMovement(p.getDeltaMovement().add(-Mth.sin(yaw) * 0.2, 0, Mth.cos(yaw) * 0.2));
    }

    private static void signs(Minecraft mc, LocalPlayer p, boolean illiterate) {
        if (!illiterate) {
            if (!SIGNS.isEmpty()) restoreAll();
            return;
        }
        int cx = p.chunkPosition().x, cz = p.chunkPosition().z;
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++) {
                LevelChunk ch = mc.level.getChunkSource().getChunk(cx + dx, cz + dz, false);
                if (ch == null) continue;
                for (BlockEntity be : ch.getBlockEntities().values()) {
                    if (be instanceof SignBlockEntity sb) scramble(sb);
                }
            }
    }

    private static void scramble(SignBlockEntity sb) {
        SignText f = sb.getFrontText(), b = sb.getBackText();
        SignText[] rec = SIGNS.get(sb);
        if (rec != null && rec[2] == f && rec[3] == b) return; // 已是乱码
        SignText of = rec != null && rec[2] == f ? rec[0] : f;
        SignText ob = rec != null && rec[3] == b ? rec[1] : b;
        SignText nf = garble(of), nb = garble(ob);
        sb.setText(nf, true);
        sb.setText(nb, false);
        SIGNS.put(sb, new SignText[]{of, ob, sb.getFrontText(), sb.getBackText()});
    }

    private static SignText garble(SignText t) {
        SignText r = t;
        for (int i = 0; i < 4; i++) {
            String s = t.getMessage(i, false).getString();
            if (!s.isEmpty()) r = r.setMessage(i, Component.literal(s).withStyle(ChatFormatting.OBFUSCATED));
        }
        return r;
    }

    private static void restoreAll() {
        for (var en : SIGNS.entrySet()) {
            SignBlockEntity sb = en.getKey();
            SignText[] rec = en.getValue();
            if (sb.isRemoved()) continue;
            if (sb.getFrontText() == rec[2]) sb.setText(rec[0], true);
            if (sb.getBackText() == rec[3]) sb.setText(rec[1], false);
        }
        SIGNS.clear();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) { SIGNS.clear(); }
}

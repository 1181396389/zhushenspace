package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.network.TkActionPayload;
import com.zhushen.space.network.TkStatePayload;
import com.zhushen.space.network.TkStrikePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 念动力操控（客户端）：
 * <ul>
 *   <li>各玩家的操控状态（悬浮武器、托举对象、剩余时长）与悬浮武器的出击轨迹，供 {@code ArtVfxRenderer} 绘制；</li>
 *   <li>本地输入：操控期间空手右键（不对着方块时）= 隔空取物 / 推开生物（潜行 = 托起），再按一次放下；
 *       左键 = 悬浮武器向准星目标出击（不影响正常攻击）；</li>
 *   <li>准星外围的细环：剩余时长。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientTelekinesis {
    private ClientTelekinesis() {}

    public record Strike(int target, long start, int flight) {}

    private static final class S {
        List<ItemStack> weapons = List.of();
        int held = -1;
        long until, start;
        Strike[] strikes = new Strike[0];
    }

    private static final Map<Integer, S> STATES = new HashMap<>();

    private static long now() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : mc.level.getGameTime();
    }

    public static void state(TkStatePayload p) {
        if (p.ticks() <= 0) { STATES.remove(p.entity()); return; }
        S s = STATES.computeIfAbsent(p.entity(), k -> { S n = new S(); n.start = now(); return n; });
        long until = now() + p.ticks();
        if (until > s.until + 20) s.start = now(); // 重新施放：剩余时长环重新计
        s.until = until;
        s.weapons = List.copyOf(p.weapons());
        s.held = p.held();
        if (s.strikes.length != s.weapons.size()) s.strikes = java.util.Arrays.copyOf(s.strikes, s.weapons.size());
    }

    public static void strike(TkStrikePayload p) {
        S s = STATES.get(p.owner());
        if (s == null || p.idx() < 0 || p.idx() >= s.strikes.length) return;
        s.strikes[p.idx()] = new Strike(p.target(), now(), Math.max(1, p.flight()));
    }

    public static List<ItemStack> weapons(int owner) {
        S s = STATES.get(owner);
        return s == null ? List.of() : s.weapons;
    }

    public static Strike strikeOf(int owner, int idx) {
        S s = STATES.get(owner);
        if (s == null || idx < 0 || idx >= s.strikes.length) return null;
        Strike k = s.strikes[idx];
        if (k != null && now() - k.start() > k.flight() + 10) { s.strikes[idx] = null; return null; }
        return k;
    }

    private static S self() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        S s = STATES.get(mc.player.getId());
        return s != null && now() < s.until ? s : null;
    }

    @SubscribeEvent
    public static void onInteract(InputEvent.InteractionKeyMappingTriggered e) {
        Minecraft mc = Minecraft.getInstance();
        S s = self();
        if (s == null || mc.player == null || mc.screen != null) return;
        if (e.isUseItem() && e.getHand() == InteractionHand.MAIN_HAND) {
            boolean holding = s.held >= 0;
            boolean empty = mc.player.getMainHandItem().isEmpty();
            boolean block = mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK;
            // 正托着东西：任何右键都是放下；空手且没对着方块（方块交给原版：远程开门 / 拉杆 / 容器）：取物 / 推 / 托
            if (holding || (empty && !block)) {
                PacketDistributor.sendToServer(new TkActionPayload(0, mc.player.isShiftKeyDown()));
                e.setSwingHand(false);
                e.setCanceled(true);
            }
        } else if (e.isAttack() && s.held >= 0) {
            // 正托着东西：左键 = 扔出去（不再挥拳 / 挖掘）
            PacketDistributor.sendToServer(new TkActionPayload(1, false));
            e.setSwingHand(true);
            e.setCanceled(true);
        } else if (e.isAttack() && !s.weapons.isEmpty()) {
            PacketDistributor.sendToServer(new TkActionPayload(1, false));
        }
    }

    /** 正托着东西：滚轮推远 / 拉近（不切换快捷栏） */
    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent e) {
        Minecraft mc = Minecraft.getInstance();
        S s = self();
        if (s == null || s.held < 0 || mc.screen != null || e.getScrollDeltaY() == 0) return;
        PacketDistributor.sendToServer(new TkActionPayload(e.getScrollDeltaY() > 0 ? 2 : 3, false));
        e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        if (STATES.isEmpty()) return;
        long n = now();
        STATES.values().removeIf(s -> n > s.until + 40);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) { STATES.clear(); }

    /** 准星外围：剩余时长细环（24 段，已流逝的段变暗）；托着东西时环内多一圈小点 */
    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        S s = self();
        if (s == null || mc.options.hideGui || mc.screen != null) return;
        GuiGraphics g = e.getGuiGraphics();
        float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);
        double t = now() + pt;
        float total = Math.max(1, s.until - s.start);
        float frac = Mth.clamp((float) ((s.until - t) / total), 0f, 1f);
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        int seg = 24;
        float r = 11.5f;
        for (int i = 0; i < seg; i++) {
            float a = (float) (-Math.PI / 2 + Math.PI * 2 * (i + 0.5) / seg);
            int x = cx + Math.round(Mth.cos(a) * r), y = cy + Math.round(Mth.sin(a) * r);
            boolean lit = (i + 0.5f) / seg <= frac;
            int col = lit ? 0xE0FF9A3C : 0x40FFFFFF;
            g.fill(x - 1, y - 1, x + 1, y + 1, col);
        }
        if (s.held >= 0) {
            float spin = (float) (t * 0.15);
            for (int i = 0; i < 4; i++) {
                float a = spin + (float) (Math.PI / 2 * i);
                int x = cx + Math.round(Mth.cos(a) * 6.5f), y = cy + Math.round(Mth.sin(a) * 6.5f);
                g.fill(x - 1, y - 1, x + 1, y + 1, 0xF0FFE2B0);
            }
            // 操作提示：准星下方一行小字
            var hint = net.minecraft.network.chat.Component.translatable("hud.zhushenspace.tk.hold_hint");
            var pose = g.pose();
            pose.pushPose();
            pose.translate(cx, cy + 18, 0);
            pose.scale(0.75f, 0.75f, 1f);
            g.drawCenteredString(mc.font, hint, 0, 0, 0xD0FFE2B0);
            pose.popPose();
        }
    }
}

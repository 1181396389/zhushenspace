package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.MeleeWeapon;
import com.zhushen.space.network.SyncLoadPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 负重（规则见 docs/cold-weapons-v1.md「负重」）。
 * <p>
 * 只计算标注了重量的物品（目前为基础冷兵器；原版物品与未标注的本模组物品不计；TACZ 枪械以后再定）：
 * 背包、盔甲、副手、饰品栏与鼠标上拿着的物品。
 * 负重上限（公斤，S = 力量，最少 0）：轻 = 10 + 3S，中 = 2 × 轻 + 2，重 = 3 × 轻 + 4；
 * 体型每大一级 ×（1 + 级数）（巨体 / 怪力各算一级）。
 * <ul>
 *   <li>中度负重（超过轻）：移动速度 −25%，闪避防御 −2，主动身体检定 −3</li>
 *   <li>重度负重（超过中）：移动速度 −50%，无法疾跑，闪避防御 −4，主动身体检定 −6</li>
 *   <li>超载（超过重）：移动速度 −90%，其余同重度</li>
 * </ul>
 * 每 10 tick 重新统计一次；创造 / 旁观模式不受影响。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class LoadManager {
    private LoadManager() {}

    public enum Tier {
        LIGHT(0, 0, 0), MEDIUM(-0.25, 2, 3), HEAVY(-0.5, 4, 6), OVER(-0.9, 4, 6);

        public final double speed;
        public final int dodge, check;

        Tier(double speed, int dodge, int check) {
            this.speed = speed;
            this.dodge = dodge;
            this.check = check;
        }

        public boolean noSprint() { return this.ordinal() >= HEAVY.ordinal(); }
    }

    private static final ResourceLocation SPEED_ID = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "load_speed");
    private static final Map<UUID, Tier> TIER = new HashMap<>();
    /** 上次同步（重量 ×10、三档上限 ×10） */
    private static final Map<UUID, int[]> SENT = new HashMap<>();

    /** 单个物品堆叠的重量（公斤）：只计有标注重量的物品（目前为基础冷兵器）；
     *  太极拳饰品、邀请函、魔虚罗法阵等没有标注重量的物品与原版物品一样不计 */
    public static float weight(ItemStack st) {
        if (st.isEmpty()) return 0f;
        MeleeWeapon w = MeleeWeapon.of(st);
        if (w != null) return w.weight * st.getCount();
        com.zhushen.space.data.ShieldType sh = com.zhushen.space.item.ZsShieldItem.of(st);
        return sh != null ? sh.weight * st.getCount() : 0f;
    }

    /** 当前携带的总重量 */
    public static float carried(ServerPlayer p) {
        float sum = 0f;
        Inventory inv = p.getInventory();
        for (ItemStack st : inv.items) sum += weight(st);
        for (ItemStack st : inv.armor) sum += weight(st);
        for (ItemStack st : inv.offhand) sum += weight(st);
        sum += weight(p.containerMenu.getCarried());
        float[] cur = {0f};
        CuriosApi.getCuriosInventory(p).ifPresent(h -> {
            var eq = h.getEquippedCurios();
            for (int i = 0; i < eq.getSlots(); i++) cur[0] += weight(eq.getStackInSlot(i));
        });
        return sum + cur[0];
    }

    /** 三档负重上限 {轻, 中, 重}（公斤） */
    public static float[] limits(ServerPlayer p) {
        int s = Math.max(0, CombatFormula.attr(p, AttributeType.STRENGTH));
        float light = 10 + 3 * s;
        float mult = 1 + FeatEffects.sizeForChecks(p);
        return new float[]{light * mult, (2 * light + 2) * mult, (3 * light + 4) * mult};
    }

    public static Tier tierOf(float w, float[] lim) {
        if (w > lim[2]) return Tier.OVER;
        if (w > lim[1]) return Tier.HEAVY;
        if (w > lim[0]) return Tier.MEDIUM;
        return Tier.LIGHT;
    }

    public static Tier tier(ServerPlayer p) { return TIER.getOrDefault(p.getUUID(), Tier.LIGHT); }

    /** 闪避防御减值 */
    public static int dodgePenalty(ServerPlayer p) { return tier(p).dodge; }

    /** 主动身体检定减值 */
    public static int checkPenalty(ServerPlayer p) { return tier(p).check; }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        Tier t = tier(p);
        if (t.noSprint() && p.isSprinting()) p.setSprinting(false);
        if (p.tickCount % 10 != 0) return;
        boolean exempt = p.isCreative() || p.isSpectator();
        float w = carried(p);
        float[] lim = limits(p);
        Tier now = exempt ? Tier.LIGHT : tierOf(w, lim);
        if (now != t) {
            TIER.put(p.getUUID(), now);
            if (!exempt) p.displayClientMessage(Component.translatable("msg.zhushenspace.load." + now.name().toLowerCase()), true);
        }
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            AttributeModifier cur = speed.getModifier(SPEED_ID);
            if (now.speed == 0) {
                if (cur != null) speed.removeModifier(SPEED_ID);
            } else if (cur == null || cur.amount() != now.speed) {
                speed.removeModifier(SPEED_ID);
                speed.addTransientModifier(new AttributeModifier(SPEED_ID, now.speed, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
        int[] key = {Math.round(w * 10), Math.round(lim[0] * 10), Math.round(lim[1] * 10), Math.round(lim[2] * 10), exempt ? 1 : 0};
        int[] last = SENT.get(p.getUUID());
        if (last == null || !java.util.Arrays.equals(last, key)) {
            SENT.put(p.getUUID(), key);
            PacketDistributor.sendToPlayer(p, new SyncLoadPayload(w, lim[0], lim[1], lim[2], exempt));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        TIER.remove(e.getEntity().getUUID());
        SENT.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        SENT.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        SENT.remove(e.getEntity().getUUID());
    }
}

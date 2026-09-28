package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.compat.TaczCompat;
import com.zhushen.space.network.DamagePanelPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家伤害浮动：玩家造成的每一次伤害都在面板伤害的 {@link #MIN_FACTOR}（20%）~ {@link #MAX_FACTOR}（100%）
 * 之间均匀随机（例如 50%、80%），不是配置项。
 * <p>
 * 结算顺序（{@link LivingIncomingDamageEvent}，护甲之前）：
 * <ol>
 *   <li>HIGHEST：伤害上限记录基础伤害（{@link DamageCap}）</li>
 *   <li>HIGH：肉搏 / 白刃固定加成、冲锋加成（{@link SkillManager}）——计入面板伤害</li>
 *   <li><b>NORMAL：本类掷出浮动倍率</b>，并按同一比例缩放上限的基础伤害</li>
 *   <li>LOW：操作暴击 / 感知弱点（{@link AttributeEvents}）——作用在浮动后的伤害上</li>
 *   <li>LOWEST：伤害上限截断</li>
 * </ol>
 * TACZ 枪械：在 TACZ 的 Pre 事件里对基础伤害掷一次（每发子弹一次，先于爆头倍率），本类跳过该发子弹的受伤结算。
 * <p>
 * 面板伤害（100%）每 {@link #SYNC_INTERVAL} tick 在服务端按当前手持物计算，数值变化时同步给客户端，
 * 由战斗模式的伤害区间 HUD 实时显示「下限 ~ 上限」（下限 = 面板 × 20% 向下取整）。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class DamageVariance {

    /** 浮动下限（占面板伤害的比例） */
    public static final float MIN_FACTOR = 0.2f;
    /** 浮动上限（占面板伤害的比例） */
    public static final float MAX_FACTOR = 1.0f;
    /** 敌对怪物攻击浮动下限 */
    public static final float MOB_MIN_FACTOR = 0.5f;
    /** 敌对怪物攻击浮动上限 */
    public static final float MOB_MAX_FACTOR = 1.0f;
    /** 面板伤害同步间隔（tick） */
    private static final int SYNC_INTERVAL = 2;

    private DamageVariance() {
    }

    /** 掷一次浮动倍率：[MIN_FACTOR, MAX_FACTOR] 均匀分布 */
    public static float roll(RandomSource random) {
        return MIN_FACTOR + random.nextFloat() * (MAX_FACTOR - MIN_FACTOR);
    }

    /** 显示用上限（向下取整） */
    public static int displayMax(float panel) {
        return (int) Math.floor(panel * MAX_FACTOR + 1.0e-4f);
    }

    /** 显示用下限（向下取整） */
    public static int displayMin(float panel) {
        return (int) Math.floor(panel * MIN_FACTOR + 1.0e-4f);
    }

    // ===== 结算 =====

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        // 敌对怪物（含其弹射物）的攻击：50% ~ 100% 浮动
        if (source.getEntity() instanceof net.minecraft.world.entity.monster.Enemy
                && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity mob
                && !(mob instanceof net.minecraft.world.entity.player.Player)
                && mob != event.getEntity() && event.getAmount() > 0f) {
            float f = MOB_MIN_FACTOR + mob.getRandom().nextFloat() * (MOB_MAX_FACTOR - MOB_MIN_FACTOR);
            event.setAmount(event.getAmount() * f);
            return;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) return;
        if (player == event.getEntity() || event.getAmount() <= 0f) return;
        if (WillpowerManager.isBonusStrike(player)) return; // 意志加持追加伤害：不浮动
        // TACZ 子弹已在 Pre 事件中对基础伤害掷过（普通 + 穿甲两次结算共用）
        if (GunDamage.isGun(source) && GunDamage.current(player, event.getEntity(), source) != null) return;
        float factor = roll(player.getRandom());
        event.setAmount(event.getAmount() * factor);
        DamageCap.scaleMeleeBase(player, event.getEntity(), factor);
    }

    // ===== 面板伤害同步 =====

    /** 上次同步给各玩家的面板（kind, 面板×100 取整, 弹丸数） */
    private static final Map<UUID, int[]> LAST_SENT = new HashMap<>();

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % SYNC_INTERVAL != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            DamagePanelPayload panel = compute(player);
            int[] key = {panel.kind(), Math.round(panel.panel() * 100f), panel.pellets()};
            int[] last = LAST_SENT.get(player.getUUID());
            if (last != null && last[0] == key[0] && last[1] == key[1] && last[2] == key[2]) continue;
            LAST_SENT.put(player.getUUID(), key);
            PacketDistributor.sendToPlayer(player, panel);
        }
    }

    /** 按当前手持物计算面板伤害（浮动前 100%，不含暴击 / 弱点 / 爆头等概率或命中部位相关的倍率） */
    public static DamagePanelPayload compute(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        {
            float[] gun = TaczCompat.panelDamage(player, main);
            if (gun != null) return new DamagePanelPayload((byte) 1, Math.max(0f, gun[0]), Math.max(1, (int) gun[1]));
        }
        float dmg = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (player.level() instanceof ServerLevel level && !main.isEmpty()) {
            // 锋利等无目标条件的附魔加成（以玩家自身为目标计算，亡灵杀手等针对特定生物的附魔不计入）
            try {
                dmg = EnchantmentHelper.modifyDamage(level, main, player, player.damageSources().playerAttack(player), dmg);
            } catch (RuntimeException ignored) {
            }
        }
        dmg += SkillManager.meleePanelBonus(player);
        return new DamagePanelPayload((byte) 0, Math.max(0f, dmg), 1);
    }

    /** 登录 / 重生 / 换维度后强制重发 */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LAST_SENT.clear();
    }
}

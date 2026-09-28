package com.zhushen.space.common;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 枪械伤害平衡（不引用任何 TACZ 类，TACZ 未安装时也可安全加载）。
 * <p>
 * 三条规则：
 * <ul>
 *   <li><b>A. 暴击 / 弱点降档</b>：枪械伤害的操作暴击与感知弱点倍率降为 {@link #CRIT_MULTIPLIER} /
 *       {@link #WEAK_POINT_MULTIPLIER}；TACZ 每发子弹会拆成「普通 + 穿甲」两次受伤结算，
 *       本类保证每发子弹只判定一次，两次结算共用同一结果；弱点被枪械命中消耗后，
 *       该目标在 {@link #WEAK_POINT_COOLDOWN_TICKS} 内不会因枪械命中再生成弱点。</li>
 *   <li><b>B. 狙击 / 重武器爆头相加</b>：由 TaczGunEvents 实现，爆头时技能加成与爆头倍率相加而非相乘。</li>
 *   <li><b>C. 总倍率上限</b>：本模组对枪械伤害的全部加成（技能乘区 × 暴击 × 弱点）
 *       合计不超过 {@link DamageCap#capFor(int)}（随枪械技能点数上涨）；TACZ 自带的爆头倍率不计入。</li>
 * </ul>
 */
public final class GunDamage {

    /** 枪械伤害的操作传奇暴击倍率（近战为 1.5） */
    public static final float CRIT_MULTIPLIER = 1.2f;
    /** 枪械伤害的感知弱点倍率（近战为 1.5） */
    public static final float WEAK_POINT_MULTIPLIER = 1.2f;
    /** 枪械命中消耗弱点后，同一目标弱点再生成的冷却（tick，3 秒） */
    public static final int WEAK_POINT_COOLDOWN_TICKS = 60;

    private static final String TACZ = "tacz";

    private GunDamage() {
    }

    /**
     * 是否为 TACZ 枪械造成的伤害：伤害类型属于 tacz 命名空间（子弹 / 虚空子弹），
     * 或直接来源实体是 TACZ 实体（打末影人时的魔法伤害、火箭弹爆炸等）。
     * 可识别 TACZ「伪装近战」的情况（直接来源为玩家但伤害类型仍是子弹）。
     */
    public static boolean isGun(DamageSource source) {
        var key = source.typeHolder().unwrapKey();
        if (key.isPresent() && TACZ.equals(key.get().location().getNamespace())) return true;
        Entity direct = source.getDirectEntity();
        return direct != null
                && TACZ.equals(BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType()).getNamespace());
    }

    // ===== 单发子弹命中上下文 =====

    /**
     * 一发子弹的命中上下文：TACZ 的 Pre 事件与随后的两次 hurt() 在服务端同一调用栈内同步执行，
     * Pre 时写入技能乘区，第一次 hurt 判定暴击 / 弱点并缓存，第二次 hurt 直接复用。
     */
    public static final class Hit {
        int targetId;
        long tick;
        /** 本发子弹已应用的技能乘区（折算为对最终伤害的倍率） */
        public float skillFactor = 1f;
        /** 本发子弹的总加成上限（按枪械技能点数） */
        public float cap = DamageCap.BASE_CAP;
        /** 是否已判定暴击 / 弱点 */
        public boolean rolled;
        /** 已判定的暴击 × 弱点倍率（已按总上限截断） */
        public float extra = 1f;
    }

    private static final Map<UUID, Hit> HITS = new HashMap<>();

    /** TACZ Pre 事件：开始一发子弹的结算，记录技能乘区并重置判定 */
    public static void beginHit(ServerPlayer attacker, Entity target, float skillFactor, float cap) {
        if (target instanceof net.neoforged.neoforge.entity.PartEntity<?> part) target = part.getParent(); // 多部件实体按本体记
        if (target == null || attacker.getServer() == null) return;
        Hit hit = HITS.computeIfAbsent(attacker.getUUID(), k -> new Hit());
        hit.targetId = target.getId();
        hit.tick = attacker.getServer().getTickCount();
        hit.skillFactor = skillFactor;
        hit.cap = cap;
        hit.rolled = false;
        hit.extra = 1f;
    }

    /**
     * 取当前这发子弹的上下文（同 tick、同目标才有效）；爆炸伤害不复用直击的上下文。
     * 无上下文时返回 null（按独立一次命中判定，技能乘区视为 1）。
     */
    public static Hit current(ServerPlayer attacker, Entity target, DamageSource source) {
        if (source.is(DamageTypeTags.IS_EXPLOSION) || attacker.getServer() == null) return null;
        Hit hit = HITS.get(attacker.getUUID());
        if (hit == null || hit.targetId != target.getId()
                || hit.tick != attacker.getServer().getTickCount()) {
            return null;
        }
        return hit;
    }

    /** 玩家下线清理 */
    public static void forget(UUID player) {
        HITS.remove(player);
    }

    /** 服务器关闭清理 */
    public static void clear() {
        HITS.clear();
    }
}

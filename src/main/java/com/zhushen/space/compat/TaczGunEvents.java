package com.zhushen.space.compat;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.SkillType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.common.NeoForge;

/**
 * TACZ 枪械联动事件（仅在 TACZ 已安装时由 {@link TaczCompat} 加载）。
 * <p>
 * 「枪械」技能按枪械类别走三条不同的加成曲线（{@link Tier}）：
 * <ul>
 *   <li>{@link Tier#FLAT 常规}（手枪 / 冲锋枪 / 小弹匣步枪）：每点子弹基础伤害 +1</li>
 *   <li>{@link Tier#VOLUME 霰弹 / 大容量}（霰弹枪、全部机枪、弹匣 ≥ 50 的步枪）：
 *       独立乘区，每点 +10%（满级 ×1.5）。霰弹枪每颗弹丸分别触发本事件，
 *       若仍用 +1 的算法会被弹丸数放大，故改为乘区。</li>
 *   <li>{@link Tier#HEAVY 狙击 / 单发重武器}（狙击枪、火箭筒）：独立乘区，每点 +50%（满级 ×3.5）。</li>
 * </ul>
 * 乘区只作用于弹头直击：{@link EntityHurtByGunEvent.Pre} 仅由子弹实体直接命中实体时触发，
 * 火箭筒等的爆炸伤害由 TACZ 的 ExplodeUtil 以原版爆炸结算，不经过本事件，因此天然不受乘区影响。
 * 本事件触发时距离衰减已结算，爆头倍率、护甲与穿甲等后续结算照常作用于加成后的伤害。
 */
final class TaczGunEvents {
    /** 常规枪械：每点枪械技能提供的子弹基础伤害 */
    static final float FLAT_PER_POINT = 1.0f;
    /** 霰弹 / 大容量枪械：每点乘区加成 */
    static final float VOLUME_MULT_PER_POINT = 0.10f;
    /** 狙击 / 单发重武器：每点乘区加成 */
    static final float HEAVY_MULT_PER_POINT = 0.50f;
    /** 步枪被视为「大容量」的弹匣阈值（基础弹匣容量） */
    static final int LARGE_MAG_THRESHOLD = 50;

    // TACZ GunTabType 序列化名（CommonGunIndex#getType）
    private static final String TYPE_PISTOL = "pistol";
    private static final String TYPE_SNIPER = "sniper";
    private static final String TYPE_RIFLE = "rifle";
    private static final String TYPE_SHOTGUN = "shotgun";
    private static final String TYPE_SMG = "smg";
    private static final String TYPE_RPG = "rpg";
    private static final String TYPE_MG = "mg";

    /** 枪械加成档位 */
    enum Tier {
        /** 每点 +1 固定伤害 */
        FLAT,
        /** 霰弹 / 大容量：每点 +10% */
        VOLUME,
        /** 狙击 / 单发重武器：每点 +50% */
        HEAVY;

        /** 对基础伤害应用本档位在给定技能等级下的加成 */
        float apply(float base, int level) {
            return switch (this) {
                case FLAT -> base + level * FLAT_PER_POINT;
                case VOLUME -> base * (1.0f + level * VOLUME_MULT_PER_POINT);
                case HEAVY -> base * (1.0f + level * HEAVY_MULT_PER_POINT);
            };
        }
    }

    private TaczGunEvents() {
    }

    static void register() {
        // 显式指定事件类型，避免依赖方法引用的泛型推断
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                EntityHurtByGunEvent.Pre.class, TaczGunEvents::onGunHurtPre);
    }

    private static void onGunHurtPre(EntityHurtByGunEvent.Pre event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) return;
        if (!(event.getAttacker() instanceof ServerPlayer player)) return;
        if (event.getHurtEntity() == null || event.getHurtEntity() == player) return;
        // 仅弹头直击（爆炸伤害不会携带子弹实体，也不会走本事件；此处为双重保险）
        if (event.getBullet() == null) return;
        int level = player.getData(ModAttachments.PLAYER_SKILLS).get(SkillType.FIREARMS.ordinal());
        if (level <= 0) return;
        Tier tier = tierOf(event.getGunId());
        event.setBaseAmount(tier.apply(event.getBaseAmount(), level));
    }

    /** 根据枪械 id 判定加成档位；未知枪械回退为常规 +1 */
    static Tier tierOf(ResourceLocation gunId) {
        if (gunId == null) return Tier.FLAT;
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (index == null) return Tier.FLAT;
        String type = index.getType();
        if (type == null) return Tier.FLAT;
        return switch (type) {
            case TYPE_SNIPER, TYPE_RPG -> Tier.HEAVY;
            case TYPE_SHOTGUN, TYPE_MG -> Tier.VOLUME;
            case TYPE_RIFLE -> isLargeMagazine(index.getGunData()) ? Tier.VOLUME : Tier.FLAT;
            case TYPE_PISTOL, TYPE_SMG -> Tier.FLAT;
            default -> Tier.FLAT;
        };
    }

    /** 步枪基础弹匣容量 ≥ 50 视为大容量（不计扩容弹匣配件） */
    private static boolean isLargeMagazine(GunData data) {
        return data != null && data.getAmmoAmount() >= LARGE_MAG_THRESHOLD;
    }
}

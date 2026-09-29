package com.zhushen.space.compat;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.util.AttachmentDataUtils;
import com.zhushen.space.common.CombatFormula;
import com.zhushen.space.common.DamageCap;
import com.zhushen.space.data.WeaponCategory;
import com.zhushen.space.common.DamageVariance;
import com.zhushen.space.common.GunDamage;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.entity.dismember.BodyPart;
import com.zhushen.space.entity.dismember.TVirusZombiePart;
import com.zhushen.space.data.SkillType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.common.NeoForge;

/**
 * TACZ 枪械联动事件（仅在 TACZ 已安装时由 {@link TaczCompat} 加载）。
 * <p>
 * 「枪械」技能按枪械类别走三条不同的加成曲线（{@link Tier}）：
 * <ul>
 *   <li>{@link Tier#REGULAR 常规}（手枪 / 冲锋枪 / 小弹匣步枪）：独立乘区，每点 +5%（5 点时 ×1.25）</li>
 *   <li>{@link Tier#VOLUME 霰弹 / 大容量}（霰弹枪、全部机枪、弹匣 ≥ 50 的步枪）：
 *       独立乘区，每点 +10%（5 点时 ×1.5）。霰弹枪每颗弹丸分别触发本事件，
 *       若仍用 +1 的算法会被弹丸数放大，故改为乘区。</li>
 *   <li>{@link Tier#HEAVY 狙击 / 单发重武器}（狙击枪、火箭筒）：独立乘区，每点 +20%（5 点时 ×2.0）；
 *       <b>爆头时</b>加成改为与爆头倍率相加（爆头倍率 + 0.2×等级），不再与爆头倍率相乘。</li>
 * </ul>
 * 技能乘区与操作暴击、感知弱点合计不超过 {@link DamageCap#capFor(int)}：0 点 ×1.5，每点 +1（见 {@link DamageCap}）。
 * 乘区只作用于弹头直击：{@link EntityHurtByGunEvent.Pre} 仅由子弹实体直接命中实体时触发，
 * 火箭筒等的爆炸伤害由 TACZ 的 ExplodeUtil 以原版爆炸结算，不经过本事件，因此天然不受乘区影响。
 * 本事件触发时距离衰减已结算，爆头倍率、护甲与穿甲等后续结算照常作用于加成后的伤害。
 * <p>
 * 爆头：所有 TACZ 爆头倍率超出 1 的部分减半（{@link #HEADSHOT_EXCESS_SCALE}）；
 * 命中 T 病毒丧尸的部位碰撞箱时，只有头部碰撞箱算爆头（测试功能：部位肢解）。
 */
final class TaczGunEvents {
    /** 常规枪械：每点乘区加成 */
    static final float REGULAR_MULT_PER_POINT = 0.05f;
    /** 霰弹 / 大容量枪械：每点乘区加成 */
    static final float VOLUME_MULT_PER_POINT = 0.10f;
    /** 狙击 / 单发重武器：每点乘区加成 */
    static final float HEAVY_MULT_PER_POINT = 0.20f;
    /** 爆头倍率超出 1 的部分的保留比例（0.5 = 减半） */
    static final float HEADSHOT_EXCESS_SCALE = 0.5f;
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
        /** 常规：每点 +5% */
        REGULAR,
        /** 霰弹 / 大容量：每点 +10% */
        VOLUME,
        /** 狙击 / 单发重武器：每点 +20% */
        HEAVY;

        /** 本档位在给定技能等级下的乘区加成（不含基础 1） */
        float bonus(int level) {
            return level * switch (this) {
                case REGULAR -> REGULAR_MULT_PER_POINT;
                case VOLUME -> VOLUME_MULT_PER_POINT;
                case HEAVY -> HEAVY_MULT_PER_POINT;
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
        // 全局：TACZ 爆头倍率超出 1 的部分减半（×2.5 → ×1.75），对所有射击者与目标生效
        float hs0 = event.getHeadshotMultiplier();
        if (hs0 > 1f) event.setHeadshotMultiplier(1f + (hs0 - 1f) * HEADSHOT_EXCESS_SCALE);
        // T 病毒丧尸部位碰撞箱：TACZ 按「部件自身的眼高」判定爆头会误判四肢，改为只有命中头部碰撞箱才算爆头
        if (event.getHurtEntity() instanceof TVirusZombiePart part) {
            event.setHeadshot(part.bodyPart() == BodyPart.HEAD);
        }
        if (!(event.getAttacker() instanceof ServerPlayer player)) return;
        if (event.getHurtEntity() == null || event.getHurtEntity() == player) return;
        // 仅弹头直击（爆炸伤害不会携带子弹实体，也不会走本事件；此处为双重保险）
        if (event.getBullet() == null) return;
        int level = player.getData(ModAttachments.PLAYER_SKILLS).get(SkillType.FIREARMS.ordinal());
        float factor = 1f;
        float cap = DamageCap.capFor(level);
        // 攻击判定：属性（敏捷 / 炮为智力）+ 枪械 + 武器伤害 − 目标防御 ± 调整（没有专业 −9）；
        // 散弹等多弹丸：属性 + 技能 − 防御 − 减值 按弹丸数平摊到每颗弹丸
        WeaponCategory cat = categoryOf(event.getGunId());
        int pellets = pellets(event.getGunId());
        float def = event.getHurtEntity() instanceof net.minecraft.world.entity.LivingEntity le ? le.getArmorValue() : 0f;
        float bonus = CombatFormula.attr(player, cat.attribute) + level - def - CombatFormula.professionPenalty(player, cat);
        event.setBaseAmount(Math.max(0f, event.getBaseAmount() + bonus / pellets));
        // 伤害浮动：面板伤害的 20%~100% 随机（每发子弹一次，先于爆头倍率与暴击 / 弱点）
        event.setBaseAmount(event.getBaseAmount() * DamageVariance.roll(player.getRandom()));
        // 开始本发子弹的结算上下文：暴击 / 弱点每发只判定一次，并与技能乘区合并封顶（C）
        GunDamage.beginHit(player, event.getHurtEntity(), factor, cap);
    }

    /**
     * 伤害面板用：手持 TACZ 枪械时的每次射击面板伤害（含配件 / 射击模式 / TACZ 基础倍率与枪械技能乘区，
     * 不含距离衰减、爆头、暴击与弱点）。
     *
     * @return {面板伤害, 弹丸数}；未手持枪械时返回 null
     */
    public static float[] panelDamage(ServerPlayer player, ItemStack stack) {
        try {
            IGun iGun = IGun.getIGunOrNull(stack);
            if (iGun == null) return null;
            ResourceLocation gunId = iGun.getGunId(stack);
            CommonGunIndex index = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
            if (index == null) return null;
            GunData data = index.getGunData();
            float base = (float) AttachmentDataUtils.getDamageWithAttachment(stack, data);
            int pellets = Math.max(1, data.getBulletData().getBulletAmount());
            int level = player.getData(ModAttachments.PLAYER_SKILLS).get(SkillType.FIREARMS.ordinal());
            WeaponCategory cat = categoryOf(gunId);
            float bonus = CombatFormula.attr(player, cat.attribute) + level - CombatFormula.professionPenalty(player, cat);
            return new float[]{Math.max(0f, base + bonus / pellets), pellets};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 根据枪械 id 判定加成档位；未知枪械回退为常规档 */
    static Tier tierOf(ResourceLocation gunId) {
        if (gunId == null) return Tier.REGULAR;
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (index == null) return Tier.REGULAR;
        String type = index.getType();
        if (type == null) return Tier.REGULAR;
        return switch (type) {
            case TYPE_SNIPER, TYPE_RPG -> Tier.HEAVY;
            case TYPE_SHOTGUN, TYPE_MG -> Tier.VOLUME;
            case TYPE_RIFLE -> isLargeMagazine(index.getGunData()) ? Tier.VOLUME : Tier.REGULAR;
            case TYPE_PISTOL, TYPE_SMG -> Tier.REGULAR;
            default -> Tier.REGULAR;
        };
    }

    /** 枪械分类：手枪 / 冲锋枪 / 散弹枪 / 机枪 / 步枪（含狙击）/ 炮（火箭筒）；未知类型按步枪 */
    static WeaponCategory categoryOf(ResourceLocation gunId) {
        if (gunId == null) return WeaponCategory.RIFLE;
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        String type = index == null ? null : index.getType();
        if (type == null) return WeaponCategory.RIFLE;
        return switch (type) {
            case TYPE_PISTOL -> WeaponCategory.PISTOL;
            case TYPE_SMG -> WeaponCategory.SMG;
            case TYPE_SHOTGUN -> WeaponCategory.SHOTGUN;
            case TYPE_MG -> WeaponCategory.MACHINE_GUN;
            case TYPE_RPG -> WeaponCategory.CANNON;
            default -> WeaponCategory.RIFLE; // rifle / sniper
        };
    }

    static WeaponCategory categoryOf(ItemStack stack) {
        try {
            IGun iGun = IGun.getIGunOrNull(stack);
            if (iGun == null) return null;
            return categoryOf(iGun.getGunId(stack));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int pellets(ResourceLocation gunId) {
        CommonGunIndex index = gunId == null ? null : TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (index == null) return 1;
        return Math.max(1, index.getGunData().getBulletData().getBulletAmount());
    }

    /** 步枪基础弹匣容量 ≥ 50 视为大容量（不计扩容弹匣配件） */
    private static boolean isLargeMagazine(GunData data) {
        return data != null && data.getAmmoAmount() >= LARGE_MAG_THRESHOLD;
    }
}

package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.DamageKind;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;

import java.util.Collection;

/**
 * 技艺 / 检定能力的伤害来源：每种伤害类型（DamageKind）对应一个注册的伤害类型
 * {@code zhushenspace:art_<key>}（data/zhushenspace/damage_type/），不再一律算作原版“间接魔法”。
 * <p>
 * 伤害数字颜色（DuMmmMmmy 木桩等按伤害类型标签着色）：
 * 火焰 → #dummmmmmy:is_fire（橙）、寒冷 → #dummmmmmy:is_cold（冰蓝）、闪电 → #minecraft:is_lightning（黄）、
 * 强酸 / 毒素 → #dummmmmmy:is_thorn（绿）、邪恶 → #dummmmmmy:is_wither（灰）；物理与其余类型为普通白色数字。
 * 这些标签只影响显示，伤害结算仍走 {@link DamageRules}。
 */
public final class ArtDamage {
    private ArtDamage() {}

    private static final String PREFIX = "art_";

    /** 多种类型同时存在时，用于显示 / 死亡信息的主类型优先级（元素性质优先于物理形态） */
    private static final DamageKind[] PRIORITY = {
            DamageKind.FIRE, DamageKind.COLD, DamageKind.LIGHTNING, DamageKind.ACID, DamageKind.TOXIN,
            DamageKind.UNHOLY, DamageKind.HOLY, DamageKind.SONIC, DamageKind.LIGHT, DamageKind.PSYCHIC,
            DamageKind.FORCE, DamageKind.PURE_ENERGY, DamageKind.PIERCE, DamageKind.SLASH, DamageKind.BLUNT};

    public static ResourceKey<DamageType> key(DamageKind k) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, PREFIX + k.key));
    }

    public static DamageKind primary(Collection<DamageKind> kinds) {
        for (DamageKind k : PRIORITY) if (kinds.contains(k)) return k;
        return DamageKind.BLUNT;
    }

    /** direct = 直接造成伤害的实体（弹体 / 施法者本人），attacker = 施法者 */
    public static DamageSource source(Entity direct, Entity attacker, DamageKind k) {
        Entity ref = attacker != null ? attacker : direct;
        return new DamageSource(ref.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(key(k)),
                direct, attacker);
    }

    public static DamageSource source(Entity direct, Entity attacker, Collection<DamageKind> kinds) {
        return source(direct, attacker, primary(kinds));
    }

    public static DamageSource source(Entity direct, Entity attacker, DamageRules.Spec spec) {
        return source(direct, attacker, spec == null ? java.util.Set.of(DamageKind.BLUNT) : spec.kinds());
    }

    /** 由技艺伤害类型反查 DamageKind（非技艺伤害返回 null） */
    public static DamageKind kindOf(DamageSource src) {
        var key = src.typeHolder().unwrapKey().orElse(null);
        if (key == null) return null;
        ResourceLocation id = key.location();
        if (!ZhuShenSpace.MODID.equals(id.getNamespace()) || !id.getPath().startsWith(PREFIX)) return null;
        String k = id.getPath().substring(PREFIX.length());
        for (DamageKind d : DamageKind.values()) if (d.key.equals(k)) return d;
        return null;
    }
}

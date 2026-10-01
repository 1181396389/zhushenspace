package com.zhushen.space.common;

import com.zhushen.space.data.ArtSkill;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 技艺伤害的归属：施放期间（同步结算）记为「正在施放」，之后的弹体 / 持续伤害按施法者最近一次施放的技艺归属
 * （窗口 {@link #WINDOW} 刻）。用于魔虚罗适应等「按现象区分」的能力：技艺伤害 = 该技艺这一现象。
 */
public final class ArtContext {
    private ArtContext() {}

    /** 最近施放归属窗口：15 秒 */
    public static final long WINDOW = 300;

    private record Cast(ArtSkill art, long tick) {}

    private static final Map<UUID, Cast> LAST = new HashMap<>();
    private static ArtSkill current;
    private static UUID currentCaster;

    static void begin(Entity caster, ArtSkill s) {
        current = s;
        currentCaster = caster.getUUID();
        LAST.put(caster.getUUID(), new Cast(s, caster.level().getGameTime()));
    }

    static void end() {
        current = null;
        currentCaster = null;
    }

    /** 该实体（施法者）此刻造成的技艺伤害归属的技艺（null = 无法归属） */
    public static ArtSkill of(Entity caster) {
        if (caster == null) return null;
        if (current != null && caster.getUUID().equals(currentCaster)) return current;
        Cast c = LAST.get(caster.getUUID());
        if (c == null) return null;
        if (caster.level().getGameTime() - c.tick > WINDOW) return null;
        return c.art;
    }

    public static void forget(UUID id) { LAST.remove(id); }
}

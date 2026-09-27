package com.zhushen.space.compat;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.SkillType;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.common.NeoForge;

/**
 * TACZ 枪械联动事件（仅在 TACZ 已安装时由 {@link TaczCompat} 加载）。
 * <p>
 * 「枪械」技能：每点使玩家发射的 TACZ 子弹基础伤害 +1。
 * 在 {@link EntityHurtByGunEvent.Pre} 中修改基础伤害：此时距离衰减已结算，
 * 爆头倍率、护甲与穿甲等后续结算照常作用于加成后的伤害；霰弹枪每颗弹丸分别计算。
 */
final class TaczGunEvents {
    /** 每点枪械技能提供的子弹基础伤害 */
    static final float DAMAGE_PER_POINT = 1.0f;

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
        int level = player.getData(ModAttachments.PLAYER_SKILLS).get(SkillType.FIREARMS.ordinal());
        if (level <= 0) return;
        event.setBaseAmount(event.getBaseAmount() + level * DAMAGE_PER_POINT);
    }
}

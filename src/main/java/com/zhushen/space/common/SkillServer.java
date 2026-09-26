package com.zhushen.space.common;

import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerSkillData;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.network.SyncSkillsPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 技能系统服务端逻辑（加点提交 / 战斗预设保存 / 数据同步）。
 */
public class SkillServer {

    /** 提交技能加点方案（含校验：范围 0~5、总消耗不超过总技能点数） */
    public static void commitSkillAllocation(ServerPlayer player, int[] target) {
        if (!SkillType.isValid(target)) return;
        PlayerSkillData data = player.getData(ModAttachments.PLAYER_SKILLS);
        if (SkillType.totalCost(target) > data.totalSkillPoints()) return;
        // 已确认的加点不可退回
        int[] saved = data.points();
        for (int i = 0; i < SkillType.COUNT; i++) {
            if (target[i] < saved[i]) return;
        }

        data.setPoints(target);
        data.pruneSlots(); // 技能点下降时移除不再解锁的预设
        sync(player);
    }

    /** 保存战斗预设栏（校验：能力合法、不重复且已解锁） */
    public static void equipPreset(ServerPlayer player, int bar, int[] slots) {
        if (bar < 0 || bar >= PlayerSkillData.BAR_COUNT) return;
        if (!SkillAbility.isValidSlots(slots)) return;
        PlayerSkillData data = player.getData(ModAttachments.PLAYER_SKILLS);
        int[] checked = new int[9];
        System.arraycopy(slots, 0, checked, 0, 9);
        for (int i = 0; i < 9; i++) {
            int s = checked[i];
            if (s >= 0) {
                SkillAbility ability = SkillAbility.values()[s];
                if (ability.isNeiliAbility()) {
                    // 内力系：需拥有内力池
                    if (player.getData(ModAttachments.PLAYER_ENERGY)
                            .getPool(EnergyManager.POOL_NEILI) == null) {
                        checked[i] = -1;
                    }
                } else if (ability.isSchoolAbility()) {
                    // 流派系：需已购买该技能且装备流派饰品
                    if (!TaiChiManager.isSkillUsable(player, ability)) {
                        checked[i] = -1;
                    }
                } else if (data.get(ability.owner().ordinal()) < ability.requiredLevel()) {
                    checked[i] = -1;
                }
            }
        }
        data.setBar(bar, checked);
        sync(player);
    }

    /** 同步技能数据到客户端（含各主动技能剩余冷却与进行中的 buff 状态） */
    public static void sync(ServerPlayer player) {
        PlayerSkillData data = player.getData(ModAttachments.PLAYER_SKILLS);
        int[] active = SkillManager.activeStateTicks(player);
        PacketDistributor.sendToPlayer(player, new SyncSkillsPayload(
                data.points(), data.totalSkillPoints(), data.bars(),
                SkillManager.remainingCooldowns(player), active[0], active[1]));
    }
}

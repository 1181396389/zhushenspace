package com.zhushen.space.common;

import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerAttributeData;
import com.zhushen.space.data.PlayerSkillData;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.network.SyncAttributesPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 属性系统服务端逻辑。
 */
public class AttributeServer {

    /** 提交加点方案（含校验：范围 0~5、总消耗不超过总点数） */
    public static void commitAllocation(ServerPlayer player, int[] target) {
        if (!AttributeType.isValid(target)) return;
        PlayerAttributeData data = player.getData(ModAttachments.PLAYER_ATTRIBUTES);
        if (AttributeType.totalCost(target) > data.totalPoints()) return;

        data.setPoints(target);
        AttributeApplier.apply(player);
        // 决心/沉着达到 5 点时立即重算主能量池上限（否则需重登录才生效）
        EnergyManager.syncLegendaryPools(player);
        sync(player);
    }

    /** 使用信封后发放自由点数与技能点数（每位玩家仅首次信封发放，防止重复刷点） */
    public static void grantPoints(ServerPlayer player, int amount) {
        PlayerAttributeData data = player.getData(ModAttachments.PLAYER_ATTRIBUTES);
        if (data.envelopeUsed()) return;
        data.markEnvelopeUsed();
        data.addTotalPoints(amount);

        // 同一封邀请函同时发放技能点数
        PlayerSkillData skills = player.getData(ModAttachments.PLAYER_SKILLS);
        if (!skills.envelopeUsed()) {
            skills.markEnvelopeUsed();
            skills.addTotalPoints(SkillType.ENVELOPE_SKILL_POINTS);
        }

        sync(player);
        SkillServer.sync(player);
    }

    /** 同步属性数据到客户端 */
    public static void sync(ServerPlayer player) {
        PlayerAttributeData data = player.getData(ModAttachments.PLAYER_ATTRIBUTES);
        PacketDistributor.sendToPlayer(player,
                new SyncAttributesPayload(data.points(), data.totalPoints()));
    }

    /** 应用属性加成并同步（登录/重生/换维度时调用） */
    public static void applyAndSync(ServerPlayer player) {
        AttributeApplier.apply(player);
        EnergyManager.syncLegendaryPools(player); // 传奇加成接入能量池（决心/沉着满5点）
        sync(player);
        SkillServer.sync(player);
        ProgressManager.sync(player);
    }
}

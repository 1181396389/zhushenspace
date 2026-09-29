package com.zhushen.space.common;

import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.BuildCheck;
import com.zhushen.space.data.BuildRules;
import com.zhushen.space.data.FeatType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerAttributeData;
import com.zhushen.space.data.PlayerBuildData;
import com.zhushen.space.data.PlayerSkillData;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.network.SyncBuildPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** 建卡 XP 系统服务端逻辑：提交校验、邀请函发放、旧存档迁移、同步 */
public final class BuildServer {
    private BuildServer() {}

    public static void commit(ServerPlayer player, int[] attrXp, int[] skills, int[] featMask, int si1, int si3a, int si3b) {
        PlayerBuildData b = player.getData(ModAttachments.PLAYER_BUILD);
        PlayerSkillData sd = player.getData(ModAttachments.PLAYER_SKILLS);
        PlayerAttributeData ad = player.getData(ModAttachments.PLAYER_ATTRIBUTES);
        if (!ad.envelopeUsed()) return;
        BuildCheck c = BuildCheck.of(b.totalXp, b.created, b.giftedSkillXp,
                b.attrXp, sd.points(), b.featMask, b.si1Skill, b.si3Skills[0], b.si3Skills[1],
                attrXp, skills, featMask, si1, si3a, si3b);
        if (!c.ok()) {
            Object[] e = c.errors.get(0);
            Object[] args = new Object[e.length - 1];
            System.arraycopy(e, 1, args, 0, args.length);
            player.displayClientMessage(Component.translatable((String) e[0], args), true);
            sync(player);
            SkillServer.sync(player);
            AttributeServer.sync(player);
            return;
        }
        b.attrXp = attrXp.clone();
        b.featMask = featMask.clone();
        int[] lv = skills.clone();
        boolean first = !b.created;
        if (first) {
            b.si1Skill = si1;
            b.si3Skills = new int[]{si3a, si3b};
            b.created = true;
            // 特殊身份 3：建卡完成后指定技能立即 +1（最多 4 级），赠送的等级不占 XP
            int disc = b.has(FeatType.SPECIAL_IDENTITY, 1) ? si1 : -1;
            for (int s : b.si3Skills) {
                if (s >= 0 && lv[s] < BuildRules.CREATION_SKILL_CAP) {
                    b.giftedSkillXp += BuildRules.skillStep(lv[s], s == disc);
                    lv[s]++;
                }
            }
            // 特殊身份每个等级：可携带 1 件 ≤500 分的物品（待对接物品系统）；超凡身份：待兑换
            int items = 0;
            for (int l = 1; l <= 3; l++) if (b.has(FeatType.SPECIAL_IDENTITY, l)) items++;
            b.pendingItems += items;
            b.pendingExchange = b.has(FeatType.SUPERNATURAL_IDENTITY, 5);
        }
        applyAttributes(player, b);
        sd.setPoints(lv);
        sd.pruneSlots();
        if (first) BuildHooks.fireCreated(player, b);
        BuildHooks.fireCommitted(player, b);
        syncAll(player);
    }

    private static void applyAttributes(ServerPlayer player, PlayerBuildData b) {
        int[] pts = new int[AttributeType.COUNT];
        for (int i = 0; i < pts.length; i++) pts[i] = BuildRules.attrLevel(b.attrXp[i]);
        player.getData(ModAttachments.PLAYER_ATTRIBUTES).setPoints(pts);
        AttributeApplier.apply(player);
        EnergyManager.syncLegendaryPools(player);
        HealthManager.sync(player);
    }

    /** 首次使用邀请函：发放 70 XP */
    public static void grantEnvelope(ServerPlayer player) {
        PlayerBuildData b = player.getData(ModAttachments.PLAYER_BUILD);
        b.totalXp += BuildRules.TOTAL_XP;
        sync(player);
    }

    public static void addXp(ServerPlayer player, int amount) {
        PlayerBuildData b = player.getData(ModAttachments.PLAYER_BUILD);
        b.totalXp = Math.max(0, b.totalXp + amount);
        sync(player);
    }

    /** 重置为未建卡状态：属性回到 1、技能清零、专长清空；已用过邀请函则 XP 恢复为 70 */
    public static void reset(ServerPlayer player) {
        PlayerBuildData b = player.getData(ModAttachments.PLAYER_BUILD);
        PlayerAttributeData ad = player.getData(ModAttachments.PLAYER_ATTRIBUTES);
        b.version = PlayerBuildData.VERSION;
        b.totalXp = ad.envelopeUsed() ? BuildRules.TOTAL_XP : 0;
        b.created = false;
        b.attrXp = new int[AttributeType.COUNT];
        b.featMask = new int[FeatType.COUNT];
        b.si1Skill = -1;
        b.si3Skills = new int[]{-1, -1};
        b.giftedSkillXp = 0;
        b.pendingItems = 0;
        b.pendingExchange = false;
        PlayerSkillData sd = player.getData(ModAttachments.PLAYER_SKILLS);
        sd.setPoints(new int[SkillType.COUNT]);
        sd.clearProfessions();
        sd.pruneSlots();
        applyAttributes(player, b);
        syncAll(player);
    }

    /** 旧存档迁移（登录时）：切换到 XP 建卡系统，旧的属性 / 技能分配清空，重新建卡 */
    public static void migrate(ServerPlayer player) {
        PlayerBuildData b = player.getData(ModAttachments.PLAYER_BUILD);
        if (b.version >= PlayerBuildData.VERSION) return;
        reset(player);
    }

    public static void sync(ServerPlayer player) {
        PlayerBuildData b = player.getData(ModAttachments.PLAYER_BUILD);
        PacketDistributor.sendToPlayer(player, new SyncBuildPayload(b.totalXp, b.created, b.attrXp, b.featMask,
                b.si1Skill, b.si3Skills[0], b.si3Skills[1], b.giftedSkillXp, b.pendingItems, b.pendingExchange));
    }

    public static void syncAll(ServerPlayer player) {
        sync(player);
        AttributeServer.sync(player);
        SkillServer.sync(player);
    }
}

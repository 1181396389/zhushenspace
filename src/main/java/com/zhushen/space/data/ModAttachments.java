package com.zhushen.space.data;

import com.zhushen.space.ZhuShenSpace;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ZhuShenSpace.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerAttributeData>> PLAYER_ATTRIBUTES =
            ATTACHMENTS.register("player_attributes",
                    () -> AttachmentType.serializable(PlayerAttributeData::new).copyOnDeath().build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerSkillData>> PLAYER_SKILLS =
            ATTACHMENTS.register("player_skills",
                    () -> AttachmentType.serializable(PlayerSkillData::new).copyOnDeath().build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerBuildData>> PLAYER_BUILD =
            ATTACHMENTS.register("player_build",
                    () -> AttachmentType.serializable(PlayerBuildData::new).copyOnDeath().build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerEnergyData>> PLAYER_ENERGY =
            ATTACHMENTS.register("player_energy",
                    () -> AttachmentType.serializable(PlayerEnergyData::new).copyOnDeath().build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerCurrencyData>> PLAYER_CURRENCY =
            ATTACHMENTS.register("player_currency",
                    () -> AttachmentType.serializable(PlayerCurrencyData::new).copyOnDeath().build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerSchoolData>> PLAYER_SCHOOLS =
            ATTACHMENTS.register("player_schools",
                    () -> AttachmentType.serializable(PlayerSchoolData::new).copyOnDeath().build());

    /** B/L/A 伤势池：死亡不保留（重生即完好之躯） */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerHealthData>> PLAYER_HEALTH =
            ATTACHMENTS.register("player_health",
                    () -> AttachmentType.serializable(PlayerHealthData::new).build());

    /** 肢体血量与断肢：死亡不保留 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerLimbData>> PLAYER_LIMBS =
            ATTACHMENTS.register("player_limbs",
                    () -> AttachmentType.serializable(PlayerLimbData::new).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerArtData>> PLAYER_ARTS =
            ATTACHMENTS.register("player_arts",
                    () -> AttachmentType.serializable(PlayerArtData::new).copyOnDeath().build());

    /** 身体状况（水分 / 体力 / 精力、不良状态点数、倒地）：死亡不保留 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerConditionData>> PLAYER_CONDITION =
            ATTACHMENTS.register("player_condition",
                    () -> AttachmentType.serializable(PlayerConditionData::new).build());

    /** 上一次长休的世界时间（主世界 gameTime；Long.MIN_VALUE = 从未长休）：死亡保留，防止死亡刷新长休 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> LAST_LONG_REST =
            ATTACHMENTS.register("last_long_rest",
                    () -> AttachmentType.builder(() -> Long.MIN_VALUE)
                            .serialize(com.mojang.serialization.Codec.LONG).copyOnDeath().build());

    /** 大厅返回点：持久化，服务器重启后仍能返回进入大厅前的位置 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<HallReturnData>> HALL_RETURN =
            ATTACHMENTS.register("hall_return",
                    () -> AttachmentType.serializable(HallReturnData::new).copyOnDeath().build());

    /** 新手试炼：进度、记录与进入前的存档备份（持久化、死亡保留） */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<TrialData>> TRIAL =
            ATTACHMENTS.register("trial",
                    () -> AttachmentType.serializable(TrialData::new).copyOnDeath().build());

    /** 装备位穿脱状态：死亡不保留 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerGearData>> PLAYER_GEAR =
            ATTACHMENTS.register("player_gear",
                    () -> AttachmentType.serializable(PlayerGearData::new).build());
}

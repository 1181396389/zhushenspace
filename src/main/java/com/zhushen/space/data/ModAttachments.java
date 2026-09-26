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
}

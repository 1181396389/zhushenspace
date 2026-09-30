package com.zhushen.space.sound;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.SOUND_EVENT, "zhushenspace");

    // 信封打开音效
    public static final DeferredHolder<SoundEvent, SoundEvent> ENVELOPE_OPEN =
            SOUND_EVENTS.register("envelope_open", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath("zhushenspace", "envelope_open")));

    // 故障/闪烁音效（No变Yes时）
    public static final DeferredHolder<SoundEvent, SoundEvent> GLITCH =
            SOUND_EVENTS.register("glitch", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath("zhushenspace", "glitch")));

    // 弱点勘破命中音效（感知属性命中黄色光点时）
    public static final DeferredHolder<SoundEvent, SoundEvent> WEAK_POINT_HIT =
            SOUND_EVENTS.register("weak_point_hit", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath("zhushenspace", "weak_point_hit")));

    // 太极拳：出招风声（八式通用，扫击 + 撞击复合）
    public static final DeferredHolder<SoundEvent, SoundEvent> TAI_CHI_MOVE =
            SOUND_EVENTS.register("tai_chi_move", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath("zhushenspace", "tai_chi_move")));

    // 太极拳：听劲启动（虚无缥缈的气场）
    public static final DeferredHolder<SoundEvent, SoundEvent> TAI_CHI_TINGJIN =
            SOUND_EVENTS.register("tai_chi_tingjin", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath("zhushenspace", "tai_chi_tingjin")));

    // 太极拳：靠·撞墙钝击
    public static final DeferredHolder<SoundEvent, SoundEvent> TAI_CHI_WALL =
            SOUND_EVENTS.register("tai_chi_wall", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath("zhushenspace", "tai_chi_wall")));

    // 内力：水晶磬音（吐息开关 / 能量流转）
    public static final DeferredHolder<SoundEvent, SoundEvent> NEILI_CHIME =
            SOUND_EVENTS.register("neili_chime", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath("zhushenspace", "neili_chime")));

    // 打坐开始（钟磬共鸣，气沉丹田）
    public static final DeferredHolder<SoundEvent, SoundEvent> NEILI_MEDITATE_START =
            SOUND_EVENTS.register("neili_meditate_start", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath("zhushenspace", "neili_meditate_start")));

    // 打坐完成（内力尽复）
    public static final DeferredHolder<SoundEvent, SoundEvent> NEILI_MEDITATE_DONE =
            SOUND_EVENTS.register("neili_meditate_done", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath("zhushenspace", "neili_meditate_done")));

    // ===== 技艺（19 个 ArtSkill）分层音效：每个能量池一组「起手 / 释放 / 命中 / 重击」 =====

    /** 能量池音效组 */
    public record ArtSfx(DeferredHolder<SoundEvent, SoundEvent> cast,
                         DeferredHolder<SoundEvent, SoundEvent> release,
                         DeferredHolder<SoundEvent, SoundEvent> hit,
                         DeferredHolder<SoundEvent, SoundEvent> impact) {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> art(String name) {
        return SOUND_EVENTS.register("art_" + name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath("zhushenspace", "art_" + name)));
    }

    // 灵力：清越空灵
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_SPIRIT_CAST = art("spirit_cast");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_SPIRIT_RELEASE = art("spirit_release");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_SPIRIT_HIT = art("spirit_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_SPIRIT_IMPACT = art("spirit_impact");
    // 精神力：低频脉冲 + 耳鸣
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_MIND_CAST = art("mind_cast");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_MIND_RELEASE = art("mind_release");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_MIND_HIT = art("mind_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_MIND_IMPACT = art("mind_impact");
    // 灵能：电弧噼啪 + 嗡鸣
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_PSYCHIC_CAST = art("psychic_cast");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_PSYCHIC_RELEASE = art("psychic_release");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_PSYCHIC_HIT = art("psychic_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_PSYCHIC_IMPACT = art("psychic_impact");
    // 妖力：妖气嘶鸣
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_YOKAI_CAST = art("yokai_cast");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_YOKAI_RELEASE = art("yokai_release");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_YOKAI_HIT = art("yokai_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_YOKAI_IMPACT = art("yokai_impact");
    // 佛法：梵钟 + 诵经
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_BUDDHA_CAST = art("buddha_cast");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_BUDDHA_RELEASE = art("buddha_release");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_BUDDHA_HIT = art("buddha_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_BUDDHA_IMPACT = art("buddha_impact");
    // 魔法：符文共振
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_MAGIC_CAST = art("magic_cast");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_MAGIC_RELEASE = art("magic_release");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_MAGIC_HIT = art("magic_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_MAGIC_IMPACT = art("magic_impact");
    // 道术：玉磬 + 风铃
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_DAO_CAST = art("dao_cast");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_DAO_RELEASE = art("dao_release");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_DAO_HIT = art("dao_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_DAO_IMPACT = art("dao_impact");
    // 内力：掌风
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_NEILI_CAST = art("neili_cast");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_NEILI_RELEASE = art("neili_release");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_NEILI_HIT = art("neili_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_NEILI_IMPACT = art("neili_impact");
    // 查克拉：火遁
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_CHAKRA_CAST = art("chakra_cast");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_CHAKRA_RELEASE = art("chakra_release");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_CHAKRA_HIT = art("chakra_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> ART_CHAKRA_IMPACT = art("chakra_impact");

    /** 按能量池取音效组 */
    public static ArtSfx artSfx(com.zhushen.space.common.FeatEffects.Pool pool) {
        if (pool == null) return NEILI_SFX;
        return switch (pool) {
            case SPIRIT -> SPIRIT_SFX;
            case MIND -> MIND_SFX;
            case PSYCHIC -> PSYCHIC_SFX;
            case YOKAI -> YOKAI_SFX;
            case BUDDHA -> BUDDHA_SFX;
            case MAGIC -> MAGIC_SFX;
            case DAO -> DAO_SFX;
            case NEILI -> NEILI_SFX;
            case CHAKRA -> CHAKRA_SFX;
        };
    }

    private static final ArtSfx SPIRIT_SFX = new ArtSfx(ART_SPIRIT_CAST, ART_SPIRIT_RELEASE, ART_SPIRIT_HIT, ART_SPIRIT_IMPACT);
    private static final ArtSfx MIND_SFX = new ArtSfx(ART_MIND_CAST, ART_MIND_RELEASE, ART_MIND_HIT, ART_MIND_IMPACT);
    private static final ArtSfx PSYCHIC_SFX = new ArtSfx(ART_PSYCHIC_CAST, ART_PSYCHIC_RELEASE, ART_PSYCHIC_HIT, ART_PSYCHIC_IMPACT);
    private static final ArtSfx YOKAI_SFX = new ArtSfx(ART_YOKAI_CAST, ART_YOKAI_RELEASE, ART_YOKAI_HIT, ART_YOKAI_IMPACT);
    private static final ArtSfx BUDDHA_SFX = new ArtSfx(ART_BUDDHA_CAST, ART_BUDDHA_RELEASE, ART_BUDDHA_HIT, ART_BUDDHA_IMPACT);
    private static final ArtSfx MAGIC_SFX = new ArtSfx(ART_MAGIC_CAST, ART_MAGIC_RELEASE, ART_MAGIC_HIT, ART_MAGIC_IMPACT);
    private static final ArtSfx DAO_SFX = new ArtSfx(ART_DAO_CAST, ART_DAO_RELEASE, ART_DAO_HIT, ART_DAO_IMPACT);
    private static final ArtSfx NEILI_SFX = new ArtSfx(ART_NEILI_CAST, ART_NEILI_RELEASE, ART_NEILI_HIT, ART_NEILI_IMPACT);
    private static final ArtSfx CHAKRA_SFX = new ArtSfx(ART_CHAKRA_CAST, ART_CHAKRA_RELEASE, ART_CHAKRA_HIT, ART_CHAKRA_IMPACT);

    // 注册方法
    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }
}

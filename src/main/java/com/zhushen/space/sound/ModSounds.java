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

    // 注册方法
    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }
}

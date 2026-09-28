package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.client.ClientAttributeData;
import com.zhushen.space.client.ClientCameraShake;
import com.zhushen.space.client.ClientEnergyData;
import com.zhushen.space.client.ClientHealthData;
import com.zhushen.space.client.ClientProgressData;
import com.zhushen.space.client.ClientSkillData;
import com.zhushen.space.common.AttributeServer;
import com.zhushen.space.common.EnergyManager;
import com.zhushen.space.common.HallManager;
import com.zhushen.space.common.ProgressManager;
import com.zhushen.space.common.SkillManager;
import com.zhushen.space.common.SkillServer;
import com.zhushen.space.data.SchoolType;
import com.zhushen.space.data.SkillAbility;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public class ModNetworking {

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("5"); // 协议版本：新增伤害面板同步（伤害浮动区间 HUD）
        registrar.playToClient(SyncAttributesPayload.TYPE, SyncAttributesPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        ClientAttributeData.update(payload.points(), payload.totalPoints())));
        registrar.playToServer(CommitAllocationPayload.TYPE, CommitAllocationPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer serverPlayer) {
                        AttributeServer.commitAllocation(serverPlayer, payload.points());
                    }
                }));

        // ===== 技能系统 =====
        registrar.playToClient(SyncSkillsPayload.TYPE, SyncSkillsPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    ClientSkillData.update(payload.points(), payload.totalSkillPoints(),
                            payload.bars(), payload.cooldownTicks());
                    ClientSkillData.updateTransient(payload.leapRemainTicks(), payload.climbRemainTicks());
                }));
        registrar.playToServer(CommitSkillAllocationPayload.TYPE, CommitSkillAllocationPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer serverPlayer) {
                        SkillServer.commitSkillAllocation(serverPlayer, payload.points());
                    }
                }));
        registrar.playToServer(EquipSkillsPayload.TYPE, EquipSkillsPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer serverPlayer) {
                        SkillServer.equipPreset(serverPlayer, payload.bar(), payload.slots());
                    }
                }));
        registrar.playToServer(UseSkillPayload.TYPE, UseSkillPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer serverPlayer) {
                        SkillManager.useSkill(serverPlayer, payload.bar(), payload.slot());
                    }
                }));

        // ===== 伤害浮动区间（战斗模式 HUD） =====
        registrar.playToClient(DamagePanelPayload.TYPE, DamagePanelPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        com.zhushen.space.client.ClientDamagePanel.update(payload.kind(), payload.panel(), payload.pellets())));

        // ===== B/L/A 伤势池 =====
        registrar.playToClient(SyncHealthPayload.TYPE, SyncHealthPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        ClientHealthData.update(payload.b(), payload.l(), payload.a(), payload.maxHp())));

        // ===== 能量池 =====
        registrar.playToClient(SyncEnergyPayload.TYPE, SyncEnergyPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        ClientEnergyData.update(payload.ids(), payload.currents(), payload.maxes(),
                                payload.colors(), payload.breathEnabled())));
        // 命中打击感：本地玩家攻击命中 / 撞墙结算时镜头微震
        registrar.playToClient(HitFeedbackPayload.TYPE, HitFeedbackPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        ClientCameraShake.trigger(payload.power(), payload.ticks())));

        registrar.playToClient(MeditatePosePayload.TYPE, MeditatePosePayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        com.zhushen.space.client.ClientMeditation.handle(payload.entityId(), payload.ticks())));

        // ===== 主神空间进度（货币 / 流派） =====
        registrar.playToClient(SyncProgressPayload.TYPE, SyncProgressPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        ClientProgressData.update(payload.branches(), payload.score(),
                                payload.schools(), payload.schoolSkillBits())));
        // 货币界面：支线拖拽兑换（拼合/拆分）
        registrar.playToServer(BranchExchangePayload.TYPE, BranchExchangePayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer serverPlayer) {
                        ProgressManager.exchange(serverPlayer, payload.fromTier(), payload.toTier());
                    }
                }));
        // 主神商城：购买流派
        registrar.playToServer(SchoolPurchasePayload.TYPE, SchoolPurchasePayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer serverPlayer) {
                        int ord = payload.school();
                        if (ord >= 0 && ord < SchoolType.COUNT) {
                            String err = ProgressManager.purchase(serverPlayer, SchoolType.values()[ord]);
                            if (err != null) {
                                serverPlayer.displayClientMessage(
                                        net.minecraft.network.chat.Component.translatable(err), true);
                            } else {
                                serverPlayer.displayClientMessage(
                                        net.minecraft.network.chat.Component.translatable(
                                                "msg.zhushenspace.school.purchased",
                                                net.minecraft.network.chat.Component.translatable(
                                                        SchoolType.values()[ord].nameKey())), false);
                            }
                        }
                    }
                }));
        // 主神商城：购买流派技能（如太极拳八式）
        registrar.playToServer(SchoolSkillPurchasePayload.TYPE, SchoolSkillPurchasePayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer serverPlayer) {
                        String err = ProgressManager.purchaseSchoolSkill(
                                serverPlayer, payload.school(), payload.ability());
                        if (err != null) {
                            serverPlayer.displayClientMessage(
                                    net.minecraft.network.chat.Component.translatable(err), true);
                        } else if (payload.ability() >= 0 && payload.ability() < SkillAbility.COUNT) {
                            serverPlayer.displayClientMessage(
                                    net.minecraft.network.chat.Component.translatable(
                                            "msg.zhushenspace.school.skill_purchased",
                                            net.minecraft.network.chat.Component.translatable(
                                                    SkillAbility.values()[payload.ability()].nameKey())), true);
                        }
                    }
                }));
        // 主神商城：领取流派饰品（免费补发）
        registrar.playToServer(SchoolClaimPayload.TYPE, SchoolClaimPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer serverPlayer) {
                        if (!ProgressManager.claimEmblem(serverPlayer, payload.school())) {
                            serverPlayer.displayClientMessage(
                                    net.minecraft.network.chat.Component.translatable(
                                            "msg.zhushenspace.school.claim_fail"), true);
                        }
                    }
                }));
        // 主神空间大厅：进入 / 返回主世界
        registrar.playToServer(EnterHallPayload.TYPE, EnterHallPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer serverPlayer) {
                        HallManager.enter(serverPlayer);
                    }
                }));
    }
}

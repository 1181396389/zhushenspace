package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.entity.HallBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import com.zhushen.space.data.ModAttachments;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;


/**
 * 主神空间大厅（zhushenspace:hallspace）——白色虚空平面安全区。
 * <ul>
 *   <li>主神面板按钮触发 {@link #enter}：不在大厅 → 记录返回点并传送进大厅；
 *       已在大厅 → 返回进入前记录的主世界位置（无记录则回主世界出生点）</li>
 *   <li>场景由 {@link HallBuilder#ensureBuilt} 构建：外部建筑文件（config 目录）→
 *       内置结构 → 极简风格，三级回退</li>
 *   <li>安全区：大厅维度内取消一切伤害</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public class HallManager {

    /** 主神空间大厅维度 */
    public static final ResourceKey<Level> HALL_DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "hallspace"));

    /** 大厅入口点 */
    public static final BlockPos HALL_SPAWN = new BlockPos(1, 1, 7);


    /** 主神面板按钮：进入大厅 / 返回主世界 */
    public static void enter(ServerPlayer player) {
        ServerLevel target;
        BlockPos pos;
        float yaw, pitch;
        if (player.level().dimension() == HALL_DIMENSION) {
            // 已在大厅 → 返回主世界
            GlobalPos ret = player.getData(ModAttachments.HALL_RETURN).take();
            if (ret == null) {
                ServerLevel overworld = player.server.overworld();
                BlockPos sp = overworld.getSharedSpawnPos();
                ret = GlobalPos.of(overworld.dimension(), sp);
            }
            target = player.server.getLevel(ret.dimension());
            if (target == null) target = player.server.overworld();
            pos = ret.pos();
            // 找到落点上方两个空气位
            pos = findSafeY(target, pos);
            yaw = player.getYRot();
            pitch = player.getXRot();
            player.displayClientMessage(Component.translatable("msg.zhushenspace.hall.returned"), true);
        } else {
            // 主世界 → 进入大厅（维度存在时才记录返回点）
            target = player.server.getLevel(HALL_DIMENSION);
            if (target == null) {
                player.displayClientMessage(Component.translatable("msg.zhushenspace.hall.missing"), true);
                return;
            }
            player.getData(ModAttachments.HALL_RETURN).set(
                    GlobalPos.of(player.level().dimension(), player.blockPosition()));
            HallBuilder.ensureBuilt(target);
            pos = findSafeY(target, HALL_SPAWN);
            yaw = 180.0F; // 面朝北
            pitch = 0.0F;
            player.displayClientMessage(Component.translatable("msg.zhushenspace.hall.entered"), true);
        }
        player.teleportTo(target, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, pitch);
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0.0F;
        target.playSound(null, pos, SoundEvents.PLAYER_TELEPORT, SoundSource.MASTER, 0.8F, 1.4F);
    }

    /** 从给定位置向上找到首个可站立位置（平台面） */
    private static BlockPos findSafeY(ServerLevel level, BlockPos pos) {
        BlockPos cursor = pos;
        for (int i = 0; i < 32; i++) {
            if (!level.getBlockState(cursor).isSolid() && !level.getBlockState(cursor.above()).isSolid()) {
                return cursor;
            }
            cursor = cursor.above();
        }
        return pos.above(2);
    }

    /**
     * 安全区：大厅维度内伤害归零（Pre 不可取消，直接把伤害设为 0）。
     * 最低优先级：必须在太极徒手加成/八劲/内力吐息等增伤监听之后执行，否则归零后又被加回伤害。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent.Pre event) {
        DamageSource src = event.getSource();
        LivingEntity target = event.getEntity();
        if (target.level() instanceof ServerLevel level
                && level.dimension() == HALL_DIMENSION
                && src.getEntity() != target
                // 虚空与 /kill 伤害不拦截：否则掉出大厅的玩家会无限下坠、/kill 也失效
                && !src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setNewDamage(0.0F);
        }
    }
}

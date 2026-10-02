package com.zhushen.space.entity;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 副本怪物实体注册。
 * 不注册 SpawnPlacement → 怪物不会在主世界自然生成，只通过刷怪蛋 / 命令 / 副本刷怪器出现。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ZhuShenSpace.MODID);

    /** T病毒丧尸：生化危机片的基础怪 */
    public static final DeferredHolder<EntityType<?>, EntityType<TVirusZombie>> T_VIRUS_ZOMBIE =
            ENTITY_TYPES.register("t_virus_zombie", () -> EntityType.Builder.of(TVirusZombie::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.74F)
                    .clientTrackingRange(10)
                    .build("t_virus_zombie"));

    /** 断肢（T 病毒丧尸部位脱落的表现实体，测试功能） */
    public static final DeferredHolder<EntityType<?>, EntityType<com.zhushen.space.entity.dismember.SeveredLimb>> SEVERED_LIMB =
            ENTITY_TYPES.register("severed_limb", () -> EntityType.Builder
                    .<com.zhushen.space.entity.dismember.SeveredLimb>of(com.zhushen.space.entity.dismember.SeveredLimb::new, MobCategory.MISC)
                    .sized(0.35F, 0.25F)
                    .clientTrackingRange(8)
                    .updateInterval(2)
                    .noSummon()
                    .build("severed_limb"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.zhushen.space.entity.art.ArtProjectile>> ART_PROJECTILE =
            ENTITY_TYPES.register("art_projectile", () -> EntityType.Builder
                    .<com.zhushen.space.entity.art.ArtProjectile>of(com.zhushen.space.entity.art.ArtProjectile::new, MobCategory.MISC)
                    .sized(0.5f, 0.5f).clientTrackingRange(12).updateInterval(1).noSummon().fireImmune()
                    .build("art_projectile"));

    /** 非弹体技艺的纯视觉特效（光束 / 附身光环 / 残影），不碰撞、不保存 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.zhushen.space.entity.art.ArtVfx>> ART_VFX =
            ENTITY_TYPES.register("art_vfx", () -> EntityType.Builder
                    .<com.zhushen.space.entity.art.ArtVfx>of(com.zhushen.space.entity.art.ArtVfx::new, MobCategory.MISC)
                    .sized(0.5f, 0.5f).clientTrackingRange(10).updateInterval(1).noSummon().fireImmune()
                    .build("art_vfx"));

    /** 投出的冷兵器（匕首等） */
    public static final DeferredHolder<EntityType<?>, EntityType<ThrownWeapon>> THROWN_WEAPON =
            ENTITY_TYPES.register("thrown_weapon", () -> EntityType.Builder
                    .<ThrownWeapon>of(ThrownWeapon::new, MobCategory.MISC)
                    .sized(0.5f, 0.5f).clientTrackingRange(4).updateInterval(20)
                    .build("thrown_weapon"));

    @SubscribeEvent
    public static void onRegisterAttributes(EntityAttributeCreationEvent event) {
        event.put(T_VIRUS_ZOMBIE.get(), TVirusZombie.createAttributes().build());
    }
}

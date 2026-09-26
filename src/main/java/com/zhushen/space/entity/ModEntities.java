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

    @SubscribeEvent
    public static void onRegisterAttributes(EntityAttributeCreationEvent event) {
        event.put(T_VIRUS_ZOMBIE.get(), TVirusZombie.createAttributes().build());
    }
}

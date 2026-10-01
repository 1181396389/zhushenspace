package com.zhushen.space.item;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;

/** 模组护甲材质 */
public final class ModArmorMaterials {
    private ModArmorMaterials() {}

    public static final DeferredRegister<ArmorMaterial> MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, ZhuShenSpace.MODID);

    /** 魔虚罗法阵：不提供护甲值（不是盔甲），护甲层贴图透明——外观由 MahoragaWheelRenderer 在头顶绘制法阵 */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> MAHORAGA = MATERIALS.register("mahoraga", () -> {
        EnumMap<ArmorItem.Type, Integer> def = new EnumMap<>(ArmorItem.Type.class);
        for (ArmorItem.Type t : ArmorItem.Type.values()) def.put(t, 0);
        return new ArmorMaterial(def, 0, SoundEvents.ARMOR_EQUIP_CHAIN, () -> Ingredient.EMPTY,
                List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "mahoraga"))), 0f, 0f);
    });
}

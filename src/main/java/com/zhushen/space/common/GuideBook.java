package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** 帕秋莉手册联动（可选）：首次登录与 /zhushen guide 发放《轮回者手册》 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class GuideBook {
    private GuideBook() {}

    public static final ResourceLocation BOOK = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "guide");
    private static final String GIVEN_TAG = "zhushenspace_guide_given";

    public static boolean available() { return ModList.get().isLoaded("patchouli"); }

    @SuppressWarnings("unchecked")
    public static ItemStack create() {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("patchouli", "guide_book"));
        ItemStack stack = new ItemStack(item);
        var type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.fromNamespaceAndPath("patchouli", "book"));
        if (type != null) stack.set((DataComponentType<ResourceLocation>) type, BOOK);
        return stack;
    }

    public static boolean give(ServerPlayer p) {
        if (!available()) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.guide.no_patchouli"), false);
            return false;
        }
        ItemStack book = create();
        if (!p.getInventory().add(book)) p.drop(book, false);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.guide.given"), true);
        return true;
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !available()) return;
        var tag = p.getPersistentData();
        if (tag.getBoolean(GIVEN_TAG)) return;
        tag.putBoolean(GIVEN_TAG, true);
        give(p);
    }
}

package com.zhushen.space;

import com.mojang.logging.LogUtils;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.entity.ModEntities;
import com.zhushen.space.item.InvitationEnvelopeItem;
import com.zhushen.space.item.TaiChiEmblemItem;
import com.zhushen.space.sound.ModSounds;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(ZhuShenSpace.MODID)
public class ZhuShenSpace {
    public static final String MODID = "zhushenspace";
    public static final Logger LOGGER = LogUtils.getLogger();

    // Deferred Registers
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Items
    public static final DeferredItem<InvitationEnvelopeItem> INVITATION_ENVELOPE =
            ITEMS.registerItem("invitation_envelope", InvitationEnvelopeItem::new, new Item.Properties().stacksTo(1));
    /** 太极拳流派饰品：装备在「流派」饰品栏后太极拳才会生效 */
    public static final DeferredItem<TaiChiEmblemItem> TAI_CHI_EMBLEM =
            ITEMS.registerItem("tai_chi_emblem", TaiChiEmblemItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
    /** T病毒丧尸刷怪蛋（副本测试用，该怪物不会在主世界自然生成） */
    public static final DeferredItem<DeferredSpawnEggItem> T_VIRUS_ZOMBIE_SPAWN_EGG =
            ITEMS.registerItem("t_virus_zombie_spawn_egg",
                    props -> new DeferredSpawnEggItem(ModEntities.T_VIRUS_ZOMBIE, 0x6B7D5A, 0x8B1A1A, props),
                    new Item.Properties());

    // Creative Tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ZHUSHEN_TAB = CREATIVE_MODE_TABS.register("zhushen_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.zhushenspace"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> INVITATION_ENVELOPE.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(INVITATION_ENVELOPE.get());
                output.accept(TAI_CHI_EMBLEM.get());
                output.accept(T_VIRUS_ZOMBIE_SPAWN_EGG.get());
            }).build());

    public ZhuShenSpace(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ModSounds.register(modEventBus);
        ModAttachments.ATTACHMENTS.register(modEventBus);
        ModEntities.ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(this::addCreative);
        // 可选联动：TACZ 枪械（未安装时不加载任何 TACZ 类）
        com.zhushen.space.compat.TaczCompat.init();
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("诸神空间模组已加载");
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(INVITATION_ENVELOPE);
        }
    }
}

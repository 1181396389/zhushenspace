package com.zhushen.space.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.item.ZsShieldItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ShieldModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * 主神盾牌的物品渲染：沿用原版盾牌模型（plate 12×22×1 + handle），贴图直接取
 * textures/entity/shield/&lt;key&gt;.png（64×64，UV 与原版 shield_base_nopattern 相同），不经过方块图集。
 */
public class ZsShieldRenderer extends BlockEntityWithoutLevelRenderer {

    private static ZsShieldRenderer instance;
    private ShieldModel model;

    private ZsShieldRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    public static ZsShieldRenderer get() {
        if (instance == null) instance = new ZsShieldRenderer();
        return instance;
    }

    public static final IClientItemExtensions EXTENSIONS = new IClientItemExtensions() {
        @Override
        public BlockEntityWithoutLevelRenderer getCustomRenderer() { return get(); }
    };

    @Override
    public void onResourceManagerReload(ResourceManager rm) {
        model = null;
    }

    private static ResourceLocation texture(ZsShieldItem item) {
        return ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "textures/entity/shield/" + item.type.key + ".png");
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack pose, MultiBufferSource buf, int light, int overlay) {
        if (!(stack.getItem() instanceof ZsShieldItem item)) return;
        if (model == null) model = new ShieldModel(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.SHIELD));
        pose.pushPose();
        pose.scale(1.0f, -1.0f, -1.0f);
        VertexConsumer vc = ItemRenderer.getFoilBufferDirect(buf, model.renderType(texture(item)), true, stack.hasFoil());
        model.handle().render(pose, vc, light, overlay);
        model.plate().render(pose, vc, light, overlay);
        pose.popPose();
    }
}

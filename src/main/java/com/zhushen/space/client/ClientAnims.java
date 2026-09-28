package com.zhushen.space.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** 玩家动作入口：KosmX Player Animator（modid playeranimator）存在时播放，缺失时静默跳过 */
public final class ClientAnims {

    public static final String MODID = "playeranimator";
    private static boolean loaded;

    private ClientAnims() {
    }

    static void init(FMLClientSetupEvent event) {
        loaded = ModList.get().isLoaded(MODID);
        if (loaded) event.enqueueWork(KosmxAnim::register);
    }

    public static boolean loaded() {
        return loaded;
    }

    /** 网络包入口：对指定实体播放动作（空名 = 停止） */
    public static void handle(int entityId, String name) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level != null ? mc.level.getEntity(entityId) : null;
        if (e instanceof AbstractClientPlayer p) play(p, name, 2);
    }

    public static void play(AbstractClientPlayer player, String name, int fadeTicks) {
        if (loaded) KosmxAnim.play(player, name, fadeTicks);
    }
}

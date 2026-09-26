package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.common.HallManager;
import net.minecraft.client.Camera;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 主神空间大厅氛围（客户端）：把天空/雾拉成纯白并拉近雾距，
 * 让白色混凝土平面在视野边缘渐隐——「白色虚空」即视感。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public class HallAmbienceClient {

    private static boolean inHall(Camera camera) {
        return camera.getEntity() != null
                && camera.getEntity().level().dimension() == HallManager.HALL_DIMENSION;
    }

    /** 雾/天空颜色拉白 */
    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (inHall(event.getCamera())) {
            event.setRed(0.95F);
            event.setGreen(0.96F);
            event.setBlue(0.98F);
        }
    }

    /** 雾距拉近：远处在白色中渐隐 */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (inHall(event.getCamera())) {
            event.setNearPlaneDistance(24.0F);
            event.setFarPlaneDistance(96.0F);
        }
    }
}

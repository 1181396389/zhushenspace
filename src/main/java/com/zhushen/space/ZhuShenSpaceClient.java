package com.zhushen.space;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = ZhuShenSpace.MODID, dist = Dist.CLIENT)
public class ZhuShenSpaceClient {
    public ZhuShenSpaceClient() {
        ZhuShenSpace.LOGGER.info("诸神空间客户端已加载");
    }
}

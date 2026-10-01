package com.zhushen.space.client.fx;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/**
 * 动漫风技艺特效用的两种纯色渲染类型（顶点只有位置 + 颜色，不用贴图）：
 * TOON = 普通透明混合，按提交顺序层层叠出色块分明的赛璐璐色阶；
 * GLOW = 加法混合，用于光晕、光束与闪光。
 * 两者都双面绘制、做深度测试但不写深度（不会把身后的特效 / 方块挡成黑块）。
 */
public abstract class ZsRenderTypes extends RenderType {

    private ZsRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling,
                          boolean sort, Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sort, setup, clear);
    }

    public static final RenderType TOON = create("zhushenspace_anime_toon", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 262144, false, false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_LIGHTNING_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    public static final RenderType GLOW = create("zhushenspace_anime_glow", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 262144, false, false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_LIGHTNING_SHADER)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));
}

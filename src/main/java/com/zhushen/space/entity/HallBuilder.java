package com.zhushen.space.entity;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.common.StructurePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * 主神空间大厅场景：极简「神的领域」风格——纯白悬浮平台 + 发光边线 + 中央主神光柱。
 * <p>场景三级来源（优先级从高到低，见 {@link StructurePlacer}）：</p>
 * <ol>
 *   <li>{@code config/zhushenspace/structures/hall_cafe.nbt / .schem} —— 外部建筑文件，
 *       下载或自行搭建的结构丢进去即可替换（推荐 .nbt，完整保留箱子/告示牌内容）</li>
 *   <li>mod 内置 {@code data/zhushenspace/structure/hall_cafe.nbt}</li>
 *   <li>内置极简风格（白色平台 + 信标光柱，无任何细节装饰）</li>
 * </ol>
 * <p>场景以原点为中心摆放（结构居中于 (0,1,0)），
 * 管理指令 {@code /zhushen hall rebuild} 强制重置，{@code /zhushen hall clear} 清空。</p>
 */
public final class HallBuilder {

    private HallBuilder() {
    }

    /** mod 内置大厅结构（可选，打包时存在才会被使用） */
    private static final ResourceLocation BUILTIN_HALL =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "hall_cafe");

    /** 场景已建特征点：平台中心 (0,1,0) */
    private static final BlockPos LANDMARK = new BlockPos(0, 1, 0);

    /** 清场范围（覆盖典型大厅建筑；仅清非空气方块） */
    private static final int CLEAR_R = 64;
    private static final int CLEAR_TOP = 48;

    /** 已建则跳过；未建则重置场景（幂等） */
    public static void ensureBuilt(ServerLevel level) {
        if (!level.getBlockState(LANDMARK).isAir()) return;
        rebuild(level);
    }

    /** 清空并重建场景：外部文件 → 内置结构 → 极简风格 */
    public static void rebuild(ServerLevel level) {
        clear(level);

        // 1) 外部文件（config 目录）：居中摆放
        int[] size = StructurePlacer.configSize("hall_cafe");
        if (size != null) {
            BlockPos anchor = new BlockPos(-size[0] / 2, 1, -size[2] / 2);
            if (StructurePlacer.placeFromConfig(level, "hall_cafe", anchor)) {
                ZhuShenSpace.LOGGER.info("大厅场景：已从 config 目录用外部文件重建");
                return;
            }
        }

        // 2) mod 内置结构：居中摆放
        int[] bs = StructurePlacer.builtinSize(level, BUILTIN_HALL);
        if (bs != null) {
            BlockPos anchor = new BlockPos(-bs[0] / 2, 1, -bs[2] / 2);
            if (StructurePlacer.placeBuiltin(level, BUILTIN_HALL, anchor)) {
                ZhuShenSpace.LOGGER.info("大厅场景：已用内置结构重建");
                return;
            }
        }

        // 3) 极简风格兜底
        buildMinimal(level);
        ZhuShenSpace.LOGGER.info("大厅场景：极简风格重建完成");
    }

    /** 清空场景区域（保留 y=0 白色地平面，仅清非空气方块） */
    public static void clear(ServerLevel level) {
        for (int x = -CLEAR_R; x <= CLEAR_R; x++) {
            for (int z = -CLEAR_R; z <= CLEAR_R; z++) {
                for (int y = 1; y <= CLEAR_TOP; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level.getBlockState(p).isAir()) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    // ===== 极简风格：纯白悬浮平台 + 发光边线 + 中央信标光柱 + 四角锚点柱 =====

    private static void buildMinimal(ServerLevel level) {
        // 主平台 31×31：白色混凝土主体，边圈海晶灯发光线
        for (int x = -15; x <= 15; x++) {
            for (int z = -15; z <= 15; z++) {
                boolean edge = x == -15 || x == 15 || z == -15 || z == 15;
                set(level, x, 1, z, edge ? Blocks.SEA_LANTERN : Blocks.WHITE_CONCRETE);
            }
        }
        // 中央主神光柱：3×3 铁块底座（一级信标金字塔）+ 信标
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                set(level, x, 1, z, Blocks.IRON_BLOCK);
            }
        }
        set(level, 0, 2, 0, Blocks.BEACON);
        // 四角空间锚点柱：白石英柱 + 顶端海晶灯
        int[][] corners = {{-13, -13}, {13, -13}, {-13, 13}, {13, 13}};
        for (int[] c : corners) {
            set(level, c[0], 2, c[1], Blocks.QUARTZ_PILLAR);
            set(level, c[0], 3, c[1], Blocks.QUARTZ_PILLAR);
            set(level, c[0], 4, c[1], Blocks.QUARTZ_PILLAR);
            set(level, c[0], 5, c[1], Blocks.SEA_LANTERN);
        }
    }

    private static void set(ServerLevel level, int x, int y, int z,
                            net.minecraft.world.level.block.Block block) {
        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
    }
}

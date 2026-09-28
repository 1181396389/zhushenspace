package com.zhushen.space.common;

import net.minecraft.world.level.GameRules;

/**
 * 模组游戏规则（/gamerule 可随时切换）。
 */
public final class ModGameRules {

    /**
     * 测试功能：T 病毒丧尸部位碰撞箱 / 部位血条 / 断肢。默认开启。
     * 关闭后丧尸恢复为整体受击（已断的肢体保持断开）。
     * 用法：/gamerule zsDismemberment false
     */
    public static GameRules.Key<GameRules.BooleanValue> DISMEMBERMENT;

    private ModGameRules() {
    }

    /** 在模组构造时调用一次 */
    public static void register() {
        if (DISMEMBERMENT != null) return;
        DISMEMBERMENT = GameRules.register("zsDismemberment", GameRules.Category.MOBS,
                GameRules.BooleanValue.create(true));
    }
}

package com.zhushen.space.common;

import com.zhushen.space.data.FeatType;
import net.minecraft.world.entity.player.Player;

/**
 * 性别判定入口。目前模组没有性别系统，基础性别默认视为男性（{@link #baseMale} 是预留接口）。
 * 「先天男娘」：资源兑换的前提与效果（专长 / 天赋 / 缺陷 / 怪癖除外）视为女性；
 * 受到针对性别的负面效果、针对性别的攻击、针对器官的效果时仍视为男性。
 */
public final class GenderRules {
    private GenderRules() {}

    public enum Context {
        /** 专长 / 天赋 / 缺陷 / 怪癖的前提与效果 */
        FEAT_TRAIT,
        /** 其他资源兑换的前提与效果 */
        EXCHANGE,
        /** 针对性别的负面效果 */
        NEGATIVE,
        /** 针对性别的攻击 */
        ATTACK,
        /** 针对器官的效果 */
        ORGAN
    }

    /** 基础性别（待接入性别系统） */
    public static boolean baseMale(Player p) { return true; }

    public static boolean femboy(Player p) {
        return p != null && BuildServer.hasFeat(p, FeatType.BORN_FEMBOY, 0);
    }

    /** 该情境下是否视为男性 */
    public static boolean countsAsMale(Player p, Context c) {
        if (!baseMale(p)) return false;
        if (!femboy(p)) return true;
        return c != Context.EXCHANGE;
    }

    /** 该情境下是否视为女性（负面效果时男娘同时视为男性与女性） */
    public static boolean countsAsFemale(Player p, Context c) {
        if (!baseMale(p)) return true;
        if (!femboy(p)) return false;
        return c == Context.EXCHANGE || c == Context.NEGATIVE;
    }
}

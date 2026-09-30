package com.zhushen.space.common;

import com.zhushen.space.data.ArtSkill;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntToDoubleFunction;

/**
 * 增幅（规则书「增幅」）：
 * <ul>
 *   <li>增幅包括增幅消耗、增幅效果与增幅上限，由各能力自行说明。</li>
 *   <li><b>已经增幅 X 次</b>：视为已经增幅；未到上限时仍可继续增幅，但上限不变，继续增幅按已增幅后的档位计算消耗
 *       （如第 1 次 1 点、第 2 次 2 点，已经增幅 1 次时继续增幅花费 2 点）。</li>
 *   <li><b>已经增幅 X 点</b>：X 点能量视为已经支付，按每次增幅的消耗依次抵扣
 *       （每次 2 点时，已经增幅 3 点：第一次无需消耗，第二次只需 1 点）。</li>
 *   <li><b>增幅的消耗减少 X 点</b>：如常增幅，额外能耗中的 X 点无需支付。</li>
 *   <li><b>增幅的上限增加 X 点（次）</b>。</li>
 *   <li>无论增幅几次、几种，在判定上都与基础能耗一同算作<b>一次能耗</b>：
 *       目标取消增幅或基础效果时所有效果一并解除，也无法借多次增幅多次触发其他能力。</li>
 * </ul>
 */
public final class Amplify {

    private Amplify() {
    }

    /** 来自其他能力的增幅修正 */
    public record Mods(int preSteps, double preCredit, double costReduction, int limitBonus) {
        public static final Mods NONE = new Mods(0, 0, 0, 0);

        public Mods plus(Mods o) {
            return new Mods(preSteps + o.preSteps, preCredit + o.preCredit, costReduction + o.costReduction, limitBonus + o.limitBonus);
        }
    }

    /** 增幅方案：生效的增幅次数（含视为已增幅的部分）与需要额外支付的能量 */
    public record Plan(int steps, double cost) {
    }

    private static final List<BiFunction<ServerPlayer, ArtSkill, Mods>> PROVIDERS = new ArrayList<>();

    /** 注册增幅修正的提供者（专长、装备、状态……） */
    public static void addProvider(BiFunction<ServerPlayer, ArtSkill, Mods> f) {
        PROVIDERS.add(f);
    }

    public static Mods mods(ServerPlayer p, ArtSkill s) {
        Mods m = Mods.NONE;
        for (var f : PROVIDERS) {
            Mods o = f.apply(p, s);
            if (o != null) m = m.plus(o);
        }
        return m;
    }

    /**
     * 计算增幅方案。
     *
     * @param available 支付基础能耗后剩余的能量
     * @param limit     能力自身的增幅上限（次）
     * @param stepCost  第 i 次（从 1 开始）增幅的消耗
     * @param mods      增幅修正
     * @param want      是否主动增幅（用满）；为 false 时只计算视为已经增幅（无需额外支付）的部分
     */
    public static Plan plan(double available, int limit, IntToDoubleFunction stepCost, Mods mods, boolean want) {
        int cap = Math.max(0, limit + mods.limitBonus());
        int steps = Math.min(mods.preSteps(), cap);
        double credit = mods.preCredit(), reduction = mods.costReduction(), paid = 0;
        while (steps < cap) {
            double c = stepCost.applyAsDouble(steps + 1);
            double useCredit = Math.min(credit, c);
            c -= useCredit;
            if (c > 1e-9 && !want) break;
            double useRed = Math.min(reduction, c);
            c -= useRed;
            if (paid + c > available + 1e-9) break;
            credit -= useCredit;
            reduction -= useRed;
            paid += c;
            steps++;
        }
        return new Plan(steps, paid);
    }
}

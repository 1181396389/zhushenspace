package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * 玩家 B/L/A 生命状态（Attachment 持久化，死亡不保留——重生即完好之躯）。
 *
 * 伤害分三档：冲击（B）/ 严重（L）/ 恶性（A）。
 * 受伤时先扣完好生命值，再将伤害累加到对应池；总伤势超过生命上限时向下转化伤势：
 * 2 点冲击 → 1 点严重，2 点严重 → 1 点恶性（整池转化时单数向上取整，如 13B → 7L），
 * 直至总伤势等于上限。转化结束后若全身皆为恶性伤害且 ≥ 上限 → 死亡。
 * 治疗从冲击开始 1:1 移除伤势（冲击 → 严重 → 恶性），并回复完好生命值。
 */
public class PlayerHealthData implements INBTSerializable<CompoundTag> {

    private int b, l, a;

    /** 伤害档位：冲击 / 严重 / 恶性 */
    public enum Severity { B, L, A }

    /** 一次伤势结算结果 */
    public record Result(boolean died, int bToL, int lToA) {
        public boolean converted() {
            return bToL > 0 || lToA > 0;
        }
    }

    public int b() {
        return b;
    }

    public int l() {
        return l;
    }

    public int a() {
        return a;
    }

    public int total() {
        return b + l + a;
    }

    /** 完好生命值（不足 0 截断） */
    public int intact(int maxHp) {
        return Math.max(0, maxHp - total());
    }

    public void reset() {
        b = l = a = 0;
    }

    /**
     * 记录一档伤害并按规则向下转化伤势。
     *
     * @param severity 伤害档位
     * @param amount   伤害点数（整数）
     * @param maxHp    生命上限
     */
    public Result apply(Severity severity, int amount, int maxHp) {
        switch (severity) {
            case B -> b += amount;
            case L -> l += amount;
            case A -> a += amount;
        }
        int total = b + l + a;
        int bToL = 0, lToA = 0;
        while (total > maxHp) {
            if (b > 0) {
                int need = total - maxHp; // 需要削减的伤势总量
                if (b >= need * 2) {
                    // 部分转化即可回到上限
                    b -= need * 2;
                    l += need;
                    bToL += need;
                    total -= need;
                } else {
                    // B 整池转化（单数向上取整：13B → 7L）
                    int gained = (b + 1) / 2;
                    l += gained;
                    bToL += gained;
                    total -= b - gained;
                    b = 0;
                }
            } else if (l > 0) {
                int need = total - maxHp;
                if (l >= need * 2) {
                    l -= need * 2;
                    a += need;
                    lToA += need;
                    total -= need;
                } else {
                    int gained = (l + 1) / 2;
                    a += gained;
                    lToA += gained;
                    total -= l - gained;
                    l = 0;
                }
            } else {
                break; // 全为 A，无法再向下转化
            }
        }
        // 转化结束后：全身恶性且 ≥ 上限 → 死亡
        boolean died = b == 0 && l == 0 && a > 0 && a >= maxHp;
        return new Result(died, bToL, lToA);
    }

    /**
     * 治疗：从冲击开始 1:1 移除伤势（冲击 → 严重 → 恶性）。
     *
     * @return 实际移除的伤势点数
     */
    public int heal(int amount) {
        if (amount <= 0) return 0;
        int rem = amount;
        int take = Math.min(b, rem);
        b -= take;
        rem -= take;
        take = Math.min(l, rem);
        l -= take;
        rem -= take;
        take = Math.min(a, rem);
        a -= take;
        rem -= take;
        return amount - rem;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("B", b);
        tag.putInt("L", l);
        tag.putInt("A", a);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        b = tag.getInt("B");
        l = tag.getInt("L");
        a = tag.getInt("A");
    }
}

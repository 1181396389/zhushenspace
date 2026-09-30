package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家身体状况（Attachment，死亡不保留——重生即完好之躯）：
 * 生存需求（水分 / 体力 / 精力）、不良状态点数（可叠加 / 不可叠加分开记录）、毁灭性后果、固有不良状态、倒地、闭气。
 */
public class PlayerConditionData implements INBTSerializable<CompoundTag> {

    // ===== 生存需求 =====
    /** 水分 0~100 */
    public float thirst = 100f;
    /** 体力（上限随耐力变化；-1 = 尚未初始化，首次 tick 设为上限） */
    public float stamina = -1f;
    /** 精力（睡眠）0~100 */
    public float sleep = 100f;
    /** 体力透支：体力耗尽后，恢复到一定比例之前不能冲刺 / 攻击 */
    public boolean exhausted;
    /** 困到昏睡（精力归零） */
    public boolean collapsed;
    /** 饥渴：缺乏充足的水和食物的累计时间（tick） */
    public long deprivedTicks;
    /** 疲惫：缺乏睡眠的累计时间（tick） */
    public long wearyTicks;
    /** 属性伤害：饥渴（耐力）、疲惫（沉着）、多系统器官功能衰竭（耐力） */
    public int starveEnd, wearyCom, modsEnd;

    // ===== 不良状态点数 =====
    /** 可叠加的点数（任何来源都互相叠加） */
    public final int[] stack = new int[StatusType.COUNT];
    /** 不可叠加的点数：来源 → 点数（同一来源取优，不同来源分别记录；生效时取最高者） */
    @SuppressWarnings("unchecked")
    public final Map<String, Integer>[] nonStack = new Map[StatusType.COUNT];
    /** 肢体妨害点数（按 LimbPart 顺序；头部不使用） */
    public final int[] limb = new int[LimbPart.COUNT];
    /** 燃烧的性质：0 = 自然火焰，1 = 恶意燃烧（不会自行熄灭），2 = 魔法火焰（持续时间内无法以物理方式熄灭） */
    public int burnKind;
    /** 魔法火焰结束的时刻（gameTime） */
    public long magicBurnUntil;
    /** 燃烧 / 流血伤害的来源、魅惑的沉迷目标、恐惧的目标、精神奴役的支配者、嘲讽来源 */
    public UUID burnSource, bleedSource, charmTarget, fearTarget, master, taunter;
    /** 冻结点数中含有非自然来源（非自然来源造成的冰封不会因回温而解除） */
    public boolean freezeUnnatural;
    /** 已触发的毁灭性后果（按 StatusType 位） */
    public int permanent;
    /** 睡眠（重度欲眠）中受到伤害而惊醒：欲眠点数再次上升前不会重新入睡 */
    public boolean wokeUp;

    // ===== 固有不良状态（直接施加，带结束时刻；Long.MAX_VALUE = 直到解除） =====
    public final long[] condUntil = new long[Condition.COUNT];
    /** 开放性创口的数量（可叠加） */
    public int openWounds;

    // ===== 倒地 / 闭气 =====
    public boolean prone;
    /** 已经闭气的时间（tick）与呼吸停止后经过的时间（tick） */
    public int breathTicks, apneaTicks;

    public PlayerConditionData() {
        for (int i = 0; i < nonStack.length; i++) nonStack[i] = new LinkedHashMap<>();
    }

    /** 生效的点数：可叠加的点数与各个不可叠加来源中取最高者（肢体妨害取最严重的肢体） */
    public int points(StatusType t) {
        if (t == StatusType.LIMB) {
            int m = 0;
            for (int v : limb) m = Math.max(m, v);
            return m;
        }
        int m = stack[t.ordinal()];
        for (int v : nonStack[t.ordinal()].values()) m = Math.max(m, v);
        return m;
    }

    /** 是否有任何点数（任一条目） */
    public boolean any(StatusType t) {
        return points(t) > 0;
    }

    public boolean isPermanent(StatusType t) { return (permanent & t.bit()) != 0; }

    public boolean timed(Condition c, long now) { return condUntil[c.ordinal()] > now; }

    /** 清空某一类型的全部点数 */
    public void wipe(StatusType t) {
        if (t == StatusType.LIMB) java.util.Arrays.fill(limb, 0);
        else {
            stack[t.ordinal()] = 0;
            nonStack[t.ordinal()].clear();
        }
    }

    /**
     * 扣除 n 点（豁免 / 反制 / 自然恢复）：每次从当前最高的条目扣 1 点，使生效点数尽可能降低。
     * 返回实际扣除的点数。
     */
    public int deduct(StatusType t, int n) {
        int done = 0;
        if (t == StatusType.LIMB) {
            while (done < n) {
                int best = -1;
                for (int i = 0; i < limb.length; i++) if (limb[i] > 0 && (best < 0 || limb[i] > limb[best])) best = i;
                if (best < 0) break;
                limb[best]--;
                done++;
            }
            return done;
        }
        Map<String, Integer> ns = nonStack[t.ordinal()];
        while (done < n) {
            String bestKey = null;
            int best = stack[t.ordinal()];
            for (var e : ns.entrySet()) if (e.getValue() > best) { best = e.getValue(); bestKey = e.getKey(); }
            if (best <= 0) break;
            if (bestKey == null) stack[t.ordinal()]--;
            else if (best - 1 <= 0) ns.remove(bestKey);
            else ns.put(bestKey, best - 1);
            done++;
        }
        return done;
    }

    /** 「使目标身上的该类不良状态点数增加 X 点」：所有条目（可叠加与不可叠加）全部增加 */
    public void raiseAll(StatusType t, int n) {
        if (t == StatusType.LIMB) {
            for (int i = 0; i < limb.length; i++) if (limb[i] > 0) limb[i] += n;
            return;
        }
        if (stack[t.ordinal()] > 0) stack[t.ordinal()] += n;
        nonStack[t.ordinal()].replaceAll((k, v) -> v + n);
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Thirst", thirst);
        tag.putFloat("Stamina", stamina);
        tag.putFloat("Sleep", sleep);
        tag.putBoolean("Exhausted", exhausted);
        tag.putBoolean("Collapsed", collapsed);
        tag.putLong("Deprived", deprivedTicks);
        tag.putLong("WearyTicks", wearyTicks);
        tag.putInt("StarveEnd", starveEnd);
        tag.putInt("WearyCom", wearyCom);
        tag.putInt("ModsEnd", modsEnd);
        tag.putIntArray("Points", stack);
        ListTag ns = new ListTag();
        for (int i = 0; i < nonStack.length; i++) {
            for (var e : nonStack[i].entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putInt("T", i);
                c.putString("K", e.getKey());
                c.putInt("V", e.getValue());
                ns.add(c);
            }
        }
        tag.put("NonStack", ns);
        tag.putIntArray("Limb", limb);
        tag.putInt("BurnKind", burnKind);
        tag.putLong("MagicBurnUntil", magicBurnUntil);
        putUuid(tag, "BurnSource", burnSource);
        putUuid(tag, "BleedSource", bleedSource);
        putUuid(tag, "CharmTarget", charmTarget);
        putUuid(tag, "FearTarget", fearTarget);
        putUuid(tag, "Master", master);
        putUuid(tag, "Taunter", taunter);
        tag.putBoolean("FreezeUnnatural", freezeUnnatural);
        tag.putInt("Permanent", permanent);
        tag.putBoolean("WokeUp", wokeUp);
        long[] cu = condUntil.clone();
        tag.putLongArray("CondUntil", cu);
        tag.putInt("OpenWounds", openWounds);
        tag.putBoolean("Prone", prone);
        tag.putInt("Breath", breathTicks);
        tag.putInt("Apnea", apneaTicks);
        return tag;
    }

    private static void putUuid(CompoundTag tag, String k, UUID v) {
        if (v != null) tag.putUUID(k, v);
    }

    private static UUID getUuid(CompoundTag tag, String k) {
        return tag.hasUUID(k) ? tag.getUUID(k) : null;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        thirst = tag.contains("Thirst") ? tag.getFloat("Thirst") : 100f;
        stamina = tag.contains("Stamina") ? tag.getFloat("Stamina") : -1f;
        sleep = tag.contains("Sleep") ? tag.getFloat("Sleep") : 100f;
        exhausted = tag.getBoolean("Exhausted");
        collapsed = tag.getBoolean("Collapsed");
        deprivedTicks = tag.getLong("Deprived");
        wearyTicks = tag.getLong("WearyTicks");
        starveEnd = tag.getInt("StarveEnd");
        wearyCom = tag.getInt("WearyCom");
        modsEnd = tag.getInt("ModsEnd");
        int[] p = tag.getIntArray("Points");
        java.util.Arrays.fill(stack, 0);
        System.arraycopy(p, 0, stack, 0, Math.min(p.length, stack.length));
        for (Map<String, Integer> m : nonStack) m.clear();
        ListTag ns = tag.getList("NonStack", Tag.TAG_COMPOUND);
        for (int i = 0; i < ns.size(); i++) {
            CompoundTag c = ns.getCompound(i);
            int t = c.getInt("T");
            if (t >= 0 && t < nonStack.length && c.getInt("V") > 0) nonStack[t].put(c.getString("K"), c.getInt("V"));
        }
        int[] l = tag.getIntArray("Limb");
        java.util.Arrays.fill(limb, 0);
        System.arraycopy(l, 0, limb, 0, Math.min(l.length, limb.length));
        burnKind = tag.getInt("BurnKind");
        magicBurnUntil = tag.getLong("MagicBurnUntil");
        burnSource = getUuid(tag, "BurnSource");
        bleedSource = getUuid(tag, "BleedSource");
        charmTarget = getUuid(tag, "CharmTarget");
        fearTarget = getUuid(tag, "FearTarget");
        master = getUuid(tag, "Master");
        taunter = getUuid(tag, "Taunter");
        freezeUnnatural = tag.getBoolean("FreezeUnnatural");
        permanent = tag.getInt("Permanent");
        wokeUp = tag.getBoolean("WokeUp");
        long[] cu = tag.getLongArray("CondUntil");
        java.util.Arrays.fill(condUntil, 0L);
        System.arraycopy(cu, 0, condUntil, 0, Math.min(cu.length, condUntil.length));
        openWounds = tag.getInt("OpenWounds");
        prone = tag.getBoolean("Prone");
        breathTicks = tag.getInt("Breath");
        apneaTicks = tag.getInt("Apnea");
    }
}

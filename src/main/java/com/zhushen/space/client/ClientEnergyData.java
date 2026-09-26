package com.zhushen.space.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端能量池镜像数据（由 SyncEnergyPayload 同步），供 HUD 显示。
 */
public class ClientEnergyData {

    /** 内力池 id（与服务端 EnergyManager.POOL_NEILI 一致） */
    public static final String NEILI_ID = "neili";

    /** 单个能量池的客户端视图 */
    public record PoolView(String id, double current, double max, int color) {
        public int currentInt() {
            return (int) Math.round(current);
        }

        public int maxInt() {
            return (int) Math.round(max);
        }
    }

    private static volatile List<PoolView> pools = List.of();
    private static volatile boolean breathEnabled;

    public static void update(String[] ids, double[] currents, double[] maxes, int[] colors,
                              boolean breathOn) {
        List<PoolView> list = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            list.add(new PoolView(ids[i], currents[i], maxes[i], colors[i]));
        }
        pools = list;
        breathEnabled = breathOn;
    }

    public static List<PoolView> pools() {
        return pools;
    }

    public static boolean breathEnabled() {
        return breathEnabled;
    }

    public static boolean hasPool(String id) {
        for (PoolView pool : pools()) {
            if (pool.id().equals(id)) return true;
        }
        return false;
    }
}

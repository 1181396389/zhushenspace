package com.zhushen.space.common;

import com.zhushen.space.data.PlayerBuildData;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 建卡对接端口：后续的物品系统 / 兑换系统 / 战斗专长在此注册监听。
 * 例如：{@code BuildHooks.register(new BuildHooks.Listener() { ... })}
 */
public final class BuildHooks {
    private BuildHooks() {}

    public interface Listener {
        /** 建卡完成（首次成功提交）时调用：可读取 data.pendingItems / data.pendingExchange 并处理后清零 */
        default void onCreated(ServerPlayer player, PlayerBuildData data) {}

        /** 每次成功提交后调用（含建卡后追加的 XP 投入） */
        default void onCommitted(ServerPlayer player, PlayerBuildData data) {}
    }

    private static final List<Listener> LISTENERS = new ArrayList<>();

    public static void register(Listener l) {
        LISTENERS.add(l);
    }

    static void fireCreated(ServerPlayer p, PlayerBuildData d) {
        for (Listener l : LISTENERS) l.onCreated(p, d);
    }

    static void fireCommitted(ServerPlayer p, PlayerBuildData d) {
        for (Listener l : LISTENERS) l.onCommitted(p, d);
    }
}

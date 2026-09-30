package com.zhushen.space.common;

import com.zhushen.space.data.LimbPart;
import com.zhushen.space.data.PlayerLimbData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.function.Predicate;

/**
 * 强制趴伏的判定（供 {@code PlayerPoseMixin} 使用）：
 * 服务端按倒地 / 断腿数据判定；客户端由 {@link #CLIENT} 判定（本地玩家看同步来的倒地状态，
 * 其他玩家沿用服务端同步的趴伏姿态，不在本地重算成站立）。
 */
public final class PoseControl {

    private PoseControl() {
    }

    /** 客户端判定（由客户端初始化时设置） */
    public static volatile Predicate<Player> CLIENT = p -> false;

    public static boolean keepCrawl(Player p) {
        if (p.isSpectator() || p.isPassenger() || p.isSleeping() || p.isFallFlying() || !p.isAlive()) return false;
        if (p.level().isClientSide) return CLIENT.test(p);
        if (!(p instanceof ServerPlayer sp)) return false;
        if (sp.getAbilities().flying) return false;
        if (StatusManager.data(sp).prone && !StatusManager.freeMover(sp)) return true;
        PlayerLimbData d = LimbManager.data(sp);
        return d.isSevered(LimbPart.RIGHT_LEG) && d.isSevered(LimbPart.LEFT_LEG);
    }
}

package com.zhushen.space.common;

import com.zhushen.space.data.FeatType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerFeatData;
import com.zhushen.space.network.SyncFeatsPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

public final class FeatServer {
    private FeatServer() {}

    public static PlayerFeatData data(ServerPlayer p) {
        return p.getData(ModAttachments.PLAYER_FEATS);
    }

    /** 提交：新掩码必须包含旧掩码、前置满足、点数足够 */
    public static void commit(ServerPlayer player, int mask) {
        PlayerFeatData d = data(player);
        if ((mask & d.owned()) != d.owned() || !FeatType.valid(mask)
                || FeatType.cost(mask) > d.totalPoints()) {
            sync(player);
            return;
        }
        d.setOwned(mask);
        sync(player);
    }

    public static void grantEnvelope(ServerPlayer player) {
        PlayerFeatData d = data(player);
        if (d.envelopeGranted()) return;
        d.markEnvelopeGranted();
        d.addTotalPoints(FeatType.ENVELOPE_FEAT_POINTS);
        sync(player);
    }

    public static void sync(ServerPlayer player) {
        PlayerFeatData d = data(player);
        boolean used = player.getData(ModAttachments.PLAYER_ATTRIBUTES).envelopeUsed();
        PacketDistributor.sendToPlayer(player, new SyncFeatsPayload(d.totalPoints(), d.owned(), used));
    }
}

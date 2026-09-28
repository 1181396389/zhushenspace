package com.zhushen.space.client;

import com.zhushen.space.data.LimbPart;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.HumanoidArm;
import org.jetbrains.annotations.NotNull;

/**
 * 断肢渲染层（KosmX 动作库，最高优先级）：把已断部位（含袖套 / 裤腿 / 盔甲层）与该手持物缩放为 0。
 * 第一人称的断臂由 {@link ClientLimbEvents} 通过 RenderHandEvent 隐藏。
 */
final class KosmxLimbLayer implements IAnimation {

    private static final Vec3f ZERO = new Vec3f(0f, 0f, 0f);
    private final AbstractClientPlayer player;

    KosmxLimbLayer(AbstractClientPlayer player) {
        this.player = player;
    }

    @Override
    public boolean isActive() {
        return ClientLimbData.mask(player.getId()) != 0;
    }

    @Override
    public @NotNull Vec3f get3DTransform(@NotNull String modelName, @NotNull TransformType type, float tickDelta,
                                         @NotNull Vec3f value0) {
        if (type != TransformType.SCALE) return value0;
        int mask = ClientLimbData.mask(player.getId());
        for (LimbPart p : LimbPart.values()) {
            if ((mask & p.bit()) != 0 && p.modelName.equals(modelName)) return ZERO;
        }
        // 手持物：跟随对应手臂
        if ("rightItem".equals(modelName) || "leftItem".equals(modelName)) {
            LimbPart arm = "rightItem".equals(modelName) ? LimbPart.RIGHT_ARM : LimbPart.LEFT_ARM;
            if ((mask & arm.bit()) != 0) return ZERO;
        }
        return value0;
    }

    @Override
    public void setupAnim(float tickDelta) {
    }

    /** 某条手臂对应哪只手（考虑左撇子设置） */
    static boolean isMainArm(AbstractClientPlayer player, LimbPart arm) {
        return (arm == LimbPart.RIGHT_ARM) == (player.getMainArm() == HumanoidArm.RIGHT);
    }
}

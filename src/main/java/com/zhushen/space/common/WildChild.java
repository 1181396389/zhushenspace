package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.FeatType;
import com.zhushen.space.data.StatusType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 狼孩 / 人猿泰山：
 * <ul>
 *   <li>文盲：不能阅读成书、书与笔，不能使用讲台；告示牌上的字显示为乱码（客户端 {@code ClientWildChild}）。本模组的指南书不受影响。</li>
 *   <li>怕火：看见火焰（火、灵魂火、点燃的营火、燃烧中的生物）时无法避免地获得 3 点恐惧（不叠加）。</li>
 *   <li>泰山：贴墙攀爬不坠落（客户端负责上升速度，服务端在贴墙时清除坠落距离）。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class WildChild {
    private WildChild() {}

    static final int FEAR_POINTS = 3;
    static final int SCAN_R = 8, SCAN_DY = 4;

    /** 双端：本地 / 服务端玩家是否为文盲 */
    public static boolean illiterate(Player p) {
        if (p.level().isClientSide) {
            int[] m = com.zhushen.space.client.ClientBuildData.featMask;
            int k = FeatType.WOLF_CHILD.ordinal();
            return m.length > k && (m[k] & FeatType.LEVEL_BITS) != 0 && !FeatType.wildLiterate(m[k]);
        }
        return FeatEffects.illiterate(p);
    }

    public static boolean tarzan(Player p) {
        if (p.level().isClientSide) {
            int[] m = com.zhushen.space.client.ClientBuildData.featMask;
            int k = FeatType.WOLF_CHILD.ordinal();
            return m.length > k && (m[k] & FeatType.LEVEL_BITS) != 0 && FeatType.wildVariant(m[k]) == 1;
        }
        return FeatEffects.tarzan(p);
    }

    static boolean writing(ItemStack st) {
        return st.is(Items.WRITTEN_BOOK) || st.is(Items.WRITABLE_BOOK);
    }

    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        if (writing(e.getItemStack()) && illiterate(e.getEntity())) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.FAIL);
            if (e.getEntity() instanceof ServerPlayer sp)
                sp.displayClientMessage(Component.translatable("msg.zhushenspace.wild.illiterate"), true);
        }
    }

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!e.getLevel().getBlockState(e.getPos()).is(Blocks.LECTERN) || !illiterate(e.getEntity())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.FAIL);
        if (e.getEntity() instanceof ServerPlayer sp)
            sp.displayClientMessage(Component.translatable("msg.zhushenspace.wild.illiterate"), true);
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !p.isAlive()) return;
        if (p.horizontalCollision && FeatEffects.tarzan(p)) p.fallDistance = 0;
        if (p.tickCount % 20 != 7 || !FeatEffects.fearsFire(p) || p.isCreative() || p.isSpectator()) return;
        if (seesFire(p)) {
            int got = StatusManager.addKeyed(p, StatusType.FEAR, FEAR_POINTS, false, null, StatusManager.Source.NATURAL, 0, "fire_fear", null);
            if (got > 0) p.displayClientMessage(Component.translatable("msg.zhushenspace.wild.fire"), true);
        }
    }

    /** 视野内（身前约 70°）有火：火 / 灵魂火 / 点燃的营火 / 燃烧的生物（含自己）。火把等光源不算 */
    static boolean seesFire(ServerPlayer p) {
        if (p.isOnFire()) return true;
        Level lv = p.level();
        Vec3 eye = p.getEyePosition(), look = p.getViewVector(1f);
        for (Entity e : lv.getEntities(p, p.getBoundingBox().inflate(SCAN_R, SCAN_DY, SCAN_R), en -> en.isOnFire() && !en.isSpectator())) {
            if (visible(p, eye, look, e.position().add(0, e.getBbHeight() * 0.5, 0), null)) return true;
        }
        BlockPos c = p.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -SCAN_DY; dy <= SCAN_DY; dy++)
            for (int dx = -SCAN_R; dx <= SCAN_R; dx++)
                for (int dz = -SCAN_R; dz <= SCAN_R; dz++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    BlockState st = lv.getBlockState(m);
                    boolean fire = st.is(BlockTags.FIRE)
                            || (st.is(BlockTags.CAMPFIRES) && st.hasProperty(CampfireBlock.LIT) && st.getValue(CampfireBlock.LIT));
                    if (fire && visible(p, eye, look, Vec3.atCenterOf(m), m.immutable())) return true;
                }
        return false;
    }

    private static boolean visible(ServerPlayer p, Vec3 eye, Vec3 look, Vec3 at, BlockPos self) {
        Vec3 d = at.subtract(eye);
        double len = d.length();
        if (len < 1e-3) return true;
        if (d.scale(1 / len).dot(look) < 0.35) return false;
        BlockHitResult h = p.level().clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return h.getType() == HitResult.Type.MISS || (self != null && h.getBlockPos().equals(self));
    }
}

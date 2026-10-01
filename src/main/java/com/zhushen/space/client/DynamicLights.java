package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.common.MagicSpells;
import com.zhushen.space.data.ModComponents;
import com.zhushen.space.entity.art.ArtVfx;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 客户端动态光源：光亮术物品（手持 / 穿戴 / 掉落 / 物品展示框，半径 20 米）、照明术光球（半径 10 米）。
 * <p>
 * 只影响画面：方块网格、实体、粒子取光照时与动态光取较亮者；不修改世界光照，不影响刷怪。
 * 光源半径内为满亮度，边缘 2 米内渐暗。光源移动超过阈值时重建受影响的区块网格。
 * 网格构建在工作线程读取光源表，所以光源表是整体替换的不可变数组。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class DynamicLights {
    private DynamicLights() {}

    public static final float ITEM_RADIUS = 20f, ORB_RADIUS = 10f;
    /** 半径外的渐暗距离 */
    private static final float EDGE = 4f;

    /** x, y, z, r 四元组 */
    private static volatile float[] SRC = new float[0];
    private static final Map<Long, float[]> COMMITTED = new HashMap<>();
    private static ClientLevel lastLevel;

    /** 某方块位置的动态方块光等级（0 = 无） */
    public static int blockLight(BlockPos pos) {
        float[] s = SRC;
        if (s.length == 0) return 0;
        float px = pos.getX() + 0.5f, py = pos.getY() + 0.5f, pz = pos.getZ() + 0.5f;
        int best = 0;
        for (int i = 0; i + 3 < s.length; i += 4) {
            float r = s[i + 3];
            float dx = px - s[i], dy = py - s[i + 1], dz = pz - s[i + 2];
            float d2 = dx * dx + dy * dy + dz * dz, outer = r + EDGE;
            if (d2 >= outer * outer) continue;
            int lv;
            if (d2 <= (r - 1f) * (r - 1f)) lv = 15;
            else lv = Mth.clamp((int) (15f - (Mth.sqrt(d2) - (r - 1f)) * 3f), 0, 15);
            if (lv > best) { best = lv; if (best == 15) return 15; }
        }
        return best;
    }

    private static boolean lit(ItemStack st, long gt) {
        if (st.isEmpty()) return false;
        Long u = st.get(ModComponents.LIGHT_UNTIL.get());
        return u != null && u > gt;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lv = mc.level;
        if (lv != lastLevel) {
            COMMITTED.clear();
            SRC = new float[0];
            lastLevel = lv;
        }
        if (lv == null || mc.player == null) return;
        long gt = lv.getGameTime();
        Vec3 me = mc.player.position();
        Map<Long, float[]> now = new HashMap<>();
        boolean fp = mc.options.getCameraType().isFirstPerson();
        for (Entity en : lv.entitiesForRendering()) {
            if (en.distanceToSqr(me) > 128 * 128) continue;
            if (en instanceof ArtVfx v) {
                if (v.kind() != ArtVfx.LIGHT_ORBS || v.followId() < 0) continue;
                Entity f = lv.getEntity(v.followId());
                if (f == null) continue;
                Vec3 c = MagicSpells.orbCenter(f);
                now.put(((long) en.getId() << 1) | 1L, new float[]{(float) c.x, (float) c.y, (float) c.z, ORB_RADIUS});
                continue;
            }
            boolean on = false;
            double y = en.getY() + en.getBbHeight() * 0.5;
            if (en instanceof ItemEntity ie) { on = lit(ie.getItem(), gt); y = en.getY() + 0.25; }
            else if (en instanceof ItemFrame fr) on = lit(fr.getItem(), gt);
            else if (en instanceof LivingEntity le) {
                for (EquipmentSlot sl : EquipmentSlot.values()) if (lit(le.getItemBySlot(sl), gt)) { on = true; break; }
                y = en.getY() + en.getBbHeight() * 0.6;
            }
            if (!on) continue;
            now.put((long) en.getId() << 1, new float[]{(float) en.getX(), (float) y, (float) en.getZ(), ITEM_RADIUS});
            // 光尘
            if ((gt + en.getId()) % 6 == 0 && !(fp && en == mc.getCameraEntity())) {
                lv.addParticle(ParticleTypes.END_ROD, en.getX() + (lv.random.nextDouble() - 0.5) * 0.6, y + (lv.random.nextDouble() - 0.3) * 0.5,
                        en.getZ() + (lv.random.nextDouble() - 0.5) * 0.6, 0, 0.01, 0);
            }
        }
        LongOpenHashSet dirty = new LongOpenHashSet();
        boolean changed = false;
        Iterator<Map.Entry<Long, float[]>> it = COMMITTED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, float[]> en = it.next();
            if (!now.containsKey(en.getKey())) { mark(dirty, en.getValue()); it.remove(); changed = true; }
        }
        for (Map.Entry<Long, float[]> en : now.entrySet()) {
            float[] n = en.getValue(), o = COMMITTED.get(en.getKey());
            if (o == null) { mark(dirty, n); COMMITTED.put(en.getKey(), n); changed = true; continue; }
            float dx = n[0] - o[0], dy = n[1] - o[1], dz = n[2] - o[2];
            float th = n[3] >= 15 ? 2f : 1.25f;
            if (dx * dx + dy * dy + dz * dz >= th * th) {
                mark(dirty, o); mark(dirty, n);
                COMMITTED.put(en.getKey(), n);
                changed = true;
            }
        }
        if (!changed) return;
        float[] arr = new float[COMMITTED.size() * 4];
        int i = 0;
        for (float[] v : COMMITTED.values()) { System.arraycopy(v, 0, arr, i, 4); i += 4; }
        SRC = arr;
        for (LongIterator li = dirty.iterator(); li.hasNext(); ) {
            long sp = li.nextLong();
            mc.levelRenderer.setSectionDirty(SectionPos.x(sp), SectionPos.y(sp), SectionPos.z(sp));
        }
    }

    /** 标记与光球（r + EDGE）相交的区块段 */
    private static void mark(LongOpenHashSet out, float[] s) {
        float R = s[3] + EDGE;
        int x0 = Mth.floor(s[0] - R) >> 4, x1 = Mth.floor(s[0] + R) >> 4;
        int y0 = Mth.floor(s[1] - R) >> 4, y1 = Mth.floor(s[1] + R) >> 4;
        int z0 = Mth.floor(s[2] - R) >> 4, z1 = Mth.floor(s[2] + R) >> 4;
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            float cx = Mth.clamp(s[0], x * 16, x * 16 + 16), cy = Mth.clamp(s[1], y * 16, y * 16 + 16), cz = Mth.clamp(s[2], z * 16, z * 16 + 16);
            float dx = cx - s[0], dy = cy - s[1], dz = cz - s[2];
            if (dx * dx + dy * dy + dz * dz <= R * R) out.add(SectionPos.asLong(x, y, z));
        }
    }

    /** 物品提示：光亮术剩余时间 */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Long u = e.getItemStack().get(ModComponents.LIGHT_UNTIL.get());
        if (u == null) return;
        long left = u - mc.level.getGameTime();
        if (left <= 0) return;
        long sec = left / 20;
        e.getToolTip().add(Component.translatable("tooltip.zhushenspace.light_until",
                String.format("%d:%02d", sec / 60, sec % 60)).withStyle(ChatFormatting.GOLD));
    }
}

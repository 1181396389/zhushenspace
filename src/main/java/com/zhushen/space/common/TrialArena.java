package com.zhushen.space.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * 新手试炼场景（代码生成，主神白色风格 + 发光边线，与大厅一致）。
 * 每名玩家一个实例：沿 +Z 排列 6 个房间，房间之间以浅蓝玻璃门分隔，完成当前房间目标后门打开。
 * <pre>
 *   房间 i 内部：x ∈ [-6, 6]，y ∈ [65, 70]，z ∈ [16i, 16i + 14]；z = 16i + 15 为隔墙（门洞 x ∈ [-1, 1]，y ∈ [65, 67]）
 * </pre>
 */
public final class TrialArena {
    private TrialArena() {}

    public static final int ROOMS = 6, LEN = 16, HALF = 6, FLOOR = 64, TOP = 71;
    public static final String TAG = "zs_trial", DUMMY_TAG = "zs_trial_dummy";

    public static int baseX(int slot) { return 4096 + slot * 128; }

    public static int roomZ(int i) { return i * LEN; }

    /** 实例包围盒（清理实体 / 判断是否在场景内） */
    public static AABB box(int slot) {
        int bx = baseX(slot);
        return new AABB(bx - HALF - 2, FLOOR - 8, -3, bx + HALF + 3, TOP + 4, ROOMS * LEN + 2);
    }

    /** 检查点：房间入口处，面朝南（+Z） */
    public static double[] checkpoint(int slot, int room) {
        return new double[]{baseX(slot) + 0.5, FLOOR + 1, roomZ(room) + 1.5};
    }

    /** 房间序号（按玩家 z 坐标） */
    public static int roomAt(double z) {
        return Math.max(0, Math.min(ROOMS - 1, (int) Math.floor(z / LEN)));
    }

    private static void set(ServerLevel l, int x, int y, int z, Block b) {
        l.setBlock(new BlockPos(x, y, z), b.defaultBlockState(), 2);
    }

    private static void set(ServerLevel l, int x, int y, int z, BlockState s) {
        l.setBlock(new BlockPos(x, y, z), s, 2);
    }

    /** 重建整个实例（幂等）：清空 → 外壳 → 房间布置 → 门 → 标语；同时清理范围内的旧实体 */
    public static void build(ServerLevel l, int slot, int gen) {
        int bx = baseX(slot);
        int zEnd = ROOMS * LEN - 1; // 末端外墙
        for (int x = -HALF - 1; x <= HALF + 1; x++) {
            for (int z = -1; z <= zEnd; z++) {
                boolean wallX = Math.abs(x) == HALF + 1, wallZ = z == -1 || z == zEnd;
                boolean sep = !wallZ && Math.floorMod(z + 1, LEN) == 0; // 隔墙
                for (int y = FLOOR; y <= TOP; y++) {
                    Block b;
                    if (y == FLOOR) {
                        // 地面：墙脚一圈发光边线
                        boolean edge = !wallX && !wallZ && !sep && (Math.abs(x) == HALF
                                || Math.floorMod(z, LEN) == 0 || Math.floorMod(z, LEN) == LEN - 2);
                        b = wallX || wallZ || sep ? Blocks.WHITE_CONCRETE : edge ? Blocks.SEA_LANTERN : Blocks.WHITE_CONCRETE;
                    } else if (y == TOP) {
                        // 顶棚：中轴灯带
                        b = x == 0 && !wallZ && !sep ? Blocks.SEA_LANTERN : Blocks.WHITE_CONCRETE;
                    } else if (wallX || wallZ || sep) {
                        // 墙面：顶部一圈发光边线
                        b = y == TOP - 1 ? Blocks.SEA_LANTERN : Blocks.WHITE_CONCRETE;
                    } else {
                        b = Blocks.AIR;
                    }
                    set(l, bx + x, y, z, b);
                }
            }
        }
        // 门（隔墙中央 3×3 浅蓝玻璃，两侧发光门框）
        for (int i = 0; i < ROOMS - 1; i++) closeDoor(l, slot, i);
        decorate(l, slot);
        clearEntities(l, slot, gen, true);
        labels(l, slot, gen);
    }

    private static void decorate(ServerLevel l, int slot) {
        int bx = baseX(slot);
        // 0 苏醒：四根石英灯柱
        int z0 = roomZ(0);
        for (int[] c : new int[][]{{-4, z0 + 3}, {4, z0 + 3}, {-4, z0 + 11}, {4, z0 + 11}}) {
            set(l, bx + c[0], FLOOR + 1, c[1], Blocks.QUARTZ_PILLAR);
            set(l, bx + c[0], FLOOR + 2, c[1], Blocks.QUARTZ_PILLAR);
            set(l, bx + c[0], FLOOR + 3, c[1], Blocks.SEA_LANTERN);
        }
        // 1 试用角色：中央光台 + 四座彩色角色台
        int z1 = roomZ(1);
        for (int x = -1; x <= 1; x++) for (int z = 5; z <= 7; z++) set(l, bx + x, FLOOR, z1 + z, Blocks.SEA_LANTERN);
        Block[] ped = {Blocks.RED_CONCRETE, Blocks.LIME_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE};
        for (int k = 0; k < 4; k++) {
            int x = -5 + k * 3 + (k >= 2 ? 1 : 0);
            set(l, bx + x, FLOOR + 1, z1 + 11, Blocks.QUARTZ_BLOCK);
            set(l, bx + x, FLOOR + 2, z1 + 11, ped[k]);
        }
        // 2 战斗 / 4 技艺：假人站位标记
        for (int r : new int[]{2, 4}) {
            int z = roomZ(r) + 10;
            for (int x = -1; x <= 1; x++) for (int dz = -1; dz <= 1; dz++)
                set(l, bx + x, FLOOR, z + dz, x == 0 && dz == 0 ? Blocks.TARGET : Blocks.RED_CONCRETE);
        }
        // 3 伤势：四角红色警示灯
        int z3 = roomZ(3);
        for (int[] c : new int[][]{{-HALF, z3 + 1}, {HALF, z3 + 1}, {-HALF, z3 + 13}, {HALF, z3 + 13}})
            set(l, bx + c[0], FLOOR, c[1], Blocks.SHROOMLIGHT);
        // 5 结算：传送台
        int z5 = roomZ(5) + 10;
        for (int x = -1; x <= 1; x++) for (int dz = -1; dz <= 1; dz++)
            set(l, bx + x, FLOOR, z5 + dz, x == 0 && dz == 0 ? Blocks.SEA_LANTERN : Blocks.LIGHT_BLUE_CONCRETE);
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            set(l, bx + c[0], FLOOR + 1, z5 + c[1], Blocks.QUARTZ_PILLAR);
            set(l, bx + c[0], FLOOR + 2, z5 + c[1], Blocks.END_ROD);
        }
    }

    /** 是否站在结算传送台上 */
    public static boolean onExitPad(int slot, double x, double z) {
        int z5 = roomZ(5) + 10;
        return Math.abs(x - (baseX(slot) + 0.5)) <= 1.6 && Math.abs(z - (z5 + 0.5)) <= 1.6;
    }

    public static boolean onPickPad(int slot, double x, double z) {
        int z1 = roomZ(1);
        return Math.abs(x - (baseX(slot) + 0.5)) <= 1.6 && z >= z1 + 5 && z < z1 + 8;
    }

    public static void closeDoor(ServerLevel l, int slot, int i) {
        int bx = baseX(slot), z = roomZ(i) + LEN - 1;
        for (int x = -1; x <= 1; x++) for (int y = FLOOR + 1; y <= FLOOR + 3; y++)
            set(l, bx + x, y, z, Blocks.LIGHT_BLUE_STAINED_GLASS);
        for (int y = FLOOR + 1; y <= FLOOR + 4; y++) {
            set(l, bx - 2, y, z, Blocks.SEA_LANTERN);
            set(l, bx + 2, y, z, Blocks.SEA_LANTERN);
        }
        for (int x = -1; x <= 1; x++) set(l, bx + x, FLOOR + 4, z, Blocks.SEA_LANTERN);
    }

    public static void openDoor(ServerLevel l, int slot, int i) {
        int bx = baseX(slot), z = roomZ(i) + LEN - 1;
        for (int x = -1; x <= 1; x++) for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
            l.setBlock(new BlockPos(bx + x, y, z), Blocks.AIR.defaultBlockState(), 3);
            l.sendParticles(ParticleTypes.END_ROD, bx + x + 0.5, y + 0.5, z + 0.5, 6, 0.3, 0.3, 0.3, 0.02);
        }
        l.playSound(null, new BlockPos(bx, FLOOR + 2, z), SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1f, 1.4f);
        l.playSound(null, new BlockPos(bx, FLOOR + 2, z), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.2f, 1.2f);
    }

    /** 每个房间上方的悬浮标语（文本展示实体，客户端按语言显示） */
    private static void labels(ServerLevel l, int slot, int gen) {
        int bx = baseX(slot);
        for (int i = 0; i < ROOMS; i++) {
            text(l, gen, bx + 0.5, FLOOR + 4.2, roomZ(i) + 7.5,
                    Component.translatable("trial.zhushenspace.room." + i).withStyle(s -> s.withColor(0x2A9FD6).withBold(true)));
            text(l, gen, bx + 0.5, FLOOR + 3.6, roomZ(i) + 7.5,
                    Component.translatable("trial.zhushenspace.room." + i + ".sub").withStyle(s -> s.withColor(0x445566)));
        }
    }

    private static void text(ServerLevel l, int gen, double x, double y, double z, Component c) {
        CompoundTag t = new CompoundTag();
        t.putString("id", "minecraft:text_display");
        t.putString("text", Component.Serializer.toJson(c, l.registryAccess()));
        t.putString("billboard", "center");
        t.putInt("background", 0x00000000);
        t.putBoolean("shadow", false);
        t.putInt("line_width", 260);
        Entity e = EntityType.loadEntityRecursive(t, l, en -> { en.moveTo(x, y, z, 0, 0); return en; });
        if (e == null) return;
        e.addTag(TAG);
        e.addTag(TAG + "_" + gen);
        l.addFreshEntity(e);
    }

    /** 清理实例内的旧实体：all = 除玩家外全部清除；否则只清除不属于当前代数的试炼实体 */
    public static void clearEntities(ServerLevel l, int slot, int gen, boolean all) {
        String cur = TAG + "_" + gen;
        for (Entity e : l.getEntities((Entity) null, box(slot), en -> !(en instanceof Player))) {
            boolean ours = e.getTags().contains(TAG) || e.getTags().contains(DUMMY_TAG);
            if (all ? !e.getTags().contains(cur) : ours && !e.getTags().contains(cur)) e.discard();
        }
    }

    /** 生成训练假人（无 AI、不会还手、受伤即回满） */
    public static UUID spawnDummy(ServerLevel l, int slot, int room, int gen) {
        Husk h = EntityType.HUSK.create(l);
        if (h == null) return null;
        h.setNoAi(true);
        h.setSilent(true);
        h.setPersistenceRequired();
        h.setCustomName(Component.translatable("entity.zhushenspace.trial_dummy"));
        h.setCustomNameVisible(true);
        var hp = h.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(200);
        var kb = h.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.setBaseValue(1);
        h.setHealth(h.getMaxHealth());
        h.moveTo(baseX(slot) + 0.5, FLOOR + 1, roomZ(room) + 10.5, 180, 0);
        h.setYHeadRot(180);
        h.addTag(DUMMY_TAG);
        h.addTag(TAG + "_" + gen);
        l.addFreshEntity(h);
        return h.getUUID();
    }
}

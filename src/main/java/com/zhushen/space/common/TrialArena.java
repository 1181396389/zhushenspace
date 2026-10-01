package com.zhushen.space.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * 新手试炼场景（代码生成，主神白色风格 + 发光边线，与大厅一致）。
 * 每名玩家一个实例：沿 +Z 排列 10 个房间，房间之间以浅蓝玻璃门分隔，完成当前房间目标后门打开。
 * <pre>
 *   房间 i 内部：x ∈ [-6, 6]，y ∈ [65, 70]，z ∈ [16i, 16i + 14]；z = 16i + 15 为隔墙（门洞 x ∈ [-1, 1]，y ∈ [65, 67]）
 *   0 苏醒 · 1 试用角色 · 2 移动与地形 · 3 战斗模式 · 4 防御 · 5 伤势与状态 · 6 能量池与技艺 · 7 综合战 · 8 休息与恢复 · 9 结算
 * </pre>
 */
public final class TrialArena {
    private TrialArena() {}

    public static final int ROOMS = 10, LEN = 16, HALF = 6, FLOOR = 64, TOP = 71;
    public static final String TAG = "zs_trial", DUMMY_TAG = "zs_trial_dummy", PUPPET_TAG = "zs_trial_puppet",
            MOB_TAG = "zs_trial_mob", BOSS_TAG = "zs_trial_boss";

    /** 房间序号（与 TrialManager 的关卡常量一致） */
    public static final int R_AWAKE = 0, R_PICK = 1, R_TERRAIN = 2, R_COMBAT = 3, R_DEFENSE = 4, R_WOUNDS = 5,
            R_ARTS = 6, R_BATTLE = 7, R_REST = 8, R_FINISH = 9;

    /** 地形关：蛛网 + 灵魂沙带（z 偏移）、高墙（z 偏移，厚 2 格，高 4 格）、深沟（z 偏移，宽 2 格）、终点平台 */
    public static final int T_SAND0 = 2, T_SAND1 = 4, T_WEB = 3, T_WALL0 = 7, T_WALL1 = 8, T_WALL_H = 4,
            T_PIT0 = 10, T_PIT1 = 11, T_PLAT0 = 13;

    public static int baseX(int slot) { return 4096 + slot * 128; }

    public static int roomZ(int i) { return i * LEN; }

    /** 实例包围盒（清理实体 / 判断是否在场景内） */
    public static AABB box(int slot) {
        int bx = baseX(slot);
        return new AABB(bx - HALF - 2, FLOOR - 8, -3, bx + HALF + 3, TOP + 4, ROOMS * LEN + 2);
    }

    /** 房间包围盒（只含房间内部） */
    public static AABB roomBox(int slot, int room) {
        int bx = baseX(slot), z = roomZ(room);
        return new AABB(bx - HALF, FLOOR - 4, z, bx + HALF + 1, TOP, z + LEN - 1);
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
        int z0 = roomZ(R_AWAKE);
        for (int[] c : new int[][]{{-4, z0 + 3}, {4, z0 + 3}, {-4, z0 + 11}, {4, z0 + 11}}) {
            set(l, bx + c[0], FLOOR + 1, c[1], Blocks.QUARTZ_PILLAR);
            set(l, bx + c[0], FLOOR + 2, c[1], Blocks.QUARTZ_PILLAR);
            set(l, bx + c[0], FLOOR + 3, c[1], Blocks.SEA_LANTERN);
        }
        // 1 试用角色：中央光台 + 四座彩色角色台
        int z1 = roomZ(R_PICK);
        for (int x = -1; x <= 1; x++) for (int z = 5; z <= 7; z++) set(l, bx + x, FLOOR, z1 + z, Blocks.SEA_LANTERN);
        Block[] ped = {Blocks.RED_CONCRETE, Blocks.LIME_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE};
        for (int k = 0; k < 4; k++) {
            int x = -5 + k * 3 + (k >= 2 ? 1 : 0);
            set(l, bx + x, FLOOR + 1, z1 + 11, Blocks.QUARTZ_BLOCK);
            set(l, bx + x, FLOOR + 2, z1 + 11, ped[k]);
        }
        terrain(l, slot);
        // 3 战斗 / 6 技艺：假人站位标记
        for (int r : new int[]{R_COMBAT, R_ARTS}) {
            int z = roomZ(r) + 10;
            for (int x = -1; x <= 1; x++) for (int dz = -1; dz <= 1; dz++)
                set(l, bx + x, FLOOR, z + dz, x == 0 && dz == 0 ? Blocks.TARGET : Blocks.RED_CONCRETE);
        }
        // 4 防御：傀儡站位（橡木圆台）+ 两侧盾形标记
        int z4 = roomZ(R_DEFENSE) + 9;
        for (int x = -1; x <= 1; x++) for (int dz = -1; dz <= 1; dz++)
            set(l, bx + x, FLOOR, z4 + dz, x == 0 && dz == 0 ? Blocks.STRIPPED_OAK_LOG : Blocks.OAK_PLANKS);
        for (int side : new int[]{-5, 5}) {
            set(l, bx + side, FLOOR + 1, z4, Blocks.IRON_BLOCK);
            set(l, bx + side, FLOOR + 2, z4, Blocks.LIGHT_BLUE_STAINED_GLASS);
        }
        // 5 伤势：四角红色警示灯
        int z5 = roomZ(R_WOUNDS);
        for (int[] c : new int[][]{{-HALF, z5 + 1}, {HALF, z5 + 1}, {-HALF, z5 + 13}, {HALF, z5 + 13}})
            set(l, bx + c[0], FLOOR, c[1], Blocks.SHROOMLIGHT);
        // 7 综合战：四根掩体石柱 + 场地边缘红线
        int z7 = roomZ(R_BATTLE);
        for (int[] c : new int[][]{{-3, 6}, {3, 6}, {-3, 10}, {3, 10}}) {
            for (int y = 1; y <= 3; y++) set(l, bx + c[0], FLOOR + y, z7 + c[1], y == 3 ? Blocks.CHISELED_QUARTZ_BLOCK : Blocks.QUARTZ_PILLAR);
        }
        for (int x = -HALF + 1; x <= HALF - 1; x++) set(l, bx + x, FLOOR, z7 + 3, Blocks.RED_CONCRETE);
        // 8 休息：床（摆设）、营火、水池、地毯
        int z8 = roomZ(R_REST);
        set(l, bx - 5, FLOOR + 1, z8 + 11, Blocks.WHITE_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT));
        set(l, bx - 5, FLOOR + 1, z8 + 12, Blocks.WHITE_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD));
        set(l, bx + 5, FLOOR + 1, z8 + 12, Blocks.CAMPFIRE);
        for (int x = 4; x <= 5; x++) for (int dz = 4; dz <= 5; dz++) {
            set(l, bx + x, FLOOR - 1, z8 + dz, Blocks.WHITE_CONCRETE);
            set(l, bx + x, FLOOR, z8 + dz, Blocks.WATER);
        }
        for (int x = -2; x <= 2; x++) for (int dz = 7; dz <= 9; dz++)
            set(l, bx + x, FLOOR + 1, z8 + dz, (x + dz) % 2 == 0 ? Blocks.LIGHT_BLUE_CARPET : Blocks.WHITE_CARPET);
        // 9 结算：传送台
        int z9 = roomZ(R_FINISH) + 10;
        for (int x = -1; x <= 1; x++) for (int dz = -1; dz <= 1; dz++)
            set(l, bx + x, FLOOR, z9 + dz, x == 0 && dz == 0 ? Blocks.SEA_LANTERN : Blocks.LIGHT_BLUE_CONCRETE);
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            set(l, bx + c[0], FLOOR + 1, z9 + c[1], Blocks.QUARTZ_PILLAR);
            set(l, bx + c[0], FLOOR + 2, z9 + c[1], Blocks.END_ROD);
        }
    }

    /**
     * 2 移动与地形：灵魂沙 + 一排蜘蛛网（困难地形）→ 挂着藤蔓的高墙（攀爬）→ 两格宽深沟（跳跃，
     * 掉下去可以顺梯子爬回来）→ 抬高的终点平台。整条路线横贯房间，无法绕开。
     */
    private static void terrain(ServerLevel l, int slot) {
        int bx = baseX(slot), z = roomZ(R_TERRAIN);
        for (int x = -HALF; x <= HALF; x++) {
            for (int dz = T_SAND0; dz <= T_SAND1; dz++) set(l, bx + x, FLOOR, z + dz, Blocks.SOUL_SAND);
            set(l, bx + x, FLOOR + 1, z + T_WEB, Blocks.COBWEB);
            for (int dz = T_WALL0; dz <= T_WALL1; dz++)
                for (int y = 1; y <= T_WALL_H; y++)
                    set(l, bx + x, FLOOR + y, z + dz, y == T_WALL_H ? Blocks.SMOOTH_QUARTZ : Blocks.WHITE_CONCRETE);
            // 深沟：地面挖空，下方 3 格深，四周与底部封闭
            set(l, bx + x, FLOOR - 3, z + T_PIT0 - 1, Blocks.WHITE_CONCRETE);
            set(l, bx + x, FLOOR - 3, z + T_PIT1 + 1, Blocks.WHITE_CONCRETE);
            for (int dz = T_PIT0; dz <= T_PIT1; dz++) {
                set(l, bx + x, FLOOR - 3, z + dz, Blocks.LIGHT_BLUE_CONCRETE);
                set(l, bx + x, FLOOR - 2, z + dz, Blocks.AIR);
                set(l, bx + x, FLOOR - 1, z + dz, Blocks.AIR);
                set(l, bx + x, FLOOR, z + dz, Blocks.AIR);
            }
            for (int y = FLOOR - 2; y <= FLOOR - 1; y++) {
                set(l, bx + x, y, z + T_PIT0 - 1, Blocks.WHITE_CONCRETE);
                set(l, bx + x, y, z + T_PIT1 + 1, Blocks.WHITE_CONCRETE);
            }
            // 终点平台（高 1 格，中央发光）
            for (int dz = T_PLAT0; dz <= LEN - 2; dz++)
                set(l, bx + x, FLOOR + 1, z + dz, x == 0 ? Blocks.SEA_LANTERN : Blocks.SMOOTH_QUARTZ);
        }
        for (int side : new int[]{-HALF - 1, HALF + 1})
            for (int dz = T_PIT0; dz <= T_PIT1; dz++)
                for (int y = FLOOR - 3; y <= FLOOR - 1; y++) set(l, bx + side, y, z + dz, Blocks.WHITE_CONCRETE);
        // 藤蔓：贴在高墙北面（中间 3 列），一直到墙顶
        for (int x = -1; x <= 1; x++)
            for (int y = 1; y <= T_WALL_H; y++)
                set(l, bx + x, FLOOR + y, z + T_WALL0 - 1, Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true));
        // 沟底梯子：贴在沟的北壁，爬回起跳一侧
        for (int y = FLOOR - 2; y <= FLOOR; y++)
            set(l, bx, y, z + T_PIT0, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        // 沟边警示线
        for (int x = -HALF; x <= HALF; x++) set(l, bx + x, FLOOR, z + T_PIT0 - 1, Blocks.YELLOW_CONCRETE);
    }

    /** 是否站在结算传送台上 */
    public static boolean onExitPad(int slot, double x, double z) {
        int z9 = roomZ(R_FINISH) + 10;
        return Math.abs(x - (baseX(slot) + 0.5)) <= 1.6 && Math.abs(z - (z9 + 0.5)) <= 1.6;
    }

    public static boolean onPickPad(int slot, double x, double z) {
        int z1 = roomZ(R_PICK);
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
            // 地形关的标语挂在入口上方，避免被高墙挡住
            double lz = i == R_TERRAIN ? roomZ(i) + 1.5 : roomZ(i) + 7.5;
            double ly = i == R_TERRAIN ? FLOOR + 5.2 : FLOOR + 4.2;
            text(l, gen, bx + 0.5, ly, lz,
                    Component.translatable("trial.zhushenspace.room." + i).withStyle(s -> s.withColor(0x2A9FD6).withBold(true)));
            text(l, gen, bx + 0.5, ly - 0.6, lz,
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

    /** 不会受伤死亡的教学实体（训练假人 / 木桩傀儡） */
    public static boolean invulnerable(Entity e) {
        return e.getTags().contains(DUMMY_TAG) || e.getTags().contains(PUPPET_TAG);
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
        h.addTag(TAG);
        h.addTag(TAG + "_" + gen);
        l.addFreshEntity(h);
        return h.getUUID();
    }

    /** 防御关的木桩傀儡：不会移动、不会死亡，由 TrialManager 控制出手节奏（伤害很低、不致死） */
    public static UUID spawnPuppet(ServerLevel l, int slot, int gen) {
        Zombie z = EntityType.ZOMBIE.create(l);
        if (z == null) return null;
        z.setNoAi(true);
        z.setPersistenceRequired();
        z.setCanPickUpLoot(false);
        z.setCustomName(Component.translatable("entity.zhushenspace.trial_puppet"));
        z.setCustomNameVisible(true);
        z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.OAK_LOG));
        z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
        for (EquipmentSlot s : EquipmentSlot.values()) z.setDropChance(s, 0f);
        var hp = z.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(200);
        var kb = z.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.setBaseValue(1);
        z.setHealth(z.getMaxHealth());
        z.moveTo(baseX(slot) + 0.5, FLOOR + 1, roomZ(R_DEFENSE) + 9.5, 180, 0);
        z.setYHeadRot(180);
        z.addTag(PUPPET_TAG);
        z.addTag(TAG);
        z.addTag(TAG + "_" + gen);
        l.addFreshEntity(z);
        return z.getUUID();
    }

    /** 综合战的普通敌人：没有随机装备的成年僵尸，生命略低 */
    public static UUID spawnMinion(ServerLevel l, int slot, int gen, int idx) {
        Zombie z = EntityType.ZOMBIE.create(l);
        if (z == null) return null;
        z.setPersistenceRequired();
        z.setCanPickUpLoot(false);
        z.setBaby(false);
        var hp = z.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(14);
        z.setHealth(z.getMaxHealth());
        int[][] pos = {{-3, 12}, {0, 13}, {3, 12}};
        int[] p = pos[Math.floorMod(idx, pos.length)];
        z.moveTo(baseX(slot) + p[0] + 0.5, FLOOR + 1, roomZ(R_BATTLE) + p[1] + 0.5, 180, 0);
        z.setYHeadRot(180);
        z.addTag(MOB_TAG);
        z.addTag(TAG);
        z.addTag(TAG + "_" + gen);
        l.addFreshEntity(z);
        l.sendParticles(ParticleTypes.POOF, z.getX(), z.getY() + 1, z.getZ(), 12, 0.3, 0.6, 0.3, 0.02);
        return z.getUUID();
    }

    /** 综合战的小头目：T 病毒丧尸（数值下调到新手可以应付的程度） */
    public static UUID spawnBoss(ServerLevel l, int slot, int gen) {
        var type = com.zhushen.space.entity.ModEntities.T_VIRUS_ZOMBIE.get();
        var z = type.create(l);
        if (z == null) return null;
        z.setPersistenceRequired();
        z.setCanPickUpLoot(false);
        z.setCustomName(Component.translatable("entity.zhushenspace.trial_boss"));
        z.setCustomNameVisible(true);
        var hp = z.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(32);
        var dmg = z.getAttribute(Attributes.ATTACK_DAMAGE);
        if (dmg != null) dmg.setBaseValue(4);
        var spd = z.getAttribute(Attributes.MOVEMENT_SPEED);
        if (spd != null) spd.setBaseValue(0.22);
        z.setHealth(z.getMaxHealth());
        z.moveTo(baseX(slot) + 0.5, FLOOR + 1, roomZ(R_BATTLE) + 12.5, 180, 0);
        z.setYHeadRot(180);
        z.addTag(BOSS_TAG);
        z.addTag(MOB_TAG);
        z.addTag(TAG);
        z.addTag(TAG + "_" + gen);
        l.addFreshEntity(z);
        l.sendParticles(ParticleTypes.LARGE_SMOKE, z.getX(), z.getY() + 1, z.getZ(), 30, 0.5, 0.8, 0.5, 0.03);
        l.playSound(null, z.blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.HOSTILE, 1f, 0.6f);
        return z.getUUID();
    }
}

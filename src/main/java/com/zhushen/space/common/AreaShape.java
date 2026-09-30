package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 范围（规则书「范围说明」）：
 * <ul>
 *   <li>所有范围能力都是<b>范围效果</b>，其造成的伤害视为<b>范围伤害</b>（用于倒地 +3 反射等判定）。</li>
 *   <li>范围效果从来源处生效，受影响的单位必须与来源之间存在<b>效果线</b>（没有实体方块阻隔），
 *       若能力需要，还需要<b>视线</b>。</li>
 *   <li><b>持续区域</b>：固定在某处，或随某一目标移动；单位进入区域时获得效果，离开时效果移除。</li>
 *   <li>形状：直线（长度 + 宽度）、圆（平面半径）、球（半径）、柱（半径 + 高度）、
 *       锥（长度，末端宽度等于长度）、方（平面边长）、立方（边长）。</li>
 * </ul>
 * 1 米 = 1 格。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class AreaShape {

    public enum Kind { LINE, CIRCLE, SPHERE, CYLINDER, CONE, SQUARE, CUBE }

    public final Kind kind;
    /** 半径 / 长度 / 边长 */
    public final double size;
    /** 宽度（直线）/ 高度（圆、柱、方） */
    public final double extra;

    private AreaShape(Kind kind, double size, double extra) {
        this.kind = kind;
        this.size = size;
        this.extra = extra;
    }

    public static AreaShape line(double length, double width) { return new AreaShape(Kind.LINE, length, Math.max(0.5, width)); }
    public static AreaShape circle(double radius) { return new AreaShape(Kind.CIRCLE, radius, 1.0); }
    public static AreaShape sphere(double radius) { return new AreaShape(Kind.SPHERE, radius, 0); }
    public static AreaShape cylinder(double radius, double height) { return new AreaShape(Kind.CYLINDER, radius, height); }
    public static AreaShape cone(double length) { return new AreaShape(Kind.CONE, length, 0); }
    public static AreaShape square(double side) { return new AreaShape(Kind.SQUARE, side, 1.0); }
    public static AreaShape cube(double side) { return new AreaShape(Kind.CUBE, side, 0); }

    /**
     * 点是否在区域内。
     *
     * @param origin 区域原点（直线 / 锥为起点，其余为中心；圆 / 柱 / 方为底面中心）
     * @param dir    朝向（直线 / 锥使用）
     */
    public boolean contains(Vec3 origin, Vec3 dir, Vec3 pt) {
        Vec3 d = pt.subtract(origin);
        switch (kind) {
            case SPHERE:
                return d.lengthSqr() <= size * size;
            case CIRCLE:
            case CYLINDER:
                return d.y >= -0.5 && d.y <= extra && d.x * d.x + d.z * d.z <= size * size;
            case SQUARE:
                return d.y >= -0.5 && d.y <= extra && Math.abs(d.x) <= size / 2 && Math.abs(d.z) <= size / 2;
            case CUBE:
                return Math.abs(d.x) <= size / 2 && Math.abs(d.y) <= size / 2 && Math.abs(d.z) <= size / 2;
            case LINE: {
                Vec3 n = dir.normalize();
                double along = d.dot(n);
                if (along < 0 || along > size) return false;
                return d.subtract(n.scale(along)).length() <= extra / 2;
            }
            case CONE: {
                // 末端宽度等于长度：半角 = atan(0.5)
                Vec3 n = dir.normalize();
                double along = d.dot(n);
                if (along < 0 || along > size) return false;
                return d.subtract(n.scale(along)).length() <= along / 2 + 0.3;
            }
            default:
                return false;
        }
    }

    /** 区域外接盒 */
    public AABB bounds(Vec3 origin, Vec3 dir) {
        double r = switch (kind) {
            case LINE, CONE -> size + extra;
            case SQUARE, CUBE -> size * 0.75;
            default -> size;
        };
        double up = kind == Kind.CYLINDER || kind == Kind.CIRCLE || kind == Kind.SQUARE ? Math.max(r, extra) : r;
        return new AABB(origin.x - r, origin.y - r, origin.z - r, origin.x + r, origin.y + up, origin.z + r);
    }

    /** 效果线：从 from 到目标之间没有实体方块阻隔 */
    public static boolean effectLine(ServerLevel level, Vec3 from, LivingEntity t, Entity source) {
        AABB bb = t.getBoundingBox();
        Vec3[] pts = {bb.getCenter(), t.getEyePosition(), new Vec3(bb.getCenter().x, bb.minY + 0.1, bb.getCenter().z)};
        for (Vec3 to : pts) {
            HitResult r = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    source == null ? net.minecraft.world.phys.shapes.CollisionContext.empty() : net.minecraft.world.phys.shapes.CollisionContext.of(source)));
            if (r.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }

    /** 视线：来源能看见目标（效果线 + 目标未隐身） */
    public static boolean lineOfSight(ServerLevel level, LivingEntity source, LivingEntity t) {
        if (!effectLine(level, source.getEyePosition(), t, source)) return false;
        return source instanceof net.minecraft.world.entity.player.Player pl ? !t.isInvisibleTo(pl) : !t.isInvisible();
    }

    /**
     * 收集区域内受影响的单位。
     *
     * @param needSight 能力是否需要视线
     */
    public List<LivingEntity> collect(ServerLevel level, Vec3 origin, Vec3 dir, LivingEntity source, boolean needSight,
                                      Predicate<LivingEntity> filter) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity t : level.getEntitiesOfClass(LivingEntity.class, bounds(origin, dir), e -> e.isAlive() && e != source)) {
            if (filter != null && !filter.test(t)) continue;
            AABB bb = t.getBoundingBox();
            Vec3 c = bb.getCenter();
            boolean in = contains(origin, dir, c) || contains(origin, dir, new Vec3(c.x, bb.minY + 0.1, c.z))
                    || contains(origin, dir, t.getEyePosition());
            if (!in) continue;
            if (!effectLine(level, origin, t, source)) continue;
            if (needSight && source != null && !lineOfSight(level, source, t)) continue;
            out.add(t);
        }
        return out;
    }

    // ---------------------------------------------------------------- 持续区域

    /** 持续区域：固定位置或跟随某个实体；进入时 onEnter，离开（或区域结束）时 onLeave，区域内每轮 onRound */
    public static final class Persistent {
        final ServerLevel level;
        final AreaShape shape;
        final Supplier<Vec3> origin;
        final Supplier<Vec3> dir;
        final LivingEntity source;
        final boolean needSight;
        final long until;
        final Consumer<LivingEntity> onEnter, onLeave, onRound;
        final Set<UUID> inside = new HashSet<>();
        Entity anchor;
        boolean dead;

        Persistent(ServerLevel level, AreaShape shape, Supplier<Vec3> origin, Supplier<Vec3> dir, LivingEntity source,
                   boolean needSight, long until, Consumer<LivingEntity> onEnter, Consumer<LivingEntity> onLeave,
                   Consumer<LivingEntity> onRound) {
            this.level = level;
            this.shape = shape;
            this.origin = origin;
            this.dir = dir;
            this.source = source;
            this.needSight = needSight;
            this.until = until;
            this.onEnter = onEnter;
            this.onLeave = onLeave;
            this.onRound = onRound;
        }

        /** 提前结束区域：区域内所有单位的效果移除 */
        public void end() {
            dead = true;
        }
    }

    private static final List<Persistent> AREAS = new ArrayList<>();

    /** 创建固定位置的持续区域 */
    public static Persistent fixed(ServerLevel level, AreaShape shape, Vec3 origin, Vec3 dir, LivingEntity source, boolean needSight,
                                   int ticks, Consumer<LivingEntity> onEnter, Consumer<LivingEntity> onLeave, Consumer<LivingEntity> onRound) {
        Persistent a = new Persistent(level, shape, () -> origin, () -> dir, source, needSight, level.getGameTime() + ticks,
                onEnter, onLeave, onRound);
        AREAS.add(a);
        return a;
    }

    /** 创建跟随目标移动的持续区域（目标死亡 / 移除时区域结束） */
    public static Persistent follow(ServerLevel level, AreaShape shape, Entity anchor, LivingEntity source, boolean needSight,
                                    int ticks, Consumer<LivingEntity> onEnter, Consumer<LivingEntity> onLeave, Consumer<LivingEntity> onRound) {
        Persistent a = new Persistent(level, shape, anchor::position, () -> anchor.getViewVector(1f), source, needSight,
                level.getGameTime() + ticks, onEnter, onLeave, onRound);
        a.anchor = anchor;
        AREAS.add(a);
        return a;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel level) || AREAS.isEmpty()) return;
        long now = level.getGameTime();
        Iterator<Persistent> it = AREAS.iterator();
        while (it.hasNext()) {
            Persistent a = it.next();
            if (a.level != level) continue;
            boolean over = a.dead || now >= a.until || (a.anchor != null && a.anchor.isRemoved());
            Set<UUID> now_in = new HashSet<>();
            List<LivingEntity> found = over ? List.of() : a.shape.collect(level, a.origin.get(), a.dir.get(), a.source, a.needSight, null);
            for (LivingEntity t : found) {
                now_in.add(t.getUUID());
                if (a.inside.add(t.getUUID()) && a.onEnter != null) a.onEnter.accept(t);
                if (a.onRound != null && now % StatusManager.ROUND == 0) a.onRound.accept(t);
            }
            for (Iterator<UUID> i2 = a.inside.iterator(); i2.hasNext(); ) {
                UUID id = i2.next();
                if (now_in.contains(id)) continue;
                i2.remove();
                if (a.onLeave != null && level.getEntity(id) instanceof LivingEntity t) a.onLeave.accept(t);
            }
            if (over) it.remove();
        }
    }
}

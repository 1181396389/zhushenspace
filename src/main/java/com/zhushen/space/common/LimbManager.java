package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.LimbPart;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerLimbData;
import com.zhushen.space.network.SyncLimbPayload;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家肢体血量与断肢系统。
 * <ul>
 *   <li><b>命中部位</b>：弹射物按命中点；近战按攻击者视线与受击者碰撞箱的交点；
 *       摔落 → 腿；其他带位置的伤害（爆炸等）按来源高度；无位置的伤害（火烧、中毒、饥饿、溺水……）→ 躯干</li>
 *   <li><b>部位护甲</b>：该次受击只计算覆盖该部位的盔甲——头盔护头；胸甲护躯干与双臂；护腿与靴子护双腿。
 *       非盔甲来源的护甲（敏捷加点、技能、意志守御等）对全身有效</li>
 *   <li><b>部位血量</b>：最终伤害同时记入总伤势（B/L/A，{@link HealthManager}）与命中部位；躯干只记总伤势</li>
 *   <li><b>头</b>：血量清空 → 昏迷（死亡仍只由「全身恶性满载」决定）</li>
 *   <li><b>四肢</b>：血量清空 → 断肢（任何伤害都可以造成）。
 *       断臂：该手物品无法再互动（攻击 / 使用 / 放置 / 交互 / 挖掘 / 换手）；
 *       断腿：一条 移速 −50%、跳跃 −50%，两条 移速 −85%、无法跳跃。
 *       已断的部位不会再被命中（改为躯干承受）</li>
 *   <li><b>恢复</b>：管理员指令 /zhushen limb restore；治疗（原版回血转化的伤势移除）同时回复未断部位的血量（头优先）</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class LimbManager {

    private LimbManager() {
    }

    private static final ResourceLocation PART_ARMOR_ID = id("limb_part_armor");
    private static final ResourceLocation PART_TOUGH_ID = id("limb_part_toughness");
    private static final ResourceLocation LEG_SPEED_ID = id("limb_leg_speed");
    private static final ResourceLocation LEG_JUMP_ID = id("limb_leg_jump");

    private static final DustParticleOptions BLOOD_MIST = new DustParticleOptions(new Vector3f(0.55f, 0.02f, 0.03f), 1.4f);
    private static final BlockParticleOption BLOOD_DROP =
            new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, path);
    }

    /** 本次受击的命中部位（null = 躯干），Incoming 时判定、Post 时结算 */
    private record Pending(LimbPart part, long tick) {
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private static long now(ServerPlayer p) {
        return p.getServer() == null ? 0 : p.getServer().getTickCount();
    }

    // ===== 查询 =====

    public static PlayerLimbData data(Player player) {
        return player.getData(ModAttachments.PLAYER_LIMBS);
    }

    /** 头部血量清空（昏迷条件之一） */
    public static boolean headOut(ServerPlayer player) {
        return data(player).current(LimbPart.HEAD, player.getMaxHealth()) <= 0;
    }

    /** 某只手对应的手臂是否已断 */
    public static boolean handSevered(Player player, InteractionHand hand) {
        return data(player).isSevered(armOf(player, hand));
    }

    public static LimbPart armOf(Player player, InteractionHand hand) {
        boolean rightMain = player.getMainArm() == HumanoidArm.RIGHT;
        boolean right = (hand == InteractionHand.MAIN_HAND) == rightMain;
        return right ? LimbPart.RIGHT_ARM : LimbPart.LEFT_ARM;
    }

    // ===== 命中部位判定 =====

    /** 判定命中部位：null = 躯干 */
    public static LimbPart resolvePart(ServerPlayer victim, DamageSource src) {
        if (src.is(DamageTypeTags.IS_FALL)) {
            return pickLeg(victim, 0);
        }
        Vec3 point = null;
        if (src.getDirectEntity() instanceof Projectile proj) {
            point = proj.position();
        } else if (src.getDirectEntity() instanceof LivingEntity att && att != victim) {
            Vec3 eye = att.getEyePosition();
            Vec3 end = eye.add(att.getViewVector(1f).scale(8));
            AABB box = victim.getBoundingBox().inflate(0.2);
            point = box.clip(eye, end).orElse(null);
            if (point == null) {
                double y = Math.max(victim.getY(), Math.min(victim.getY() + victim.getBbHeight(), att.getEyeY()));
                point = new Vec3(att.getX(), y, att.getZ());
            }
        } else if (src.getSourcePosition() != null) {
            Vec3 sp = src.getSourcePosition();
            double y = Math.max(victim.getY(), Math.min(victim.getY() + victim.getBbHeight(), sp.y));
            point = new Vec3(sp.x, y, sp.z);
        }
        if (point == null) return null; // 无位置伤害：躯干

        double rel = (point.y - victim.getY()) / Math.max(0.1, victim.getBbHeight());
        double yaw = Math.toRadians(victim.yBodyRot);
        // 实体朝向 = (-sin, 0, cos)，右侧 = (-cos, 0, -sin)
        double lateral = (point.x - victim.getX()) * -Math.cos(yaw) + (point.z - victim.getZ()) * -Math.sin(yaw);

        LimbPart part;
        if (rel >= 0.78) {
            part = LimbPart.HEAD;
        } else if (rel >= 0.40) {
            if (Math.abs(lateral) < 0.20) return null;
            part = lateral > 0 ? LimbPart.RIGHT_ARM : LimbPart.LEFT_ARM;
        } else {
            part = pickLeg(victim, lateral);
        }
        // 已断的部位不会再被命中
        if (part != null && data(victim).isSevered(part)) {
            return part.isLeg() ? pickLeg(victim, 0) : null;
        }
        return part;
    }

    /** 攻击判定用的目标防御：护甲值（只计覆盖命中部位的盔甲） */
    public static double defenseFor(ServerPlayer victim, DamageSource src) {
        double armor = victim.getAttributeValue(Attributes.ARMOR);
        if (src.is(DamageTypeTags.BYPASSES_ARMOR)) return 0;
        LimbPart part = resolvePart(victim, src);
        return Math.max(0, armor - uncoveredArmor(victim, part)[0]);
    }

    /** 选一条未断的腿（lateral 指示偏向，0 = 随机）；两腿皆断 → 躯干 */
    private static LimbPart pickLeg(ServerPlayer victim, double lateral) {
        PlayerLimbData d = data(victim);
        boolean r = !d.isSevered(LimbPart.RIGHT_LEG), l = !d.isSevered(LimbPart.LEFT_LEG);
        if (!r && !l) return null;
        if (r && l) {
            if (Math.abs(lateral) < 0.03) return victim.getRandom().nextBoolean() ? LimbPart.RIGHT_LEG : LimbPart.LEFT_LEG;
            return lateral > 0 ? LimbPart.RIGHT_LEG : LimbPart.LEFT_LEG;
        }
        return r ? LimbPart.RIGHT_LEG : LimbPart.LEFT_LEG;
    }

    // ===== 部位护甲 =====

    private static List<EquipmentSlot> coverOf(LimbPart part) {
        if (part == null || part.isArm()) return List.of(EquipmentSlot.CHEST);
        if (part == LimbPart.HEAD) return List.of(EquipmentSlot.HEAD);
        return List.of(EquipmentSlot.LEGS, EquipmentSlot.FEET);
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** 不覆盖该部位的盔甲提供的护甲 / 韧性（本次受击需要扣除） */
    private static double[] uncoveredArmor(ServerPlayer player, LimbPart part) {
        List<EquipmentSlot> cover = coverOf(part);
        double[] sum = new double[2];
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (cover.contains(slot)) continue;
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            stack.forEachModifier(slot, (Holder<Attribute> attr, AttributeModifier mod) -> {
                if (mod.operation() != AttributeModifier.Operation.ADD_VALUE) return;
                if (attr.value() == Attributes.ARMOR.value()) sum[0] += mod.amount();
                else if (attr.value() == Attributes.ARMOR_TOUGHNESS.value()) sum[1] += mod.amount();
            });
        }
        return sum;
    }

    private static void setModifier(ServerPlayer player, Holder<Attribute> attr, ResourceLocation id, double amount,
                                    AttributeModifier.Operation op) {
        AttributeInstance inst = player.getAttribute(attr);
        if (inst == null) return;
        inst.removeModifier(id);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(id, amount, op));
    }

    private static void clearPartArmor(ServerPlayer player) {
        setModifier(player, Attributes.ARMOR, PART_ARMOR_ID, 0, AttributeModifier.Operation.ADD_VALUE);
        setModifier(player, Attributes.ARMOR_TOUGHNESS, PART_TOUGH_ID, 0, AttributeModifier.Operation.ADD_VALUE);
    }

    // ===== 受击结算 =====

    private static boolean bypass(DamageSource src) {
        return src.is(DamageTypes.GENERIC_KILL) || src.is(DamageTypes.FELL_OUT_OF_WORLD);
    }

    /** 数值阶段末尾：判定部位，并临时扣除不覆盖该部位的盔甲 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onIncoming(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || bypass(event.getSource())) return;
        LimbPart part = resolvePart(player, event.getSource());
        PENDING.put(player.getUUID(), new Pending(part, now(player)));
        if (!event.getSource().is(DamageTypeTags.BYPASSES_ARMOR)) {
            double[] un = uncoveredArmor(player, part);
            setModifier(player, Attributes.ARMOR, PART_ARMOR_ID, -un[0], AttributeModifier.Operation.ADD_VALUE);
            setModifier(player, Attributes.ARMOR_TOUGHNESS, PART_TOUGH_ID, -un[1], AttributeModifier.Operation.ADD_VALUE);
        }
    }

    /** 最终伤害：记入命中部位（头清空 → 昏迷；四肢清空 → 断肢） */
    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        clearPartArmor(player);
        Pending pending = PENDING.remove(player.getUUID());
        if (bypass(event.getSource())) return;
        LimbPart part = pending != null ? pending.part() : resolvePart(player, event.getSource());
        int amount = Math.round(event.getNewDamage());
        if (part == null || amount <= 0) return;
        damagePart(player, part, amount);
    }

    /** 对部位造成伤害（指令 / 其他系统也可调用） */
    public static void damagePart(ServerPlayer player, LimbPart part, int amount) {
        PlayerLimbData d = data(player);
        if (d.isSevered(part)) return;
        float maxHealth = player.getMaxHealth();
        boolean wasOut = d.current(part, maxHealth) <= 0;
        d.setDamage(part, Math.min(part.maxHp(maxHealth), d.damage(part) + amount));
        if (d.current(part, maxHealth) <= 0 && !wasOut) {
            if (part.severable()) {
                sever(player, part);
            } else {
                player.displayClientMessage(Component.translatable("msg.zhushenspace.limb.head_out"), false);
            }
        }
        sync(player);
    }

    /** 断肢：演出 + 属性惩罚 */
    public static void sever(ServerPlayer player, LimbPart part) {
        PlayerLimbData d = data(player);
        if (!part.severable() || d.isSevered(part)) return;
        d.setSevered(part, true);
        d.setDamage(part, part.maxHp(player.getMaxHealth()));
        applyPenalties(player);
        player.displayClientMessage(Component.translatable("msg.zhushenspace.limb.severed",
                Component.translatable(part.nameKey())), false);
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_HURT_SWEET_BERRY_BUSH,
                SoundSource.PLAYERS, 1.0f, 0.5f);
        player.level().playSound(null, player.blockPosition(), SoundEvents.SLIME_BLOCK_BREAK,
                SoundSource.PLAYERS, 1.0f, 0.6f);
        if (player.level() instanceof ServerLevel level) {
            double y = player.getY() + (part.isLeg() ? 0.6 : 1.3);
            level.sendParticles(BLOOD_MIST, player.getX(), y, player.getZ(), 30, 0.25, 0.25, 0.25, 0);
            level.sendParticles(BLOOD_DROP, player.getX(), y, player.getZ(), 24, 0.2, 0.2, 0.2, 0.15);
        }
        dropLimb(player, part);
        if (part.isArm()) {
            // 断臂：若正在使用该手物品（拉弓 / 举盾 / 进食）立即中止
            if (player.isUsingItem() && armOf(player, player.getUsedItemHand()) == part) player.stopUsingItem();
        }
        sync(player);
    }

    /** 掉落断肢实体（使用玩家皮肤渲染）：从断口处向外侧抛出 */
    private static void dropLimb(ServerPlayer player, LimbPart part) {
        com.zhushen.space.entity.dismember.BodyPart bp = switch (part) {
            case HEAD -> com.zhushen.space.entity.dismember.BodyPart.HEAD;
            case RIGHT_ARM -> com.zhushen.space.entity.dismember.BodyPart.RIGHT_ARM;
            case LEFT_ARM -> com.zhushen.space.entity.dismember.BodyPart.LEFT_ARM;
            case RIGHT_LEG -> com.zhushen.space.entity.dismember.BodyPart.RIGHT_LEG;
            case LEFT_LEG -> com.zhushen.space.entity.dismember.BodyPart.LEFT_LEG;
        };
        double yaw = Math.toRadians(player.yBodyRot);
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        double side = (part == LimbPart.RIGHT_ARM || part == LimbPart.RIGHT_LEG) ? 1 : -1;
        double lateral = part.isArm() ? 0.35 : 0.12;
        Vec3 pos = player.position().add(right.scale(side * lateral)).add(0, part.isArm() ? 1.2 : 0.3, 0);
        Vec3 vel = right.scale(side * (0.15 + player.getRandom().nextDouble() * 0.1))
                .add(0, 0.25 + player.getRandom().nextDouble() * 0.1, 0);
        com.zhushen.space.entity.dismember.SeveredLimb.spawn(player.level(), bp, player.getScale(), pos, vel,
                player.yBodyRot, player.getUUID());
    }

    /** 断腿惩罚：一条 −50% 移速 / 跳跃，两条 −85% 移速 / 无法跳跃 */
    public static void applyPenalties(ServerPlayer player) {
        PlayerLimbData d = data(player);
        int legs = (d.isSevered(LimbPart.RIGHT_LEG) ? 1 : 0) + (d.isSevered(LimbPart.LEFT_LEG) ? 1 : 0);
        double speed = legs == 0 ? 0 : legs == 1 ? -0.5 : -0.85;
        double jump = legs == 0 ? 0 : legs == 1 ? -0.5 : -1.0;
        setModifier(player, Attributes.MOVEMENT_SPEED, LEG_SPEED_ID, speed, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        setModifier(player, Attributes.JUMP_STRENGTH, LEG_JUMP_ID, jump, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    // ===== 恢复 =====

    /** 恢复部位（null = 全部）：血量回满并接回断肢 */
    public static void restore(ServerPlayer player, LimbPart part) {
        PlayerLimbData d = data(player);
        for (LimbPart p : LimbPart.values()) {
            if (part != null && p != part) continue;
            d.setDamage(p, 0);
            d.setSevered(p, false);
        }
        applyPenalties(player);
        sync(player);
    }

    /** 治疗联动：回复未断部位的血量（头优先，其次四肢），供 HealthManager 调用 */
    public static void heal(ServerPlayer player, int amount) {
        if (amount <= 0) return;
        PlayerLimbData d = data(player);
        boolean changed = false;
        for (LimbPart p : LimbPart.values()) {
            if (amount <= 0) break;
            if (d.isSevered(p) || d.damage(p) <= 0) continue;
            int take = Math.min(amount, d.damage(p));
            d.setDamage(p, d.damage(p) - take);
            amount -= take;
            changed = true;
        }
        if (changed) sync(player);
    }

    // ===== 断臂：物品互动封锁 =====

    private static void deny(Player player) {
        if (player instanceof ServerPlayer sp && sp.tickCount % 10 == 0) {
            sp.displayClientMessage(Component.translatable("msg.zhushenspace.limb.arm_lost"), true);
        }
    }

    /** 双腿皆断：强制趴伏（SWIMMING 姿态 → 0.6×0.6 碰撞箱，渲染为贴地爬行，只占一格） */
    @SubscribeEvent
    public static void onPlayerTickCrawl(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        PlayerLimbData d = data(player);
        if (d.isSevered(LimbPart.RIGHT_LEG) && d.isSevered(LimbPart.LEFT_LEG)
                && !player.isPassenger() && !player.isSleeping() && !player.getAbilities().flying
                && player.getPose() != net.minecraft.world.entity.Pose.SWIMMING) {
            player.setPose(net.minecraft.world.entity.Pose.SWIMMING);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (handSevered(e.getEntity(), e.getHand())) { e.setCanceled(true); deny(e.getEntity()); }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (handSevered(e.getEntity(), e.getHand())) { e.setCanceled(true); deny(e.getEntity()); }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        if (handSevered(e.getEntity(), e.getHand())) { e.setCanceled(true); deny(e.getEntity()); }
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific e) {
        if (handSevered(e.getEntity(), e.getHand())) { e.setCanceled(true); deny(e.getEntity()); }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
        if (handSevered(e.getEntity(), InteractionHand.MAIN_HAND)) { e.setCanceled(true); deny(e.getEntity()); }
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent e) {
        if (handSevered(e.getEntity(), InteractionHand.MAIN_HAND)) { e.setCanceled(true); deny(e.getEntity()); }
    }

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed e) {
        if (handSevered(e.getEntity(), InteractionHand.MAIN_HAND)) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onSwapHands(LivingSwapItemsEvent.Hands e) {
        if (e.getEntity() instanceof Player p
                && (handSevered(p, InteractionHand.MAIN_HAND) || handSevered(p, InteractionHand.OFF_HAND))) {
            e.setCanceled(true);
            deny(p);
        }
    }

    // ===== 同步 / 生命周期 =====

    public static SyncLimbPayload payload(ServerPlayer player) {
        PlayerLimbData d = data(player);
        int[] cur = new int[LimbPart.COUNT], max = new int[LimbPart.COUNT];
        for (LimbPart p : LimbPart.values()) {
            max[p.ordinal()] = p.maxHp(player.getMaxHealth());
            cur[p.ordinal()] = d.current(p, player.getMaxHealth());
        }
        return new SyncLimbPayload(player.getId(), cur, max, d.severedMask());
    }

    public static void sync(ServerPlayer player) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, payload(player));
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking e) {
        if (e.getTarget() instanceof ServerPlayer target && e.getEntity() instanceof ServerPlayer viewer) {
            PacketDistributor.sendToPlayer(viewer, payload(target));
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) { applyPenalties(p); sync(p); }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            data(p).reset();
            applyPenalties(p);
            sync(p);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) { applyPenalties(p); sync(p); }
    }

    /** 生命上限变化（装备 / 属性）会改变部位上限：换装时顺带重发 */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && e.getSlot().isArmor()) sync(p);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        PENDING.remove(e.getEntity().getUUID());
    }

    /** 兜底：受击被取消（Post 未触发）时，下一 tick 撤去部位护甲修正 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (PENDING.isEmpty()) return;
        long t = e.getServer().getTickCount();
        PENDING.entrySet().removeIf(en -> {
            if (en.getValue().tick() >= t) return false;
            ServerPlayer p = e.getServer().getPlayerList().getPlayer(en.getKey());
            if (p != null) clearPartArmor(p);
            return true;
        });
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent e) {
        PENDING.clear();
    }
}

package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.GearSlot;
import com.zhushen.space.data.LimbPart;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerGearData;
import com.zhushen.space.data.PlayerLimbData;
import com.zhushen.space.network.SyncGearPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.event.CurioCanEquipEvent;
import top.theillusivec4.curios.api.event.CurioCanUnequipEvent;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * 装备位规则（docs/equipment-slots-v1.md）：
 * <ul>
 *   <li>穿脱时间：放入装备位后进入「穿戴中」，计时结束才生效；需要穿脱时间的装备生效后不能直接取下——
 *       尝试取下即开始「解下」，计时结束后变为「已解开」，才可以取出。原版护甲栏通过 ArmorSlotMixin、
 *       Curios 栏位通过 {@link CurioCanUnequipEvent} 锁定。</li>
 *   <li>标准动作：除戒指、盔甲（与武器、盾牌、插件、可搭载装置、道具）外，穿脱需要一个标准动作——无法行动时进度暂停。</li>
 *   <li>生效数量：Curios 栏位数量即生效数量，多出来的装备由角色自行选择放在哪个栏位。</li>
 *   <li>身体：失去一只手（手臂）→ 失去手套位、护腕位与一个戒指位；没有腿 → 没有鞋位（靴子被脱下）。
 *       多头 / 多臂等身体改造通过 {@link #BODY_HOOKS} 接入（每多一个头 +1 项链位）。</li>
 *   <li>概念武装：占据概念武装位与自身的装备位；只有在短休 / 长休时才能更换（标签
 *       {@code zhushenspace:concept_free_swap} 的概念武装除外）；永远不会被摧毁。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class GearManager {
    private GearManager() {}

    /** 概念武装（可放入概念武装位） */
    public static final TagKey<Item> CONCEPT_ARMAMENT = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "concept_armament"));
    /** 有特殊说明、可随时更换的概念武装 */
    public static final TagKey<Item> CONCEPT_FREE_SWAP = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "concept_free_swap"));

    private static final ResourceLocation BODY_MOD = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "gear_body");

    /** 身体结构：头 / 可用手臂（手） / 腿的数量 */
    public record Body(int heads, int arms, int legs) {}

    /** 身体结构修正（多头、多臂、义肢……）：依次作用于由断肢计算出的基础身体 */
    public static final List<BiFunction<ServerPlayer, Body, Body>> BODY_HOOKS = new ArrayList<>();

    /** 客户端锁定判定（由客户端注册；服务端为 null） */
    public static Predicate<String> CLIENT_LOCKED = null;
    /** 客户端：能否更换概念武装（休息中） */
    public static java.util.function.BooleanSupplier CLIENT_CONCEPT_SWAP = null;

    /** 已应用的栏位修正缓存：玩家 → 栏位 → 数值 */
    private static final Map<UUID, Map<String, Integer>> APPLIED = new HashMap<>();
    private static final Map<UUID, Integer> LAST_FLAGS = new HashMap<>();

    // ===== 查询 =====

    public static PlayerGearData data(Player p) { return p.getData(ModAttachments.PLAYER_GEAR); }

    private static long now(ServerPlayer p) {
        return p.getServer() != null ? p.getServer().overworld().getGameTime() : p.level().getGameTime();
    }

    public static Body body(ServerPlayer p) {
        PlayerLimbData limbs = LimbManager.data(p);
        int arms = 0, legs = 0;
        for (LimbPart part : LimbPart.values()) {
            if (limbs.isSevered(part)) continue;
            if (part.isArm()) arms++;
            if (part.isLeg()) legs++;
        }
        Body b = new Body(1, arms, legs);
        for (var h : BODY_HOOKS) b = h.apply(p, b);
        return b;
    }

    /** 原版护甲栏中的装备此刻是否生效（护甲值、魔虚罗法阵等） */
    public static boolean effective(ServerPlayer p, EquipmentSlot slot) {
        GearSlot gs = GearSlot.ofVanilla(slot);
        if (gs == null) return true;
        ItemStack st = p.getItemBySlot(slot);
        if (st.isEmpty()) return false;
        if (slot == EquipmentSlot.FEET && body(p).legs() <= 0) return false;
        if (occupiedByConcept(p, slot)) return false;
        PlayerGearData d = data(p);
        if (!d.initialized) return true;
        PlayerGearData.Entry e = d.entries.get(GearSlot.vanillaKey(slot));
        return e == null || e.state == PlayerGearData.WORN;
    }

    /** 是否需要先解下才能取出 */
    public static boolean locked(Player p, String key) {
        if (p.isCreative() || p.isSpectator() || !p.isAlive()) return false;
        if (p.level().isClientSide) return CLIENT_LOCKED != null && CLIENT_LOCKED.test(key);
        GearSlot gs = GearSlot.ofKey(key);
        if (gs == null || gs.ticks <= 0) return false;
        PlayerGearData.Entry e = data(p).entries.get(key);
        return e != null && (e.state == PlayerGearData.WORN || e.state == PlayerGearData.DOFFING);
    }

    static boolean canSwapConcept(Player p, ItemStack stack) {
        if (p.isCreative() || (stack != null && stack.is(CONCEPT_FREE_SWAP))) return true;
        if (p.level().isClientSide) return CLIENT_CONCEPT_SWAP == null || CLIENT_CONCEPT_SWAP.getAsBoolean();
        return p instanceof ServerPlayer sp && RestManager.isResting(sp);
    }

    // ===== 位置 =====

    private record Pos(String key, GearSlot slot, EquipmentSlot vanilla, String curio, int index) {}

    private static List<Pos> positions(ServerPlayer p, ICuriosItemHandler h) {
        List<Pos> out = new ArrayList<>();
        for (GearSlot gs : GearSlot.values()) {
            if (gs.vanilla != null) {
                out.add(new Pos(GearSlot.vanillaKey(gs.vanilla), gs, gs.vanilla, null, 0));
            } else if (h != null) {
                var sh = h.getStacksHandler(gs.curio).orElse(null);
                if (sh == null) continue;
                for (int i = 0; i < sh.getSlots(); i++) out.add(new Pos(GearSlot.curioKey(gs.curio, i), gs, null, gs.curio, i));
            }
        }
        return out;
    }

    private static ItemStack stackAt(ServerPlayer p, ICuriosItemHandler h, Pos pos) {
        if (pos.vanilla != null) return p.getItemBySlot(pos.vanilla);
        if (h == null) return ItemStack.EMPTY;
        return h.getStacksHandler(pos.curio).map(sh -> pos.index < sh.getSlots()
                ? sh.getStacks().getStackInSlot(pos.index) : ItemStack.EMPTY).orElse(ItemStack.EMPTY);
    }

    private static String itemId(ItemStack st) { return BuiltInRegistries.ITEM.getKey(st.getItem()).toString(); }

    // ===== 概念武装 =====

    private static ItemStack conceptStack(ServerPlayer p, ICuriosItemHandler h) {
        if (h == null) return ItemStack.EMPTY;
        return h.getStacksHandler("concept").map(sh -> sh.getSlots() > 0 ? sh.getStacks().getStackInSlot(0) : ItemStack.EMPTY)
                .orElse(ItemStack.EMPTY);
    }

    /** 概念武装自身的装备位（Curios 栏位 id；原版护甲则为 "v:&lt;slot&gt;"；没有则 null） */
    private static String naturalSlot(ServerPlayer p, ItemStack st) {
        if (st.isEmpty()) return null;
        EquipmentSlot es = p.getEquipmentSlotForItem(st);
        if (es.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) return GearSlot.vanillaKey(es);
        for (GearSlot gs : GearSlot.values()) {
            if (gs.curio == null || gs == GearSlot.CONCEPT) continue;
            TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("curios", gs.curio));
            if (st.is(tag)) return gs.curio;
        }
        return null;
    }

    private static boolean occupiedByConcept(ServerPlayer p, EquipmentSlot slot) {
        ICuriosItemHandler h = CuriosApi.getCuriosInventory(p).orElse(null);
        String nat = naturalSlot(p, conceptStack(p, h));
        return nat != null && nat.equals(GearSlot.vanillaKey(slot));
    }

    // ===== 主循环 =====

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.tickCount % 5 != 0 || p.isSpectator()) return;
        ICuriosItemHandler h = CuriosApi.getCuriosInventory(p).orElse(null);
        Body body = body(p);
        ItemStack concept = conceptStack(p, h);
        String nat = naturalSlot(p, concept);

        // 1) 身体与概念武装决定的栏位数量
        int flags = 0;
        if (body.legs() <= 0) flags |= 1;
        if (body.arms() < 2) flags |= 2;
        if (body.arms() <= 0) flags |= 4;
        if (h != null) {
            Map<String, Integer> want = new LinkedHashMap<>();
            want.put("necklace", Math.max(0, body.heads() - 1));
            want.put("bracelet", body.arms() < 2 ? -1 : 0);
            want.put("hands", body.arms() < 2 ? -1 : 0);
            want.put("ring", Math.min(0, body.arms() - 2));
            want.put("back", 0);
            want.put("belt", 0);
            want.put("charm", 0);
            if (nat != null && want.containsKey(nat)) want.merge(nat, -1, Integer::sum);
            Map<String, Integer> applied = APPLIED.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
            for (var w : want.entrySet()) {
                Integer old = applied.get(w.getKey());
                int v = w.getValue();
                if (old != null && old == v) continue;
                h.removeSlotModifier(w.getKey(), BODY_MOD);
                if (v != 0) h.addTransientSlotModifier(w.getKey(), BODY_MOD, v, AttributeModifier.Operation.ADD_VALUE);
                applied.put(w.getKey(), v);
            }
        }
        Integer lastFlags = LAST_FLAGS.put(p.getUUID(), flags);
        boolean changed = lastFlags == null || lastFlags != flags;
        if (lastFlags != null && lastFlags != flags && (flags & ~lastFlags) != 0) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.gear.slots_changed"), false);
        }

        // 2) 没有腿：没有鞋位
        if (body.legs() <= 0) {
            ItemStack boots = p.getItemBySlot(EquipmentSlot.FEET);
            if (!boots.isEmpty()) {
                ItemStack copy = boots.copy();
                p.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
                if (!p.getInventory().add(copy)) p.drop(copy, false);
                p.displayClientMessage(Component.translatable("msg.zhushenspace.gear.no_legs", copy.getHoverName()), true);
            }
        }

        // 3) 概念武装永不损坏
        if (!concept.isEmpty() && concept.isDamageableItem() && concept.getDamageValue() > 0) concept.setDamageValue(0);

        // 4) 穿脱状态
        PlayerGearData d = data(p);
        long now = now(p);
        boolean act = StatusEffects.canAct(p, false);
        List<Pos> pos = positions(p, h);
        Map<String, ItemStack> present = new HashMap<>();
        for (Pos x : pos) present.put(x.key, stackAt(p, h, x));
        if (!d.initialized) {
            for (Pos x : pos) {
                ItemStack st = present.get(x.key);
                if (!st.isEmpty()) d.entries.put(x.key, new PlayerGearData.Entry(itemId(st), PlayerGearData.WORN, now, now));
            }
            d.initialized = true;
            changed = true;
        }
        for (Iterator<String> it = d.entries.keySet().iterator(); it.hasNext(); ) {
            String k = it.next();
            ItemStack st = present.get(k);
            if (st == null || st.isEmpty()) { it.remove(); changed = true; }
        }
        for (Pos x : pos) {
            ItemStack st = present.get(x.key);
            if (st.isEmpty()) continue;
            String id = itemId(st);
            PlayerGearData.Entry en = d.entries.get(x.key);
            if (en == null || !en.item.equals(id)) {
                int t = x.slot.ticks;
                if (p.isCreative() || t <= 0) {
                    d.entries.put(x.key, new PlayerGearData.Entry(id, PlayerGearData.WORN, now, now));
                } else {
                    d.entries.put(x.key, new PlayerGearData.Entry(id, PlayerGearData.DONNING, now, now + t));
                    p.displayClientMessage(Component.translatable("msg.zhushenspace.gear.donning", st.getHoverName(),
                            Component.translatable(x.slot.nameKey()), seconds(t)), true);
                }
                changed = true;
                continue;
            }
            if (en.state == PlayerGearData.DONNING || en.state == PlayerGearData.DOFFING) {
                if (p.isCreative()) en.end = now;
                if (x.slot.standard && !act) { en.end += 5; changed = true; } // 无法行动：标准动作暂停
                if (now >= en.end) {
                    boolean donning = en.state == PlayerGearData.DONNING;
                    en.state = donning ? PlayerGearData.WORN : PlayerGearData.LOOSE;
                    p.displayClientMessage(Component.translatable(donning ? "msg.zhushenspace.gear.donned" : "msg.zhushenspace.gear.loose",
                            st.getHoverName()), true);
                    changed = true;
                }
            }
        }

        // 5) Curios 栏位：未生效（穿戴中 / 解下中 / 已解开）的装备停用
        if (h != null) {
            for (Pos x : pos) {
                if (x.curio == null) continue;
                ItemStack st = present.get(x.key);
                PlayerGearData.Entry en = d.entries.get(x.key);
                boolean active = st.isEmpty() || en == null || en.state == PlayerGearData.WORN;
                if (h.isSlotActive(x.curio, x.index) != active) h.setSlotActive(x.curio, x.index, active);
            }
        }

        if (changed || p.tickCount % 100 == 0) sync(p, flags);
    }

    private static String seconds(int ticks) {
        return String.valueOf((int) Math.ceil(ticks / 20.0));
    }

    /** 尝试取下一件已生效的装备：开始解下（需要标准动作） */
    public static void requestDoff(ServerPlayer p, String key) {
        PlayerGearData.Entry en = data(p).entries.get(key);
        GearSlot gs = GearSlot.ofKey(key);
        if (en == null || gs == null) return;
        long now = now(p);
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(en.item));
        Component name = new ItemStack(item).getHoverName();
        if (en.state == PlayerGearData.DOFFING) {
            int left = (int) Math.max(0, en.end - now);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.gear.doffing_wait", name, seconds(left)), true);
            return;
        }
        if (en.state != PlayerGearData.WORN) return;
        if (gs.standard && !StatusEffects.canAct(p, true)) return;
        en.state = PlayerGearData.DOFFING;
        en.start = now;
        en.end = now + gs.ticks;
        p.displayClientMessage(Component.translatable("msg.zhushenspace.gear.doffing", name, seconds(gs.ticks)), true);
        sync(p, LAST_FLAGS.getOrDefault(p.getUUID(), 0));
    }

    public static void sync(ServerPlayer p, int flags) {
        PlayerGearData d = data(p);
        long now = now(p);
        List<SyncGearPayload.Entry> list = new ArrayList<>();
        for (var e : d.entries.entrySet()) {
            PlayerGearData.Entry en = e.getValue();
            int total = (int) Math.max(0, en.end - en.start);
            int remain = (int) Math.max(0, en.end - now);
            list.add(new SyncGearPayload.Entry(e.getKey(), en.item, en.state, remain, total, locked(p, e.getKey())));
        }
        PacketDistributor.sendToPlayer(p, new SyncGearPayload(list, flags));
    }

    // ===== 锁定：原版护甲栏（ArmorSlotMixin 调用） =====

    /** 原版护甲栏能否取出（false = 拦截；服务端同时开始解下） */
    public static boolean blockArmorPickup(Player player, LivingEntity owner, EquipmentSlot slot) {
        if (owner != player || GearSlot.ofVanilla(slot) == null) return false;
        String key = GearSlot.vanillaKey(slot);
        if (!locked(player, key)) return false;
        if (player instanceof ServerPlayer sp) requestDoff(sp, key);
        return true;
    }

    /** 手持右键穿戴（与已穿着的头盔 / 靴子交换）同样受锁定限制 */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        Player p = e.getEntity();
        ItemStack st = e.getItemStack();
        EquipmentSlot es = p.getEquipmentSlotForItem(st);
        if (es.getType() != EquipmentSlot.Type.HUMANOID_ARMOR || p.getItemBySlot(es).isEmpty()) return;
        if (!locked(p, GearSlot.vanillaKey(es))) return;
        if (p instanceof ServerPlayer sp) requestDoff(sp, GearSlot.vanillaKey(es));
        e.setCanceled(true);
    }

    // ===== 锁定：Curios 栏位 =====

    @SubscribeEvent
    public static void onCurioUnequip(CurioCanUnequipEvent e) {
        SlotContext ctx = e.getSlotContext();
        if (ctx.cosmetic() || !(ctx.entity() instanceof Player p) || !p.isAlive()) return;
        if ("concept".equals(ctx.identifier())) {
            if (!canSwapConcept(p, e.getStack())) {
                e.setUnequipResult(TriState.FALSE);
                if (p instanceof ServerPlayer sp) sp.displayClientMessage(Component.translatable("msg.zhushenspace.gear.concept_rest"), true);
            }
            return;
        }
        if (GearSlot.ofCurio(ctx.identifier()) == null) return;
        String key = GearSlot.curioKey(ctx.identifier(), ctx.index());
        if (!locked(p, key)) return;
        e.setUnequipResult(TriState.FALSE);
        if (p instanceof ServerPlayer sp) requestDoff(sp, key);
    }

    @SubscribeEvent
    public static void onCurioEquip(CurioCanEquipEvent e) {
        SlotContext ctx = e.getSlotContext();
        if (ctx.cosmetic() || !"concept".equals(ctx.identifier()) || !(ctx.entity() instanceof Player p)) return;
        if (!canSwapConcept(p, e.getStack())) e.setEquipResult(TriState.FALSE);
    }

    // ===== 生命周期 =====

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        APPLIED.remove(e.getEntity().getUUID());
        LAST_FLAGS.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        APPLIED.remove(e.getEntity().getUUID());
        LAST_FLAGS.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone e) {
        APPLIED.remove(e.getEntity().getUUID());
    }
}

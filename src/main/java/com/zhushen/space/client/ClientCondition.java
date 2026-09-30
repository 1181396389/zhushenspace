package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.common.SurvivalManager;
import com.zhushen.space.data.Condition;
import com.zhushen.space.data.LimbPart;
import com.zhushen.space.data.StatusType;
import com.zhushen.space.network.ArtActionPayload;
import com.zhushen.space.network.SyncConditionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 身体状况客户端：生存需求 HUD（水分 / 体力 / 精力）、不良状态列表、倒地爬行与起身、力竭禁冲刺、
 * 潜行空手喝水、耳鸣 / 耳聋的声音衰减、目眩 / 失去眼睛 / 困倦的画面效果、极度困倦时的幻听。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientCondition {

    private ClientCondition() {
    }

    private static boolean has;
    private static float thirst = 100, stamina = 60, staminaMax = 60, sleep = 100;
    private static int flags;
    private static int[] points = new int[StatusType.COUNT], tiers = new int[StatusType.COUNT],
            heavy = new int[StatusType.COUNT], destr = new int[StatusType.COUNT];
    private static int permanent;
    private static long conditions;
    private static int limbDisabled, openWounds;
    private static float breath = -1f;
    private static int lastTinnitusTier;

    public static void update(SyncConditionPayload p) {
        has = true;
        thirst = p.thirst();
        stamina = p.stamina();
        staminaMax = Math.max(1, p.staminaMax());
        sleep = p.sleep();
        flags = p.flags();
        if (p.points().length == StatusType.COUNT) points = p.points();
        if (p.tiers().length == StatusType.COUNT) tiers = p.tiers();
        if (p.heavy().length == StatusType.COUNT) heavy = p.heavy();
        if (p.destructive().length == StatusType.COUNT) destr = p.destructive();
        permanent = p.permanent();
        conditions = p.conditions();
        limbDisabled = p.limbDisabled();
        openWounds = p.openWounds();
        breath = p.breath();
        int tin = tier(StatusType.TINNITUS);
        if (tin > lastTinnitusTier && tin > 0) {
            // 耳鸣：一声尖锐的长鸣（UI 声道，不受衰减影响）
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 2.0f, 0.35f));
        }
        lastTinnitusTier = tin;
    }

    public static boolean exhausted() { return (flags & 1) != 0; }
    public static boolean collapsed() { return (flags & 2) != 0; }
    public static boolean prone() { return (flags & 4) != 0; }
    public static boolean standing() { return (flags & 8) != 0; }
    public static boolean incapacitated() { return (flags & 16) != 0; }
    public static boolean freeMover() { return (flags & 32) != 0; }
    public static int tier(StatusType t) { return tiers[t.ordinal()]; }
    public static int points(StatusType t) { return points[t.ordinal()]; }
    public static boolean permanent(StatusType t) { return (permanent & t.bit()) != 0; }
    public static boolean noSprint() { return (flags & 64) != 0; }
    public static boolean immobile() { return (flags & 128) != 0; }
    public static boolean has(Condition c) { return (conditions & c.bit()) != 0; }
    public static boolean limbDisabled(LimbPart part) { return (limbDisabled & part.bit()) != 0; }

    /** 强制趴伏（PlayerPoseMixin）：本地玩家看同步来的倒地 / 断腿；其他玩家保持服务端同步的趴伏姿态 */
    public static boolean keepCrawl(net.minecraft.world.entity.player.Player p) {
        Minecraft mc = Minecraft.getInstance();
        if (p == mc.player) {
            if (p.getAbilities().flying) return false;
            if (has && !p.isCreative() && prone() && !freeMover()) return true;
            return ClientLimbData.severed(p.getId(), LimbPart.RIGHT_LEG) && ClientLimbData.severed(p.getId(), LimbPart.LEFT_LEG);
        }
        return p.getPose() == Pose.SWIMMING && !p.isInWater() && !p.isSwimming();
    }

    /**
     * 视野：本模组造成的移速变化（不良状态、断腿、休息 / 冥想时的定身）不再让视野缩放，
     * 否则速度一变画面就一缩一放，看起来像在抽搐。原版的疾跑 / 药水 / 拉弓视野效果保留。
     */
    @SubscribeEvent
    public static void onFov(net.neoforged.neoforge.client.event.ComputeFovModifierEvent event) {
        net.minecraft.world.entity.player.Player p = event.getPlayer();
        var inst = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        float walk = p.getAbilities().getWalkingSpeed();
        if (inst == null || walk <= 0) return;
        var lock = p.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN);
        boolean restLock = lock != null && lock.getAmplifier() >= 9;
        boolean ours = restLock;
        double base = inst.getBaseValue(), add = 0, mulBase = 0, mulTotal = 1;
        for (var m : inst.getModifiers()) {
            boolean skip = m.id().getNamespace().equals(com.zhushen.space.ZhuShenSpace.MODID)
                    || restLock && m.id().getPath().contains("slowness");
            if (skip) { ours = true; continue; }
            switch (m.operation()) {
                case ADD_VALUE -> add += m.amount();
                case ADD_MULTIPLIED_BASE -> mulBase += m.amount();
                case ADD_MULTIPLIED_TOTAL -> mulTotal *= 1 + m.amount();
            }
        }
        if (!ours) return;
        double b = base + add;
        double without = Math.max(0, (b + b * mulBase) * mulTotal);
        double with = inst.getValue();
        float sWith = (float) ((with / walk + 1) / 2), sWithout = (float) ((without / walk + 1) / 2);
        if (sWith <= 0.01f) return;
        event.setNewFovModifier(event.getNewFovModifier() / sWith * sWithout);
    }

    private static boolean active(LocalPlayer p) {
        return has && p != null && !p.isCreative() && !p.isSpectator();
    }

    // ===== 输入 =====

    private static boolean lastJump;
    private static long lastStandReq;

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer p) || !active(p)) return;
        var in = event.getInput();
        // 体力透支 / 力竭 / 反胃 / 失衡：无法冲刺（前进力度压到冲刺门槛以下，走路也会慢一些）
        if ((exhausted() || noSprint()) && in.forwardImpulse > 0.79f) in.forwardImpulse = 0.79f;
        // 无法移动（定身 / 冰封 / 浮空 / 禁锢……）：不能跳
        if (immobile()) in.jumping = false;
        // 倒地：不能跳（按跳跃键 = 爬起来）
        if (prone() && !freeMover()) in.jumping = false;
    }

    @SubscribeEvent
    public static void onTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!active(p)) return;
        if ((exhausted() || noSprint()) && p.isSprinting()) p.setSprinting(false);
        boolean jump = mc.options.keyJump.isDown();
        if (prone() && !freeMover() && jump && !lastJump && !standing() && mc.screen == null
                && System.currentTimeMillis() - lastStandReq > 500) {
            lastStandReq = System.currentTimeMillis();
            PacketDistributor.sendToServer(new ArtActionPayload(21, 0, 0));
        }
        lastJump = jump;
    }

    /** 倒地：本地玩家同步趴伏（碰撞箱 / 视角高度与服务端一致） */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof LocalPlayer p) || !active(p)) return;
        if (prone() && !freeMover() && !p.isPassenger() && !p.isSleeping() && p.getPose() != Pose.SWIMMING) {
            p.setPose(Pose.SWIMMING);
        }
    }

    /** 潜行 + 空手右键水面：喝生水 */
    @SubscribeEvent
    public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || !event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!p.isShiftKeyDown() || !p.getMainHandItem().isEmpty()) return;
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1f).scale(3.5));
        BlockHitResult hit = p.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
        if (hit.getType() != HitResult.Type.BLOCK || !p.level().getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) return;
        event.setCanceled(true);
        event.setSwingHand(true);
        PacketDistributor.sendToServer(new ArtActionPayload(20, 0, 0));
    }

    /** 挖掘速度（客户端预测，与服务端一致） */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (!(event.getEntity() instanceof LocalPlayer p) || !active(p)) return;
        float mul = SurvivalManager.mineFactor(exhausted(), sleep, thirst);
        if (mul < 1f) event.setNewSpeed(event.getNewSpeed() * mul);
    }

    // ===== 声音：耳鸣衰减 / 耳聋静音 =====

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        LocalPlayer p = Minecraft.getInstance().player;
        SoundInstance s = event.getSound();
        if (s == null || !active(p) || s.getSource() == SoundSource.MASTER) return;
        float vol;
        boolean unlocatable = false;
        if (permanent(StatusType.TINNITUS) || has(Condition.DEAF)) vol = 0f;              // 耳聋：听不见
        else if (has(Condition.HEARING_IMPAIRED)) { vol = 0.35f; unlocatable = true; }    // 听觉障碍：无法定位声源
        else if (tier(StatusType.TINNITUS) >= StatusType.Tier.LIGHT.ordinal()) vol = 0.6f;
        else return;
        if (vol <= 0f) {
            event.setSound(null);
            return;
        }
        if (s instanceof TickableSoundInstance) return; // 持续音效（音乐唱片 / 矿车等）保持原样
        event.setSound(new Muffled(s, vol, unlocatable && !s.isRelative()));
    }

    /** 音量缩放的声音包装；flat = 改为不带方位（听觉障碍：无法精确定位其他单位） */
    private record Muffled(SoundInstance in, float factor, boolean flat) implements SoundInstance {
        @Override public ResourceLocation getLocation() { return in.getLocation(); }
        @Override public WeighedSoundEvents resolve(SoundManager m) { return in.resolve(m); }
        @Override public Sound getSound() { return in.getSound(); }
        @Override public SoundSource getSource() { return in.getSource(); }
        @Override public boolean isLooping() { return in.isLooping(); }
        @Override public boolean isRelative() { return flat || in.isRelative(); }
        @Override public int getDelay() { return in.getDelay(); }
        @Override public float getPitch() { return in.getPitch(); }
        @Override public double getX() { return flat ? 0 : in.getX(); }
        @Override public double getY() { return flat ? 0 : in.getY(); }
        @Override public double getZ() { return flat ? 0 : in.getZ(); }
        @Override public Attenuation getAttenuation() { return flat ? Attenuation.NONE : in.getAttenuation(); }
        @Override public float getVolume() { return in.getVolume() * factor * (flat ? distanceFade() : 1f); }
        private float distanceFade() {
            LocalPlayer lp = Minecraft.getInstance().player;
            if (lp == null || in.getAttenuation() == Attenuation.NONE) return 1f;
            double d = Math.sqrt(lp.distanceToSqr(in.getX(), in.getY(), in.getZ()));
            return (float) Math.max(0.05, 1.0 - d / 16.0);
        }
        @Override public boolean canStartSilent() { return in.canStartSilent(); }
        @Override public boolean canPlaySound() { return in.canPlaySound(); }
    }

    // ===== 幻听（极度困倦） =====

    private static long nextHallucination;
    private static final SoundEvent[] HALLUCINATIONS = {SoundEvents.ZOMBIE_AMBIENT, SoundEvents.CREEPER_PRIMED,
            SoundEvents.SKELETON_AMBIENT, SoundEvents.SPIDER_AMBIENT, SoundEvents.ENDERMAN_AMBIENT,
            SoundEvents.WOODEN_DOOR_OPEN, SoundEvents.STONE_STEP, SoundEvents.ARROW_SHOOT};

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!active(p) || mc.level == null || mc.isPaused()) return;
        long now = System.currentTimeMillis();
        if (sleep < 10 && !collapsed() && !p.isSleeping()) {
            if (nextHallucination == 0) nextHallucination = now + 15000 + p.getRandom().nextInt(30000);
            if (now >= nextHallucination) {
                nextHallucination = now + 20000 + p.getRandom().nextInt(40000);
                double a = p.getRandom().nextDouble() * Math.PI * 2, r = 3 + p.getRandom().nextDouble() * 5;
                SoundEvent ev = HALLUCINATIONS[p.getRandom().nextInt(HALLUCINATIONS.length)];
                mc.level.playLocalSound(p.getX() + Math.cos(a) * r, p.getY(), p.getZ() + Math.sin(a) * r, ev,
                        SoundSource.HOSTILE, 0.8f, 0.9f + p.getRandom().nextFloat() * 0.2f, false);
            }
        } else nextHallucination = 0;
    }

    // ===== 画面 / HUD =====

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!active(p) || mc.options.hideGui) return;
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        long ms = System.currentTimeMillis();

        // --- 画面效果 ---
        int eyes = ClientLimbData.eyes();
        boolean blind = (eyes & 3) == 3 || permanent(StatusType.DAZZLE) || has(Condition.BLIND);
        if (blind) g.fill(0, 0, w, h, 0xE0000000);
        else {
            if ((eyes & 1) != 0) sideShade(g, w, h, true);
            if ((eyes & 2) != 0) sideShade(g, w, h, false);
            int dz = tier(StatusType.DAZZLE);
            if (has(Condition.VISION_IMPAIRED)) {
                // 视觉障碍：视野模糊发暗（周期性加重）
                int a = 0x70 + (int) (0x30 * (0.5 + 0.5 * Math.sin(ms / 700.0)));
                g.fill(0, 0, w, h, (a << 24) | 0x101010);
            } else if (dz == StatusType.Tier.LIGHT.ordinal()) {
                int a = 40 + (int) (30 * (0.5 + 0.5 * Math.sin(ms / 400.0)));
                g.fill(0, 0, w, h, (a << 24) | 0xFFFFFF);
            }
        }
        // 困倦：眼皮打架（周期性合眼）
        if (!collapsed() && sleep < 25 && !p.isSleeping()) {
            long period = sleep < 10 ? 6000 : 11000;
            long ph = ms % period;
            if (ph < 900) {
                double k = Math.sin(ph / 900.0 * Math.PI);
                int lid = (int) (h / 2.0 * k * (sleep < 10 ? 1.0 : 0.7));
                g.fill(0, 0, w, lid, 0xFF000000);
                g.fill(0, h - lid, w, h, 0xFF000000);
            }
        }
        Font font = mc.font;
        if (collapsed()) {
            g.fill(0, 0, w, h, 0xF0000000);
            g.drawCenteredString(font, Component.translatable("hud.zhushenspace.survival.collapsed"), w / 2, h / 2 - 4, 0xFFB0B0C0);
            return;
        }
        // 无法行动的固有不良状态：画面与提示
        Condition big = null;
        for (Condition c : new Condition[]{Condition.BANISHED, Condition.PETRIFIED, Condition.FROZEN, Condition.ETERNAL_SLEEP,
                Condition.UNCONSCIOUS, Condition.ASLEEP, Condition.ENSLAVED, Condition.HELPLESS, Condition.STUNNED}) {
            if (has(c)) { big = c; break; }
        }
        if (big != null) {
            switch (big) {
                case ETERNAL_SLEEP, UNCONSCIOUS, ASLEEP -> g.fill(0, 0, w, h, 0xF0000000);
                case PETRIFIED -> g.fill(0, 0, w, h, 0x90707070);
                case BANISHED -> g.fill(0, 0, w, h, 0xA0200030);
                case ENSLAVED -> g.fill(0, 0, w, h, 0x50600080);
                default -> { }
            }
            g.drawCenteredString(font, Component.translatable("hud.zhushenspace.condition." + big.key), w / 2, h / 2 + 20, 0xFFE0D0FF);
        }
        if (breath == 0f && p.isEyeInFluid(FluidTags.WATER)) {
            g.drawCenteredString(font, Component.translatable("hud.zhushenspace.breath.out"), w / 2, h / 2 + 32, 0xFF80C0FF);
        }

        // --- 生存条（快捷栏右侧：水分 / 体力 / 精力） ---
        int x0 = w / 2 + 91 + 6, bottom = h - 1;
        survivalBars(g, font, x0, bottom, Math.max(40, Math.min(78, w - x0 - 4)), ms);

        // --- 不良状态 / 倒地 ---
        int y = bottom - 33;
        if (prone()) {
            g.drawString(font, Component.translatable(standing() ? "hud.zhushenspace.prone.standing" : "hud.zhushenspace.prone.hint"),
                    x0, y, 0xFFFFD27F, true);
            y -= 10;
        }
        // 固有不良状态（不含已由点数行显示的）
        StringBuilder conds = new StringBuilder();
        for (Condition c : Condition.values()) {
            if (!has(c)) continue;
            if (conds.length() > 0) conds.append(' ');
            conds.append(Component.translatable(c.nameKey()).getString());
            if (c == Condition.OPEN_WOUND && openWounds > 1) conds.append('×').append(openWounds);
        }
        if (conds.length() > 0) {
            String line = conds.toString();
            while (line.length() > 0) {
                String part = font.plainSubstrByWidth(line, 150);
                if (part.isEmpty()) break;
                g.drawString(font, part, x0, y, 0xFFFF7A7A, true);
                y -= 10;
                line = line.substring(part.length()).trim();
            }
        }
        for (StatusType t : StatusType.values()) {
            boolean perm = permanent(t);
            int tr = tier(t);
            if (tr == 0 && !perm) continue;
            int color = perm || tr >= 3 ? 0xFFFF4040 : tr == 2 ? 0xFFFF9A3C : 0xFFFFE070;
            Component name = points(t) <= 0 ? Component.translatable(t.tierKey(StatusType.Tier.DESTRUCTIVE))
                    : Component.translatable("hud.zhushenspace.status.line", Component.translatable(t.tierKey(StatusType.Tier.values()[tr])),
                    points(t), heavy[t.ordinal()], destr[t.ordinal()]);
            g.drawString(font, name, x0, y, color, true);
            y -= 10;
        }
    }

    // ===== 生存条（体力 / 水分 / 精力） =====

    /** 显示值（平滑）与残影值（刚失去的部分稍后才收回） */
    private static final float[] DISP = {-1, -1, -1}, TRAIL = {-1, -1, -1};
    private static final long[] TRAIL_HOLD = new long[3];
    private static long lastBarMs, lastChangeMs;
    private static float barAlpha = 1f;

    private static final String[] ICON_BOLT = {
            "...##..", "..##...", ".##....", ".#####.", "...##..", "..##...", ".##...."};
    private static final String[] ICON_DROP = {
            "...#...", "..###..", ".##o##.", "##o####", "#######", "#######", ".#####."};
    private static final String[] ICON_MOON = {
            "..####.", ".###...", "###....", "###....", "###....", ".###...", "..####."};

    /**
     * 快捷栏右侧的三条横向生存条：体力（上）/ 水分（中）/ 精力（下）。
     * 平滑变化 + 失去部分的残影；偏低时闪烁描边；体力透支时变红并出现流动斜纹；
     * 三项都充足且体力一段时间没有变化时整体淡出，减少遮挡。
     */
    private static void survivalBars(GuiGraphics g, Font font, int x0, int bottom, int width, long ms) {
        float dt = lastBarMs == 0 ? 0f : Math.min(0.25f, (ms - lastBarMs) / 1000f);
        lastBarMs = ms;
        float[] target = {
                staminaMax > 0 ? stamina / staminaMax : 1f,
                thirst / SurvivalManager.MAX,
                sleep / SurvivalManager.MAX};
        for (int i = 0; i < 3; i++) {
            float t = Math.max(0f, Math.min(1f, target[i]));
            if (DISP[i] < 0) { DISP[i] = t; TRAIL[i] = t; }
            if (Math.abs(t - DISP[i]) > 0.002f && i == 0) lastChangeMs = ms;
            DISP[i] += (t - DISP[i]) * Math.min(1f, dt * 10f);
            if (t >= TRAIL[i]) { TRAIL[i] = DISP[i]; TRAIL_HOLD[i] = ms; }
            else if (ms - TRAIL_HOLD[i] > 500) TRAIL[i] = Math.max(DISP[i], TRAIL[i] - dt * 0.35f);
        }
        boolean calm = target[0] >= 0.98f && target[1] >= 0.5f && target[2] >= 0.5f && !exhausted() && ms - lastChangeMs > 3000;
        barAlpha += ((calm ? 0.4f : 1f) - barAlpha) * Math.min(1f, dt * 4f);

        int rowH = 7, bw = width - 9 - 14;
        boolean ex = exhausted();
        drawBar(g, font, x0, bottom - rowH * 3 + 1, bw, 0, ICON_BOLT, ex ? 0xFFE0463C : 0xFFF2C84B, ex ? 0xFF8A1E1A : 0xFFB5781E,
                target[0] < 0.2f || ex, ex, ms);
        drawBar(g, font, x0, bottom - rowH * 2 + 1, bw, 1, ICON_DROP, 0xFF5CC3FF, 0xFF1F5FB8, target[1] < 0.15f, false, ms);
        drawBar(g, font, x0, bottom - rowH + 1, bw, 2, ICON_MOON, 0xFFB9A2FF, 0xFF5A40B0, target[2] < 0.15f, false, ms);
    }

    private static int alpha(int argb, float a) {
        int al = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    private static void drawBar(GuiGraphics g, Font font, int x, int y, int bw, int idx, String[] icon, int top, int bot,
                                boolean warn, boolean overexert, long ms) {
        float a = barAlpha;
        float pulse = warn ? (float) (0.5 + 0.5 * Math.sin(ms / 160.0)) : 0f;
        // 图标（带 1 像素阴影）
        int iconColor = warn ? blend(top, 0xFFFFFFFF, pulse * 0.6f) : top;
        for (int r = 0; r < icon.length; r++) {
            for (int c = 0; c < icon[r].length(); c++) {
                char ch = icon[r].charAt(c);
                if (ch == '.') continue;
                g.fill(x + c + 1, y + r - 1 + 1, x + c + 2, y + r + 1, alpha(0xA0000000, a));
                g.fill(x + c, y + r - 1, x + c + 1, y + r, alpha(ch == 'o' ? 0xFFFFFFFF : iconColor, a));
            }
        }
        int bx = x + 9, by = y, bh = 5;
        // 外框（圆角）与低值闪烁描边
        int frame = warn ? blend(0xFF18121E, 0xFFFF5A4A, pulse) : 0xFF18121E;
        g.fill(bx + 1, by - 1, bx + bw - 1, by, alpha(frame, a * 0.9f));
        g.fill(bx + 1, by + bh, bx + bw - 1, by + bh + 1, alpha(frame, a * 0.9f));
        g.fill(bx, by, bx + 1, by + bh, alpha(frame, a * 0.9f));
        g.fill(bx + bw - 1, by, bx + bw, by + bh, alpha(frame, a * 0.9f));
        int ix = bx + 1, iw = bw - 2;
        g.fill(ix, by, ix + iw, by + bh, alpha(0x70000000, a));
        // 残影
        int tw = Math.round(iw * TRAIL[idx]), fw = Math.round(iw * DISP[idx]);
        if (tw > fw) g.fill(ix + fw, by, ix + tw, by + bh, alpha(0x90FFFFFF, a * 0.55f));
        // 填充：上亮下暗渐变 + 顶部高光
        if (fw > 0) {
            g.fillGradient(ix, by, ix + fw, by + bh, alpha(top, a), alpha(bot, a));
            g.fill(ix, by, ix + fw, by + 1, alpha(0x55FFFFFF, a));
            if (overexert) {
                // 体力透支：流动斜纹
                int off = (int) ((ms / 60) % 6);
                for (int px = 0; px < fw; px++)
                    for (int k = 0; k < bh; k++)
                        if ((px + k + off) % 6 < 2) g.fill(ix + px, by + k, ix + px + 1, by + k + 1, alpha(0x50000000, a));
            }
            // 末端亮点
            g.fill(ix + fw - 1, by, ix + fw, by + bh, alpha(0x60FFFFFF, a));
        }
        // 四分刻度
        for (int q = 1; q < 4; q++) {
            int qx = ix + iw * q / 4;
            g.fill(qx, by + 1, qx + 1, by + bh - 1, alpha(0x40000000, a));
        }
        // 数值（小字）
        String txt = overexert ? Component.translatable("hud.zhushenspace.survival.overexert").getString()
                : Math.round(DISP[idx] * 100) + "%";
        g.pose().pushPose();
        g.pose().translate(bx + bw + 2, by, 0);
        g.pose().scale(0.6f, 0.6f, 1f);
        g.drawString(font, txt, 0, 0, alpha(warn ? blend(0xFFD8D0E8, 0xFFFF6A5A, pulse) : 0xFFD8D0E8, a), true);
        g.pose().popPose();
    }

    private static int blend(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a1 = c1 >>> 24, r1 = c1 >> 16 & 0xFF, g1 = c1 >> 8 & 0xFF, b1 = c1 & 0xFF;
        int a2 = c2 >>> 24, r2 = c2 >> 16 & 0xFF, g2 = c2 >> 8 & 0xFF, b2 = c2 & 0xFF;
        return (Math.round(a1 + (a2 - a1) * t) << 24) | (Math.round(r1 + (r2 - r1) * t) << 16)
                | (Math.round(g1 + (g2 - g1) * t) << 8) | Math.round(b1 + (b2 - b1) * t);
    }

    /** 失去一只眼：该侧视野变暗（右眼 → 画面右侧） */
    private static void sideShade(GuiGraphics g, int w, int h, boolean right) {
        int strips = 24, half = w / 2;
        for (int i = 0; i < strips; i++) {
            int a = (int) (40 + 200 * (i / (double) (strips - 1)));
            int x1 = half * i / strips, x2 = half * (i + 1) / strips;
            if (right) g.fill(half + x1, 0, half + x2, h, a << 24);
            else g.fill(half - x2, 0, half - x1, h, a << 24);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        has = false;
        flags = 0;
        permanent = 0;
        conditions = 0;
        limbDisabled = 0;
        openWounds = 0;
        breath = -1f;
        points = new int[StatusType.COUNT];
        tiers = new int[StatusType.COUNT];
        thirst = sleep = 100;
    }
}

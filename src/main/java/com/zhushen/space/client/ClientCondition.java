package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.common.SurvivalManager;
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
        // 力竭：无法冲刺（前进力度压到冲刺门槛以下，走路也会慢一些）
        if (exhausted() && in.forwardImpulse > 0.79f) in.forwardImpulse = 0.79f;
        // 倒地：不能跳（按跳跃键 = 爬起来）
        if (prone() && !freeMover()) in.jumping = false;
    }

    @SubscribeEvent
    public static void onTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!active(p)) return;
        if (exhausted() && p.isSprinting()) p.setSprinting(false);
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
        if (permanent(StatusType.TINNITUS)) vol = 0f;
        else if (tier(StatusType.TINNITUS) >= StatusType.Tier.HEAVY.ordinal()) vol = 0.15f;
        else if (tier(StatusType.TINNITUS) >= StatusType.Tier.LIGHT.ordinal()) vol = 0.6f;
        else return;
        if (vol <= 0f) {
            event.setSound(null);
            return;
        }
        if (s instanceof TickableSoundInstance) return; // 持续音效（音乐唱片 / 矿车等）保持原样
        event.setSound(new Muffled(s, vol));
    }

    /** 音量缩放的声音包装 */
    private record Muffled(SoundInstance in, float factor) implements SoundInstance {
        @Override public ResourceLocation getLocation() { return in.getLocation(); }
        @Override public WeighedSoundEvents resolve(SoundManager m) { return in.resolve(m); }
        @Override public Sound getSound() { return in.getSound(); }
        @Override public SoundSource getSource() { return in.getSource(); }
        @Override public boolean isLooping() { return in.isLooping(); }
        @Override public boolean isRelative() { return in.isRelative(); }
        @Override public int getDelay() { return in.getDelay(); }
        @Override public float getVolume() { return in.getVolume() * factor; }
        @Override public float getPitch() { return in.getPitch(); }
        @Override public double getX() { return in.getX(); }
        @Override public double getY() { return in.getY(); }
        @Override public double getZ() { return in.getZ(); }
        @Override public Attenuation getAttenuation() { return in.getAttenuation(); }
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
        boolean blind = (eyes & 3) == 3 || permanent(StatusType.DAZZLE);
        if (blind) g.fill(0, 0, w, h, 0xE0000000);
        else {
            if ((eyes & 1) != 0) sideShade(g, w, h, true);
            if ((eyes & 2) != 0) sideShade(g, w, h, false);
            int dz = tier(StatusType.DAZZLE);
            if (dz >= StatusType.Tier.HEAVY.ordinal()) g.fill(0, 0, w, h, 0xB0101010);
            else if (dz == StatusType.Tier.LIGHT.ordinal()) {
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
        if (incapacitated() && permanent(StatusType.FREEZE)) {
            g.drawCenteredString(font, Component.translatable("hud.zhushenspace.status.frozen_solid"), w / 2, h / 2 + 20, 0xFFBFE9FF);
        }

        // --- 生存条（快捷栏右侧：水分 / 体力 / 精力） ---
        int x0 = w / 2 + 91 + 6, bottom = h - 2, bh = 20;
        bar(g, font, x0, bottom, bh, thirst / SurvivalManager.MAX, 0xFF3FA9F5, "hud.zhushenspace.survival.thirst_short", thirst < 15, ms);
        bar(g, font, x0 + 11, bottom, bh, stamina / staminaMax, exhausted() ? 0xFFE04040 : 0xFFE8C547,
                "hud.zhushenspace.survival.stamina_short", exhausted(), ms);
        bar(g, font, x0 + 22, bottom, bh, sleep / SurvivalManager.MAX, 0xFFA58CFF, "hud.zhushenspace.survival.sleep_short", sleep < 15, ms);

        // --- 不良状态 / 倒地 ---
        int y = bottom - bh - 22;
        if (prone()) {
            g.drawString(font, Component.translatable(standing() ? "hud.zhushenspace.prone.standing" : "hud.zhushenspace.prone.hint"),
                    x0, y, 0xFFFFD27F, true);
            y -= 10;
        }
        for (StatusType t : StatusType.values()) {
            boolean perm = permanent(t);
            int tr = tier(t);
            if (tr == 0 && !perm) continue;
            int color = perm || tr >= 3 ? 0xFFFF4040 : tr == 2 ? 0xFFFF9A3C : 0xFFFFE070;
            Component name = perm ? Component.translatable(t.tierKey(StatusType.Tier.DESTRUCTIVE))
                    : Component.translatable("hud.zhushenspace.status.line", Component.translatable(t.tierKey(StatusType.Tier.values()[tr])),
                    points(t), heavy[t.ordinal()], destr[t.ordinal()]);
            g.drawString(font, name, x0, y, color, true);
            y -= 10;
        }
    }

    private static void bar(GuiGraphics g, Font font, int x, int bottom, int bh, float frac, int color, String label,
                            boolean warn, long ms) {
        frac = Math.max(0f, Math.min(1f, frac));
        g.fill(x, bottom - bh, x + 7, bottom, 0x90000000);
        int fh = Math.round((bh - 2) * frac);
        int c = warn && (ms / 300) % 2 == 0 ? 0xFFFFFFFF : color;
        g.fill(x + 1, bottom - 1 - fh, x + 6, bottom - 1, c);
        g.pose().pushPose();
        g.pose().translate(x + 3.5f, bottom - bh - 8, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        Component l = Component.translatable(label);
        g.drawString(font, l, -font.width(l) / 2, 0, color, true);
        g.pose().popPose();
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
        points = new int[StatusType.COUNT];
        tiers = new int[StatusType.COUNT];
        thirst = sleep = 100;
    }
}

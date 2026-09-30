package com.zhushen.space.common;

import com.zhushen.space.sound.ModSounds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Presentation helpers only. Visuals now belong to the actual projectile entity renderer. */
public final class ArtFx {
    private ArtFx() {}
    public static void anim(ServerPlayer p, String name) { TaiChiFx.anim(p, name); }
    public static void soundAt(ServerPlayer p, Vec3 at, SoundEvent event, float volume, float pitch) {
        p.level().playSound(null, at.x, at.y, at.z, event, SoundSource.PLAYERS, volume, pitch);
    }
    public static void castSfx(ServerPlayer p, FeatEffects.Pool pool, float power) {
        soundAt(p, p.position(), ModSounds.artSfx(pool).cast().get(), 0.45f + power * 0.2f, 1f);
    }
    public static void releaseSfx(ServerPlayer p, FeatEffects.Pool pool, float power) {
        soundAt(p, p.position(), ModSounds.artSfx(pool).release().get(), 0.6f + power * 0.2f, 1f);
    }
}

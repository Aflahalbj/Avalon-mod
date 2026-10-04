package id.avalon.core;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Efek ke player: title, action bar, sound, partikel.
 * Pengganti Player#sendTitle, #sendActionBar, #playSound, World#spawnParticle.
 */
public final class Fx {

    private Fx() {}

    // ── Title ─────────────────────────────────────────────────────────────────

    /** Setara Player#sendTitle(title, subtitle, fadeIn, stay, fadeOut) dengan string legacy (§). */
    public static void title(ServerPlayer p, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        title(p, Component.literal(title), Component.literal(subtitle), fadeIn, stay, fadeOut);
    }

    public static void title(ServerPlayer p, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        if (p == null) return;
        p.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    // ── Action bar ────────────────────────────────────────────────────────────

    public static void actionBar(ServerPlayer p, Component message) {
        if (p == null) return;
        p.displayClientMessage(message, true);
    }

    // ── Sound ─────────────────────────────────────────────────────────────────

    /** Setara Player#playSound(player.getLocation(), sound, volume, pitch): hanya terdengar oleh player ini. */
    public static void sound(ServerPlayer p, SoundEvent sound, float volume, float pitch) {
        sound(p, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch);
    }

    public static void sound(ServerPlayer p, Holder<SoundEvent> sound, float volume, float pitch) {
        if (p == null) return;
        p.connection.send(new ClientboundSoundPacket(
                sound, SoundSource.MASTER,
                p.getX(), p.getY(), p.getZ(),
                volume, pitch, p.getRandom().nextLong()));
    }

    /** Setara World#playSound(location, sound, volume, pitch): terdengar oleh semua di sekitar. */
    public static void worldSound(ServerLevel level, double x, double y, double z, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, x, y, z, sound, SoundSource.MASTER, volume, pitch);
    }

    // ── Particle ──────────────────────────────────────────────────────────────

    public static void particle(ServerLevel level, ParticleOptions type, double x, double y, double z,
                                int count, double dx, double dy, double dz, double speed) {
        level.sendParticles(type, x, y, z, count, dx, dy, dz, speed);
    }
}

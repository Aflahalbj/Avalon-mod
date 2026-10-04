package id.avalon.core;

import id.avalon.network.AvalonNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pengganti Attribute.SCALE (tidak ada di 1.20.1).
 * Skala disimpan di server, dikirim ke client, lalu dipakai untuk hitbox,
 * tinggi mata (EntityEvent.Size) dan render (RenderPlayerEvent).
 */
public final class PlayerScale {

    private static final Map<UUID, Float> scales = new HashMap<>();

    private PlayerScale() {}

    public static float get(Player player) {
        return scales.getOrDefault(player.getUUID(), 1.0f);
    }

    /** Setara player.getAttribute(Attribute.SCALE).setBaseValue(scale). */
    public static void set(ServerPlayer player, double scale) {
        if (player == null) return;
        float s = (float) scale;
        Float old = scales.get(player.getUUID());
        if (Math.abs(s - 1.0f) < 1.0e-4f) {
            scales.remove(player.getUUID());
        } else {
            scales.put(player.getUUID(), s);
        }
        if (old == null ? s != 1.0f : old != s) {
            player.refreshDimensions();
        }
        AvalonNetwork.sendTrackingAndSelf(player, new AvalonNetwork.Scale(player.getId(), s));
    }

    /** Kirim skala player {@code target} ke {@code tracker} saat mulai terlihat. */
    public static void syncTo(ServerPlayer tracker, Player target) {
        float s = get(target);
        if (s != 1.0f) {
            AvalonNetwork.sendTo(tracker, new AvalonNetwork.Scale(target.getId(), s));
        }
    }

    public static void forget(Player player) {
        scales.remove(player.getUUID());
    }

    public static void clear() {
        scales.clear();
    }
}

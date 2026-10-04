package id.avalon.listeners;

import id.avalon.core.PlayerScale;
import id.avalon.core.Scheduler;
import id.avalon.managers.GameManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Menangani player yang disconnect / reconnect di tengah game.
 *
 * Alur disconnect:
 *  - Spawn mannequin di posisi terakhir player
 *  - Broadcast pesan disconnect
 *  - Logika per fase:
 *      King selection → grace 90 detik lalu auto-rotasi raja
 *      Voting         → cek apakah semua online sudah vote (auto-finish)
 *      Mission        → cek apakah semua anggota tim offline (abort misi)
 *      Assassination  → grace 90 detik lalu kubu baik menang default
 *      Discussion     → hapus vote skip player tsb, cek apakah sisa sudah skip semua
 *
 * Alur reconnect:
 *  - Hapus mannequin
 *  - Broadcast pesan reconnect
 *  - Cancel grace timer jika ada
 *  - Restore item/state sesuai fase aktif
 */
public class PlayerOfflineHandler {

    private final GameManager gameManager;

    public PlayerOfflineHandler(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ItemGuard.forget(player.getUUID());

        if (gameManager.isGameRunning()
                && gameManager.getRegisteredPlayers().contains(player.getGameProfile().getName())) {
            gameManager.handlePlayerOffline(player);
        }

        PlayerScale.forget(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // Sinkronkan state lock kamera/gerakan ke client baru
        gameManager.resendLocks(player);

        if (!gameManager.isGameRunning()) return;
        if (!gameManager.getRegisteredPlayers().contains(player.getGameProfile().getName())) return;

        // Delay 20 tick agar client sepenuhnya loaded sebelum restore state
        Scheduler.later(20L, () -> {
            if (!gameManager.isGameRunning()) return;
            if (!gameManager.isOnline(player)) return;
            gameManager.handlePlayerOnline(player);
        });
    }
}

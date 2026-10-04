package id.avalon.listeners;

import id.avalon.managers.GameManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Pengganti CutsceneListener plugin:
 *  - Lock gerakan & kamera ditangani GameManager#tick (server) + ClientEvents (client),
 *    karena di Forge rotasi/gerakan player dikontrol client.
 *  - Cegah player turun dari kursi avalon_seat (EntityDismountEvent).
 */
public class CutsceneListener {

    private final GameManager gm;

    public CutsceneListener(GameManager gm) {
        this.gm = gm;
    }

    @SubscribeEvent
    public void onDismount(EntityMountEvent event) {

        if (!event.isDismounting())
            return;

        if (!(event.getEntityMounting() instanceof Player player))
            return;

        if (player.level().isClientSide)
            return;

        Entity mounted = event.getEntityBeingMounted();
        if (!(mounted instanceof ArmorStand stand))
            return;

        if (!stand.getTags().contains("avalon_seat"))
            return;

        // Kursi sedang dihapus / player keluar server → izinkan
        if (stand.isRemoved())
            return;
        if (player instanceof ServerPlayer sp && sp.hasDisconnected())
            return;

        // Game sudah selesai → izinkan turun
        if (!gm.isGameRunning())
            return;

        // Izinkan eject kalau game manager sedang dalam fase reveal
        // (supaya standAsViewer() dan standAsTarget() bisa eject player)
        if (gm.isRevealPhaseActive())
            return;

        event.setCanceled(true);
    }
}

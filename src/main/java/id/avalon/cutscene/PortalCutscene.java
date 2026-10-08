package id.avalon.cutscene;

import id.avalon.core.PlayerScale;
import id.avalon.core.Scheduler;
import id.avalon.core.Task;
import id.avalon.managers.GameManager;
import id.avalon.network.AvalonNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Cutscene portal (sisi server): portal terbuka di overworld, player tersedot satu per satu,
 * lalu masing-masing diserahkan ke pemanggil (game: didudukkan di dimensi Avalon; tes: dibebaskan di tempat).
 *
 * Server hanya mengunci player, membagikan naskah (urutan + gaya animasi) dan memindahkan player
 * tepat waktu. Gerakan tersedotnya murni visual di client (PortalCutsceneClient) supaya mulus:
 * entity player sebenarnya tetap diam di tempat sampai dipindahkan.
 */
public final class PortalCutscene {

    private PortalCutscene() {}

    /** Jarak mendatar & tinggi portal dari titik tengah para player. */
    private static final double PORTAL_DISTANCE = 13.0;
    private static final double PORTAL_HEIGHT = 6.5;
    private static final float PORTAL_RADIUS = 4.0f;

    /** Player sejauh ini dari portal ikut melihat cutscene. */
    private static final double VIEW_RANGE = 96.0;

    private static final List<Task> tasks = new ArrayList<>();
    /** Player yang masih terkunci (belum dipindahkan). */
    private static final List<ServerPlayer> locked = new ArrayList<>();
    private static ServerLevel level;
    private static Vec3 portal;
    private static boolean running = false;
    private static Consumer<ServerPlayer> arriveHandler;

    public static boolean isRunning() {
        return running;
    }

    /**
     * Mainkan cutscene untuk {@code players}; portal terbuka di arah {@code yaw} dari titik tengah mereka.
     *
     * @param style    nomor gaya animasi untuk semua player (lihat {@link PortalTimeline#STYLE_NAMES}),
     *                 atau -1 untuk gaya acak yang berbeda-beda
     * @param onArrive dipanggil untuk tiap player begitu ia masuk portal (mis. memindahkannya ke
     *                 dimensi Avalon); null = dibebaskan di tempat (untuk tes)
     */
    public static void play(GameManager gm, ServerLevel world, List<ServerPlayer> players, float yaw,
                            int style, Consumer<ServerPlayer> onArrive) {
        if (running || players.isEmpty()) return;
        running = true;
        level = world;
        arriveHandler = onArrive;

        double cx = 0, cy = 0, cz = 0;
        for (ServerPlayer p : players) {
            cx += p.getX();
            cy += p.getY();
            cz += p.getZ();
        }
        cx /= players.size();
        cy /= players.size();
        cz /= players.size();

        float dirX = -Mth.sin(yaw * Mth.DEG_TO_RAD);
        float dirZ = Mth.cos(yaw * Mth.DEG_TO_RAD);
        portal = new Vec3(cx + dirX * PORTAL_DISTANCE, cy + PORTAL_HEIGHT, cz + dirZ * PORTAL_DISTANCE);

        // Gaya animasi diacak; gaya baru berulang kalau player lebih banyak dari jumlah gaya
        List<ServerPlayer> order = new ArrayList<>(players);
        Collections.shuffle(order);
        List<Integer> styles = new ArrayList<>();
        for (int i = 0; i < PortalTimeline.STYLE_COUNT; i++) styles.add(i);
        Collections.shuffle(styles);

        List<AvalonNetwork.PortalActor> actors = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            ServerPlayer p = order.get(i);
            int delay = PortalTimeline.delay(i);

            // Hanya gerakan yang dikunci: arah hadap dibiarkan, player tetap bebas menoleh dengan mouse
            gm.lockMovement(p);
            locked.add(p);

            actors.add(new AvalonNetwork.PortalActor(p.getId(), delay, style >= 0 ? style : styles.get(i % styles.size()),
                    PlayerScale.get(p), p.getX(), p.getY(), p.getZ()));

            tasks.add(Scheduler.later(delay + PortalTimeline.PLAYER_TICKS + PortalTimeline.TELEPORT_DELAY,
                    () -> arrive(gm, p)));
        }

        AvalonNetwork.PortalStart start = new AvalonNetwork.PortalStart(
                portal.x, portal.y, portal.z, dirX, dirZ, PORTAL_RADIUS, cx, cy, cz, actors);
        for (ServerPlayer viewer : level.players()) {
            if (viewer.distanceToSqr(portal) <= VIEW_RANGE * VIEW_RANGE) {
                AvalonNetwork.sendTo(viewer, start);
            }
        }

        int lastDelay = PortalTimeline.delay(order.size() - 1);
        tasks.add(Scheduler.later(PortalTimeline.end(lastDelay), PortalCutscene::reset));
    }

    /** Player sudah masuk portal: buka kunci, lalu serahkan ke {@code onArrive} atau bebaskan di tempat. */
    private static void arrive(GameManager gm, ServerPlayer p) {
        locked.remove(p);
        unlock(gm, p);
        // Keluar (atau keluar-masuk) sebelum gilirannya: game yang menyusulkannya saat ia online lagi
        if (!gm.isOnline(p)) return;

        p.fallDistance = 0;
        ServerLevel from = level;
        if (arriveHandler != null) arriveHandler.accept(p);
        if (p.serverLevel() == from) {
            // Tetap di dunia ini: kembalikan kamera, jangan biarkan client tertahan di layar putih
            AvalonNetwork.sendTo(p, new AvalonNetwork.PortalStop(true));
        }
    }

    /** Buka kunci gerakan; kalau player sudah keluar-masuk, client barunya yang diberi tahu. */
    private static void unlock(GameManager gm, ServerPlayer p) {
        ServerPlayer now = gm.getPlayer(p.getUUID());
        gm.unlockMovement(now != null ? now : p);
    }

    /** Hentikan cutscene di tengah jalan: player yang belum tersedot dibebaskan di tempat. */
    public static boolean stop(GameManager gm) {
        if (!running) return false;

        for (ServerPlayer p : locked) {
            unlock(gm, p);
        }
        if (level != null) {
            for (ServerPlayer viewer : level.players()) {
                AvalonNetwork.sendTo(viewer, new AvalonNetwork.PortalStop(false));
            }
        }
        reset();
        return true;
    }

    /** Lupakan semua state (cutscene selesai / server berhenti). */
    public static void reset() {
        for (Task task : tasks) task.cancel();
        tasks.clear();
        locked.clear();
        level = null;
        portal = null;
        arriveHandler = null;
        running = false;
    }
}

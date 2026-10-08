package id.avalon.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Pengganti BukkitScheduler berbasis tick server.
 *
 * Semantik mengikuti Bukkit: task yang dijadwalkan dengan delay 0 dijalankan
 * pada tick berikutnya, bukan langsung.
 */
public final class Scheduler {

    private static final List<Task> tasks = new ArrayList<>();
    private static final List<Task> pending = new ArrayList<>();
    private static long currentTick = 0;
    private static boolean paused = false;

    private Scheduler() {}

    /**
     * Bekukan / lanjutkan semua task: selama beku tidak ada yang dijalankan dan sisa waktu tiap task
     * tidak berkurang (dipakai saat game berjalan tapi tidak ada satu pun pemainnya yang online).
     */
    public static void setPaused(boolean value) {
        paused = value;
    }

    /** Dipanggil sekali per tick server (ServerTickEvent END). */
    public static void tick() {
        currentTick++;

        tasks.addAll(pending);
        pending.clear();

        if (paused) {
            for (Task task : tasks) task.nextRun++;
            return;
        }

        List<Task> snapshot = new ArrayList<>(tasks);
        for (Task task : snapshot) {
            if (task.isCancelled()) continue;
            if (task.nextRun > currentTick) continue;

            try {
                task.run();
            } catch (Throwable t) {
                AvalonLog.error("Task error", t);
                task.cancel();
            }

            if (task.period <= 0) {
                task.cancel();
            } else if (!task.isCancelled()) {
                task.nextRun = currentTick + task.period;
            }
        }
        tasks.removeIf(Task::isCancelled);
    }

    static void schedule(Task task, long delay, long period) {
        task.nextRun = currentTick + Math.max(1, delay);
        task.period = period;
        pending.add(task);
    }

    /** Hentikan semua task (server stop). */
    public static void clear() {
        for (Task t : tasks) t.cancel();
        for (Task t : pending) t.cancel();
        tasks.clear();
        pending.clear();
        paused = false;
    }

    /** Jalankan runnable sekali setelah delay tick (setara runTaskLater). */
    public static Task later(long delay, Runnable r) {
        return new Task() {
            @Override
            public void run() {
                r.run();
            }
        }.runLater(delay);
    }

    /** Jalankan runnable pada tick berikutnya (setara runTask). */
    public static Task next(Runnable r) {
        return later(1, r);
    }
}

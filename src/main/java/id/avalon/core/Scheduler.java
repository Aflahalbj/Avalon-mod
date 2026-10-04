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

    private Scheduler() {}

    /** Dipanggil sekali per tick server (ServerTickEvent END). */
    public static void tick() {
        currentTick++;

        tasks.addAll(pending);
        pending.clear();

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

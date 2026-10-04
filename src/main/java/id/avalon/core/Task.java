package id.avalon.core;

/**
 * Pengganti BukkitRunnable / BukkitTask.
 * Subclass mengisi {@link #run()} dan boleh memanggil {@link #cancel()} di dalamnya.
 */
public abstract class Task implements Runnable {

    long nextRun;
    long period;
    private boolean cancelled = false;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Setara runTaskLater(plugin, delay). */
    public Task runLater(long delay) {
        Scheduler.schedule(this, delay, 0);
        return this;
    }

    /** Setara runTaskTimer(plugin, delay, period). */
    public Task runTimer(long delay, long period) {
        Scheduler.schedule(this, delay, Math.max(1, period));
        return this;
    }
}

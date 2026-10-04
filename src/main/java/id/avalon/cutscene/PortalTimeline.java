package id.avalon.cutscene;

import java.util.List;

/**
 * Jadwal cutscene portal dalam tick, dihitung dari saat cutscene dimulai.
 * Dipakai server (kapan player dipindahkan) dan client (animasi), jadi tidak boleh
 * menyentuh kelas client-only.
 */
public final class PortalTimeline {

    private PortalTimeline() {}

    /** Celah cahaya mulai melebar jadi portal. */
    public static final int OPEN_START = 24;
    /** Portal terbuka penuh. */
    public static final int OPEN_END = 58;

    /** Para player mulai terangkat. */
    public static final int FIRST_PULL = 84;
    /** Jeda antar player; 0 = semua tersedot bersamaan. */
    public static final int STAGGER = 0;

    /** Lama player terangkat / meronta sebelum ditarik. */
    public static final int LIFT_TICKS = 26;
    /** Lama player melayang sampai masuk portal. */
    public static final int PULL_TICKS = 40;
    public static final int PLAYER_TICKS = LIFT_TICKS + PULL_TICKS;

    /** Jeda dari player hilang di portal sampai benar-benar dipindahkan (layar sudah putih). */
    public static final int TELEPORT_DELAY = 14;

    public static final int CLOSE_DELAY = 20;
    public static final int CLOSE_TICKS = 30;

    /** Nama gaya animasi; indeksnya = nomor gaya (urutan sama dengan konstanta PortalActorAnim di client). */
    public static final List<String> STYLE_NAMES = List.of(
            "baling", "keseret", "salto", "superman", "ngelawan", "spiral", "ragdoll", "kejerat");
    public static final int STYLE_COUNT = STYLE_NAMES.size();

    /** Tick mulai untuk player urutan ke-{@code index}. */
    public static int delay(int index) {
        return FIRST_PULL + index * STAGGER;
    }

    /** Portal mulai menutup; {@code lastDelay} = tick mulai player terakhir. */
    public static int closeStart(int lastDelay) {
        return lastDelay + PLAYER_TICKS + CLOSE_DELAY;
    }

    public static int end(int lastDelay) {
        return closeStart(lastDelay) + CLOSE_TICKS + 20;
    }
}

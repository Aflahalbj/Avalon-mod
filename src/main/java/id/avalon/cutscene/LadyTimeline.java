package id.avalon.cutscene;

/**
 * Jadwal animasi pemeriksaan Lady of the Lake dalam tick, dihitung dari saat target dipilih.
 * Dipakai server (kapan hasil diberitahukan & token berpindah) dan client (animasi).
 */
public final class LadyTimeline {

    private LadyTimeline() {}

    /** Roh mulai ditarik keluar dari dada pemain yang diperiksa. */
    public static final int DRAW_START = 20;
    /** Roh meninggalkan pemain yang diperiksa dan mengitari tengah lingkaran kursi. */
    public static final int ORBIT_START = 45;
    /** Roh menukik ke pemegang Lady. */
    public static final int DIVE_START = 88;
    /** Roh masuk ke pemegang Lady: hasilnya diberitahukan kepadanya. */
    public static final int ARRIVE = 102;

    /** Token berpindah ke pemain yang diperiksa, dan lama terbangnya. */
    public static final int TOKEN = 122;
    public static final int TOKEN_FLIGHT = 30;

    /** Animasi selesai: game lanjut ke fase diskusi. */
    public static final int END = 170;
}

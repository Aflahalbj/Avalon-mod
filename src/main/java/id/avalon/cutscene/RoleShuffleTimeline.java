package id.avalon.cutscene;

/**
 * Jadwal animasi kocok peran dalam tick, dihitung dari saat animasi dimulai.
 * Dipakai server (kapan nama peran diumumkan) dan client (animasi).
 */
public final class RoleShuffleTimeline {

    private RoleShuffleTimeline() {}

    /** Roh-roh mulai naik dari bola di bawah lantai. */
    public static final int RISE_START = 30;
    /** Jeda antar roh saat naik. */
    public static final float RISE_STAGGER = 2.0f;

    /** Roh-roh berhenti berputar dan melesat ke tiap kursi. */
    public static final int DEAL_START = 115;
    /** Jeda berangkat antar roh, dan lama terbangnya ke kursi. */
    public static final float DEAL_STAGGER = 0.8f;
    public static final float DEAL_FLIGHT = 14f;

    /** Semua roh sudah sampai: nama peran diumumkan ke tiap player. */
    public static final int REVEAL = 138;

    /** Lingkaran di lantai selesai memudar. */
    public static final int END = 185;

    /** Tick roh ke-{@code seat} sampai di kursinya. */
    public static float arrival(int seat) {
        return DEAL_START + seat * DEAL_STAGGER + DEAL_FLIGHT;
    }
}

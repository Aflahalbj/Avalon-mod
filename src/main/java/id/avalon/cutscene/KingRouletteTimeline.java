package id.avalon.cutscene;

/**
 * Jadwal animasi kocok raja dalam tick, dihitung dari saat animasi dimulai.
 * Dipakai server (kapan raja diumumkan) dan client (animasi pedang & mahkota).
 */
public final class KingRouletteTimeline {

    private KingRouletteTimeline() {}

    /** Pedang selesai naik dari bola. */
    public static final int RISE_END = 30;
    /** Pedang rebah dari tegak menjadi mendatar (seperti jarum kompas). */
    public static final int TILT_START = 24;
    public static final int TILT_END = 36;

    /** Pedang berputar dari kencang sampai hampir berhenti, sedikit melewati kursi raja. */
    public static final int SPIN_START = 36;
    public static final int SPIN_END = 120;
    /** Pedang mundur dan mengunci ke kursi raja, lalu menembakkan partikel ke raja. */
    public static final int LOCK = 134;
    /** Lama semburan partikel dari ujung pedang sampai ke raja. */
    public static final int BOLT_TICKS = 6;

    /** Raja diumumkan (title & chat), sesaat setelah semburan sampai. */
    public static final int REVEAL = LOCK + BOLT_TICKS + 2;

    /** Pedang tegak lagi lalu tenggelam ke bola. */
    public static final int SINK_START = 150;
    public static final int END = 182;

    /** Berapa putaran penuh sebelum berhenti, dan seberapa jauh (derajat) pedang kebablasan. */
    public static final int TURNS = 5;
    public static final float OVERSHOOT = 14f;
}

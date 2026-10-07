package id.avalon.cutscene;

import id.avalon.block.PillarBlock;

import java.util.List;

/**
 * Linimasa cutscene akhir game (tick sejak cutscene dimulai), dipakai server (kapan pilar diubah,
 * kapan cutscene selesai) dan client (kamera & animasi).
 *
 *   MENANG — bola tiga pilar saling terhubung jadi segitiga, mengalirkan cahaya ke bola tengah,
 *            bola tengah menembak bola kaca di cincin portal sampai pecah menjadi portal (blok),
 *            lalu kubu baik berpamitan dan masuk portal satu per satu.
 *   KALAH  — bola pilar yang sudah menyala memerah lalu meledak satu per satu (disorot dari dekat),
 *            lalu kubu jahat berdiri dengan aura merah. Tidak ada pilar menyala = langsung ke kubu jahat.
 *   MERLIN — reka ulang Merlin tertembak busur assassin lalu tumbang, kemudian sama seperti KALAH.
 */
public final class EndingTimeline {

    private EndingTimeline() {}

    public static final int WIN = 0;
    public static final int EVIL = 1;
    public static final int MERLIN = 2;

    /** Nama tiap jenis ending untuk command; indeksnya = nomor jenis. */
    public static final List<String> NAMES = List.of("menang", "kalah", "merlin");

    /** Jumlah gaya berpamitan (lihat EndingClient). */
    public static final int FAREWELL_STYLES = 5;

    // ── Menang ────────────────────────────────────────────────────────────────
    /** Sisi segitiga ditarik satu per satu (kamera mengikuti ujungnya); tiap sisi selesai sebelum sisi berikutnya. */
    public static final int WIN_EDGE_START = 20;
    public static final int WIN_EDGE_GAP = 50;
    public static final int WIN_EDGE_TICKS = 46;
    /** Cahaya dari tiga bola turun ke bola tengah. */
    public static final int WIN_CORE_START = WIN_EDGE_START + 3 * WIN_EDGE_GAP;
    public static final int WIN_CORE_TICKS = 36;
    /** Bola tengah mengumpulkan energi: membesar, berdenyut makin cepat, berubah dari biru ke ungu. */
    public static final int WIN_CHARGE_START = WIN_CORE_START + WIN_CORE_TICKS;
    public static final int WIN_CHARGE_TICKS = 64;
    /** Baru setelah itu ia memancarkan sinar ungu ke bola kaca di tengah cincin portal. */
    public static final int WIN_BEAM_START = WIN_CHARGE_START + WIN_CHARGE_TICKS;
    public static final int WIN_BEAM_TICKS = 18;
    /** Bola kaca pecah, lalu blok portal menyebar dari tengah sampai memenuhi cincin. */
    public static final int WIN_SHATTER_START = WIN_BEAM_START + WIN_BEAM_TICKS;
    public static final int WIN_SHATTER_TICKS = 10;
    public static final int WIN_GATE_OPEN_START = WIN_SHATTER_START + 4;
    public static final int WIN_GATE_OPEN_END = WIN_GATE_OPEN_START + 32;
    /** Perpisahan: semua player baik berpamitan, lalu berbalik dan melangkah masuk portal bersama-sama. */
    public static final int FAREWELL_START = WIN_GATE_OPEN_END + 14;
    public static final int FAREWELL_LEAD = 12;
    /**
     * Selama ending menang para player duduk di kursinya; baru sesaat sebelum perpisahan mereka
     * dipindahkan ke depan portal (layar berkilat putih tepat di pergantian sorotannya).
     */
    public static final int FAREWELL_MOVE_AT = FAREWELL_START - 3;
    public static final int FAREWELL_FLASH_TICKS = 7;
    /** Jeda antar player; 0 = semua berpamitan dan masuk portal bersamaan. */
    public static final int FAREWELL_STAGGER = 0;
    /** Satu player: berpamitan menghadap kubu jahat, berbalik ke portal, lalu melangkah masuk. */
    public static final int ACTOR_TURN_END = 12;
    public static final int ACTOR_GESTURE_END = 52;
    public static final int ACTOR_BACK_END = 62;
    /** Berjalan santai ke portal (bukan berlari): sekitar 2 detik. */
    public static final int ACTOR_TICKS = ACTOR_BACK_END + 44;
    /** Setelah yang terakhir masuk: jeda, portal menutup, layar memutih. */
    public static final int WIN_CLOSE_DELAY = 14;
    public static final int WIN_CLOSE_TICKS = 26;

    /** Tick player baik urutan ke-{@code order} mulai berpamitan. */
    public static int farewellAt(int order) {
        return FAREWELL_START + FAREWELL_LEAD + order * FAREWELL_STAGGER;
    }

    /** Portal mulai menutup setelah player baik terakhir masuk. */
    public static int winCloseStart(int goodCount) {
        return farewellAt(Math.max(0, goodCount - 1)) + ACTOR_TICKS + WIN_CLOSE_DELAY;
    }

    // ── Kalah (sabotase / penolakan) ──────────────────────────────────────────
    /**
     * Hanya pilar yang sudah menyala (misi sukses) yang bolanya meledak, satu per satu, masing-masing
     * disorot dari dekat selama {@link #EVIL_ORB_TICKS}. Tidak ada yang menyala = langsung sorotan kubu jahat.
     */
    public static final int EVIL_ORB_TICKS = 70;
    /** Dalam sorotan satu bola: kapan ia mulai meluap, dan kapan pecah. */
    public static final int EVIL_OVERLOAD_AT = 14;
    public static final int EVIL_BURST_AT = EVIL_OVERLOAD_AT + PillarBlock.OVERLOAD_BURST_TICK - PillarBlock.OVERLOAD_LIT_SKIP;
    /** Lama sorotan kubu jahat. */
    public static final int EVIL_GROUP_TICKS = 104;

    /** Tick ending kalah yang sebenarnya dimulai: langsung, atau setelah reka ulang Merlin tertembak. */
    public static int loseStart(int type) {
        return type == MERLIN ? MERLIN_INTRO_TICKS : 0;
    }

    /** Tick bola menyala urutan ke-{@code order} mulai meluap. */
    public static int evilOverload(int type, int order) {
        return loseStart(type) + order * EVIL_ORB_TICKS + EVIL_OVERLOAD_AT;
    }

    /** Tick bola menyala urutan ke-{@code order} meledak. */
    public static int evilBurst(int type, int order) {
        return loseStart(type) + order * EVIL_ORB_TICKS + EVIL_BURST_AT;
    }

    /** Sorotan kubu jahat dimulai setelah semua bola yang menyala meledak. */
    public static int evilGroupStart(int type, int litCount) {
        return loseStart(type) + litCount * EVIL_ORB_TICKS;
    }

    // ── Merlin terbunuh ───────────────────────────────────────────────────────
    // Reka ulang tembakannya, lalu berlanjut persis seperti ending kalah (bola pilar yang menyala
    // meledak, sorotan kubu jahat).
    /** Assassin mulai mengangkat busurnya. */
    public static final int MERLIN_DRAW_START = 10;
    /** Panah dilepas, lalu terbang (gerak lambat) ke Merlin. */
    public static final int MERLIN_RELEASE = 46;
    public static final int MERLIN_FLIGHT_TICKS = 24;
    /** Panah mengenai Merlin; ia tumbang. */
    public static final int MERLIN_HIT = MERLIN_RELEASE + MERLIN_FLIGHT_TICKS;
    public static final int MERLIN_INTRO_TICKS = MERLIN_HIT + 52;

    /** Lama layar menggelap / memutih di akhir cutscene. */
    public static final int FADE_TICKS = 16;

    /**
     * @param goodCount jumlah player kubu baik (ending menang)
     * @param litCount  jumlah pilar yang sedang menyala (ending kalah)
     */
    public static int total(int type, int goodCount, int litCount) {
        return switch (type) {
            case WIN -> winCloseStart(goodCount) + WIN_CLOSE_TICKS + FADE_TICKS;
            default -> evilGroupStart(type, litCount) + EVIL_GROUP_TICKS;
        };
    }
}

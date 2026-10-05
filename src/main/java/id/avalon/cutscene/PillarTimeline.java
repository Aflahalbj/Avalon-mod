package id.avalon.cutscene;

import id.avalon.block.PillarBlock;

/**
 * Linimasa cutscene pilar di akhir misi (tick), dipakai server (kapan pilar dinyalakan, kapan
 * cutscene berakhir) dan client (gerak kamera). Animasi pilarnya sendiri ada di {@link PillarBlock}.
 */
public final class PillarTimeline {

    private PillarTimeline() {}

    /** Jeda dari cutscene mulai sampai pilar dinyalakan: kamera sempat tiba & area sempat dimuat client. */
    public static final int LEAD_TICKS = 30;

    /** Lama cutscene misi sukses: tiang naik, bola terbentuk, lalu ditahan sebentar. */
    public static final int SUCCESS_TICKS = LEAD_TICKS + PillarBlock.RISE_TICKS + PillarBlock.IGNITE_TICKS + 60;

    /** Lama cutscene misi gagal: sampai bola pecah, lalu serpihannya ditahan sebentar. */
    public static final int FAIL_TICKS = LEAD_TICKS + PillarBlock.OVERLOAD_BURST_TICK + 45;

    /** Lama layar menggelap di akhir cutscene dan terang lagi sesudahnya. */
    public static final int FADE_TICKS = 12;
}

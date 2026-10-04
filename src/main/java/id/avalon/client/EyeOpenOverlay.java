package id.avalon.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.network.chat.Component;

/**
 * Animasi kelopak mata di layar:
 *  - buka mata: layar gelap yang membuka, berkedip dua kali lalu terbuka penuh
 *    (saat player baru "sadar" di kursinya setelah tersedot portal, dan setelah fase perkenalan);
 *  - tutup mata: kelopak menutup lalu layar tetap gelap sampai dibuka lagi
 *    (player yang tidak boleh melihat apa pun di fase perkenalan).
 *
 * Selama layar loading dimensi masih tampil, mata tetap tertutup; animasi buka baru berjalan setelahnya.
 */
public final class EyeOpenOverlay {

    private EyeOpenOverlay() {}

    private static final int BLACK = 0xFF000000;
    private static final int CLEAR = 0x00000000;

    /** Waktu (tick) → seberapa terbuka mata (0..1): buka sedikit, terpejam, buka setengah, terpejam, buka penuh. */
    private static final float[][] OPEN_KEYS = {
        {0f, 0f}, {12f, 0f}, {26f, 0.22f}, {34f, 0.04f}, {52f, 0.5f}, {59f, 0.18f}, {92f, 1f},
    };
    /** Menutup: berat, sempat tertahan sebentar, lalu terpejam. */
    private static final float[][] CLOSE_KEYS = {
        {0f, 1f}, {10f, 0.45f}, {16f, 0.5f}, {28f, 0f},
    };

    private enum Mode { NONE, OPENING, CLOSING }

    private static Mode mode = Mode.NONE;
    private static int age = 0;

    /** Buka mata (menunggu layar loading dimensi selesai kalau sedang tampil). */
    public static void start() {
        mode = Mode.OPENING;
        age = 0;
    }

    /** Buka mata sekarang juga. */
    public static void openNow() {
        start();
    }

    /** Tutup mata dan biarkan tertutup sampai {@link #start()}. */
    public static void close() {
        mode = Mode.CLOSING;
        age = 0;
    }

    public static void stop() {
        mode = Mode.NONE;
    }

    public static void tick() {
        if (mode == Mode.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        // Masih di layar loading dimensi: tetap terpejam
        if (mode == Mode.OPENING && (mc.level == null || mc.screen instanceof ReceivingLevelScreen)) {
            age = 0;
            return;
        }
        if (mc.isPaused()) return;
        age++;
        if (mode == Mode.OPENING && age > OPEN_KEYS[OPEN_KEYS.length - 1][0]) mode = Mode.NONE;
    }

    public static void render(GuiGraphics g, int width, int height, float partialTick) {
        if (mode == Mode.NONE) return;

        float t = age + partialTick;
        float open = keyframes(mode == Mode.OPENING ? OPEN_KEYS : CLOSE_KEYS, t);
        if (open <= 0f) {
            g.fill(0, 0, width, height, BLACK);
            if (mode == Mode.CLOSING) {
                // Tanda kecil bahwa layar gelap ini disengaja
                Component hint = Component.literal("Matamu terpejam...").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
                Minecraft mc = Minecraft.getInstance();
                g.drawString(mc.font, hint, (width - mc.font.width(hint)) / 2, height / 2 - 4, 0xFFFFFF, false);
            }
            return;
        }

        // Kelopak digambar per kolom: celahnya paling lebar di tengah layar, menyempit ke samping
        float centerY = height / 2f;
        float reach = height * 0.85f * open;
        int soft = Math.max(2, (int) (height * 0.10f));
        int columns = Math.max(1, width / 3);

        g.drawManaged(() -> {
            for (int i = 0; i < columns; i++) {
                int x0 = i * width / columns;
                int x1 = (i + 1) * width / columns;
                float nx = (x0 + x1) / (float) width - 1f;
                float half = reach * (1f - 0.38f * nx * nx);
                int top = (int) (centerY - half);
                int bottom = (int) (centerY + half);

                if (top - soft > 0) g.fill(x0, 0, x1, top - soft, BLACK);
                if (top > 0) g.fillGradient(x0, top - soft, x1, top, BLACK, CLEAR);
                if (bottom < height) g.fillGradient(x0, bottom, x1, bottom + soft, CLEAR, BLACK);
                if (bottom + soft < height) g.fill(x0, bottom + soft, x1, height, BLACK);
            }

            // Pandangan gelap saat mata baru setengah terbuka
            int dim = (int) ((1f - open) * (1f - open) * 170f);
            if (dim > 2) g.fill(0, 0, width, height, dim << 24);
        });
    }

    private static float keyframes(float[][] keys, float t) {
        if (t >= keys[keys.length - 1][0]) return keys[keys.length - 1][1];
        for (int i = 1; i < keys.length; i++) {
            if (t < keys[i][0]) {
                float k = (t - keys[i - 1][0]) / (keys[i][0] - keys[i - 1][0]);
                k = k * k * (3f - 2f * k);
                return keys[i - 1][1] + (keys[i][1] - keys[i - 1][1]) * k;
            }
        }
        return keys[keys.length - 1][1];
    }
}

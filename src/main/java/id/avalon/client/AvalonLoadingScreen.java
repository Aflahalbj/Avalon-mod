package id.avalon.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import id.avalon.AvalonMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

/**
 * Layar "loading terrain" khusus saat masuk ke dimensi Avalon:
 * nebula ungu yang bergerak pelan, lingkaran sihir berputar, dan butiran cahaya (tanpa tulisan).
 * Tetap turunan ReceivingLevelScreen supaya logika "terrain sudah siap" milik vanilla tetap dipakai.
 */
public class AvalonLoadingScreen extends ReceivingLevelScreen {

    private static final ResourceLocation BACKGROUND = tex("background");
    private static final ResourceLocation CIRCLE = tex("circle");
    private static final ResourceLocation MOTE = tex("mote");

    private static final int BG_W = 1280, BG_H = 720;

    /** Layar tampil minimal selama ini walau terrain sudah siap lebih cepat. */
    private static final long MIN_SHOW_MS = 3500L;
    private static final long FADE_IN_MS = 700L;
    private static final long FADE_OUT_MS = 800L;

    private static final int MOTE_COUNT = 70;

    private final long openedAt = System.currentTimeMillis();
    private long fadeOutAt = -1L;
    private boolean started = false;

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(AvalonMod.MOD_ID, "textures/gui/loading/" + name + ".png");
    }

    @Override
    protected void init() {
        super.init();
        if (started) return;
        started = true;

        // Filter linear supaya gambar tidak pecah saat diperbesar/diputar
        for (ResourceLocation t : new ResourceLocation[]{BACKGROUND, CIRCLE, MOTE}) {
            minecraft.getTextureManager().getTexture(t).setFilter(true, false);
        }
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PORTAL_TRAVEL, 0.7f, 0.35f));
    }

    // ── Tutup: tunggu durasi minimal, lalu memudar ────────────────────────────

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        if (fadeOutAt >= 0) {
            if (now - fadeOutAt >= FADE_OUT_MS) super.onClose();
            return;
        }
        if (now - openedAt < MIN_SHOW_MS) return;
        super.tick();
    }

    /** Vanilla memanggil ini saat terrain siap: mulai memudar dulu, tutup setelah selesai. */
    @Override
    public void onClose() {
        if (fadeOutAt < 0) fadeOutAt = System.currentTimeMillis();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        float t = (now - openedAt) / 1000f;

        // Alpha seluruh layar (memudar saat keluar) & alpha isi (muncul saat masuk)
        float screenAlpha = fadeOutAt < 0 ? 1f : 1f - Mth.clamp((now - fadeOutAt) / (float) FADE_OUT_MS, 0f, 1f);
        float intro = ease(Mth.clamp((now - openedAt) / (float) FADE_IN_MS, 0f, 1f));

        int cx = width / 2;
        int cy = height / 2;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        drawBackground(g, t, screenAlpha);

        // Aditif: cahaya saling menumpuk jadi lebih terang
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        drawCircles(g, cx, cy, t, screenAlpha * intro);
        drawMotes(g, t, screenAlpha * intro);
        RenderSystem.defaultBlendFunc();

        drawVignette(g, screenAlpha);

        g.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    /** Nebula menutupi seluruh layar, membesar & bergeser pelan. */
    private void drawBackground(GuiGraphics g, float t, float alpha) {
        float cover = Math.max(width / (float) BG_W, height / (float) BG_H);
        float zoom = cover * (1.10f + 0.035f * t);
        float driftX = Mth.sin(t * 0.21f) * width * 0.012f;
        float driftY = Mth.cos(t * 0.17f) * height * 0.012f;

        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(width / 2f + driftX, height / 2f + driftY, 0f);
        pose.scale(zoom, zoom, 1f);
        g.setColor(1f, 1f, 1f, alpha);
        g.blit(BACKGROUND, -BG_W / 2, -BG_H / 2, BG_W, BG_H, 0f, 0f, BG_W, BG_H, BG_W, BG_H);
        pose.popPose();
    }

    /** Dua lingkaran sihir berputar berlawanan arah di tengah layar. */
    private void drawCircles(GuiGraphics g, int cx, int cy, float t, float alpha) {
        float base = height * 0.42f;
        float pulse = 1f + 0.02f * Mth.sin(t * 1.6f);

        drawSpinning(g, cx, cy, base * 1.55f * pulse, t * -6f, 0.55f, 0.30f, 1.00f, 0.22f * alpha);
        drawSpinning(g, cx, cy, base * pulse, t * 11f, 0.80f, 0.55f, 1.00f, 0.50f * alpha);
    }

    private void drawSpinning(GuiGraphics g, int cx, int cy, float radius, float degrees,
                              float r, float gr, float b, float alpha) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0f);
        pose.mulPose(Axis.ZP.rotationDegrees(degrees));
        pose.scale(radius / 256f, radius / 256f, 1f);
        g.setColor(r, gr, b, alpha);
        g.blit(CIRCLE, -256, -256, 512, 512, 0f, 0f, 512, 512, 512, 512);
        pose.popPose();
    }

    /** Butiran cahaya yang melayang naik; posisi & kecepatannya diturunkan dari indeks (tanpa state). */
    private void drawMotes(GuiGraphics g, float t, float alpha) {
        PoseStack pose = g.pose();
        for (int i = 0; i < MOTE_COUNT; i++) {
            float seedA = frac(i * 0.61803f);
            float seedB = frac(i * 0.38197f + 0.25f);
            float seedC = frac(i * 0.75488f + 0.5f);

            float speed = 0.02f + 0.05f * seedB;
            float y = 1.05f - frac(seedC + t * speed) * 1.10f;
            float x = seedA + 0.025f * Mth.sin(t * (0.4f + seedB) + i);
            float size = (2.5f + 6f * seedC) * height / 360f;
            float twinkle = 0.45f + 0.55f * Mth.sin(t * (1.3f + 2.2f * seedA) + i * 1.7f);
            // Memudar di tepi atas & bawah
            float edge = Mth.clamp(Math.min(y, 1f - y) * 6f, 0f, 1f);

            boolean gold = i % 4 == 0;
            g.setColor(gold ? 1.0f : 0.78f, gold ? 0.85f : 0.55f, gold ? 0.55f : 1.0f, alpha * twinkle * edge * 0.9f);

            pose.pushPose();
            pose.translate(x * width, y * height, 0f);
            pose.scale(size / 32f, size / 32f, 1f);
            g.blit(MOTE, -16, -16, 32, 32, 0f, 0f, 32, 32, 32, 32);
            pose.popPose();
        }
    }

    /** Gelap di tepi atas & bawah supaya fokus ke tengah. */
    private void drawVignette(GuiGraphics g, float alpha) {
        g.setColor(1f, 1f, 1f, 1f);
        int a = (int) (200 * alpha);
        if (a <= 3) return;
        int dark = (a << 24) | 0x06000C;
        int clear = 0x0006000C;
        int band = (int) (height * 0.38f);
        g.fillGradient(0, 0, width, band, dark, clear);
        g.fillGradient(0, height - band, width, height, clear, dark);
        // fillGradient() mematikan blend setelah menggambar; nyalakan lagi untuk tekstur berikutnya
        RenderSystem.enableBlend();
    }

    private static float frac(float v) {
        return v - Mth.floor(v);
    }

    /** Ease-out kubik. */
    private static float ease(float v) {
        float inv = 1f - v;
        return 1f - inv * inv * inv;
    }
}

package id.avalon.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import id.avalon.network.AvalonNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fase perkenalan di sisi client: aura (merah / ungu) di sekitar player tertentu, dan pose
 * menunduk untuk semua yang tidak boleh terlihat tegak. Isinya berbeda di tiap client,
 * sesuai peran pemiliknya; client yang tidak boleh melihat apa pun hanya memejamkan mata.
 */
public final class RevealClient {

    private RevealClient() {}

    /** Seberapa dalam kepala menunduk (radian). */
    private static final float BOW_PITCH = 0.95f;

    /** Bentuk aura: titik di sepanjang tepi, dan jumlah lidah api yang naik. */
    private static final int OUTLINE = 48;
    private static final int LICKS = 7;

    private static boolean active = false;
    private static boolean eyesClosed = false;
    private static final Set<Integer> red = new HashSet<>();
    private static final Set<Integer> purple = new HashSet<>();
    private static final Set<Integer> upright = new HashSet<>();
    private static int age = 0;

    public static void apply(AvalonNetwork.Reveal msg) {
        if (!msg.active()) {
            stop(true);
            return;
        }
        boolean wasActive = active;
        active = true;
        red.clear();
        red.addAll(msg.red());
        purple.clear();
        purple.addAll(msg.purple());
        upright.clear();
        upright.addAll(msg.upright());
        // Pembaruan di tengah fase (ada yang keluar/masuk) tidak mengulang animasi
        if (!wasActive) age = 0;
        if (msg.eyesClosed() && !(wasActive && eyesClosed)) EyeOpenOverlay.close();
        eyesClosed = msg.eyesClosed();
    }

    /** Akhiri fase; {@code openEyes} = yang tadinya terpejam membuka mata lagi. */
    public static void stop(boolean openEyes) {
        if (active && eyesClosed && openEyes) EyeOpenOverlay.openNow();
        active = false;
        eyesClosed = false;
        red.clear();
        purple.clear();
        upright.clear();
    }

    public static void tick() {
        if (!active) return;
        if (Minecraft.getInstance().level == null) {
            stop(false);
            return;
        }
        if (!Minecraft.getInstance().isPaused()) age++;
    }

    // ── Pose menunduk ─────────────────────────────────────────────────────────

    /** Dipanggil dari PlayerModelMixin: kepala semua yang tidak boleh tegak dibuat menunduk ke depan. */
    public static void poseModel(PlayerModel<?> model, Entity entity) {
        if (!active || eyesClosed) return;
        Minecraft mc = Minecraft.getInstance();
        if (entity == mc.player || upright.contains(entity.getId())) return;

        // Menunduk pelan saat fase dimulai
        float k = PortalActorAnim.smooth(0f, 12f, age + mc.getFrameTime());
        model.head.xRot = Mth.lerp(k, model.head.xRot, BOW_PITCH);
        model.head.yRot = Mth.lerp(k, model.head.yRot, 0f);
        model.hat.copyFrom(model.head);
    }

    // ── Aura ──────────────────────────────────────────────────────────────────

    public static void render(RenderLevelStageEvent event) {
        if (!active || eyesClosed || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        float partial = event.getPartialTick();
        float t = age + partial;
        float fadeIn = PortalActorAnim.smooth(0f, 20f, t);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        for (Entity e : mc.level.entitiesForRendering()) {
            boolean isRed = red.contains(e.getId());
            if (!isRed && !purple.contains(e.getId())) continue;

            double x = Mth.lerp(partial, e.xOld, e.getX());
            double y = Mth.lerp(partial, e.yOld, e.getY());
            double z = Mth.lerp(partial, e.zOld, e.getZ());
            // Tiap orang punya irama api sendiri
            float seed = (e.getId() * 0.618f) % 1f * 40f;

            pose.pushPose();
            pose.translate(x - cam.x, y - cam.y, z - cam.z);
            // Selalu menghadap kamera (berputar di sumbu tegak saja)
            pose.mulPose(Axis.YP.rotation((float) Mth.atan2(cam.x - x, cam.z - z)));
            aura(pose.last().pose(), t + seed, fadeIn, isRed);
            pose.popPose();
        }

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /**
     * Aura satu orang dalam koordinat lokal (x ke samping, y ke atas dari kaki, menghadap kamera):
     * selubung api berbentuk tetesan terbalik dengan tepi terang yang bergolak, dua lapis
     * yang berkedip bergantian, plus lidah-lidah api yang naik dari bawah.
     */
    private static void aura(Matrix4f m, float t, float alpha, boolean isRed) {
        float r = isRed ? 1.0f : 0.72f;
        float g = isRed ? 0.18f : 0.30f;
        float b = isRed ? 0.12f : 1.0f;
        float rimR = isRed ? 1.0f : 0.92f;
        float rimG = isRed ? 0.55f : 0.70f;
        float rimB = isRed ? 0.35f : 1.0f;

        float flicker = 0.85f + 0.15f * Mth.sin(t * 0.9f) * Mth.sin(t * 2.3f);
        shell(m, t, 1.0f, 0.00f, alpha * flicker, r, g, b, rimR, rimG, rimB);
        shell(m, t * 1.35f + 7f, 0.88f, 0.05f, alpha * (1.7f - flicker) * 0.8f, r, g, b, rimR, rimG, rimB);
        licks(m, t, alpha, rimR, rimG, rimB);
    }

    /** Satu lapis selubung: isi tipis + tepi terang yang menyala ke luar. */
    private static void shell(Matrix4f m, float t, float scale, float z, float alpha,
                              float r, float g, float b, float rimR, float rimG, float rimB) {
        if (alpha <= 0.01f) return;
        float[] xs = new float[OUTLINE + 1];
        float[] ys = new float[OUTLINE + 1];
        for (int i = 0; i <= OUTLINE; i++) {
            outline(i / (float) OUTLINE, t, scale, xs, ys, i);
        }
        float cx = 0f, cy = 0.85f * scale;

        BufferBuilder buf = Tesselator.getInstance().getBuilder();

        // Isi: tipis di tengah supaya badan tetap terlihat, makin pekat ke tepi
        buf.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(m, cx, cy, z).color(r, g, b, 0.05f * alpha).endVertex();
        for (int i = 0; i <= OUTLINE; i++) {
            buf.vertex(m, Mth.lerp(0.9f, cx, xs[i]), Mth.lerp(0.9f, cy, ys[i]), z).color(r, g, b, 0.22f * alpha).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());

        // Tepi: dari dalam (redup) ke garis tepi (terang) lalu memudar ke luar
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < OUTLINE; i++) {
            band(buf, m, cx, cy, xs, ys, i, 0.9f, 1.0f, z, r, g, b, 0.22f * alpha, rimR, rimG, rimB, 0.75f * alpha);
            band(buf, m, cx, cy, xs, ys, i, 1.0f, 1.18f, z, rimR, rimG, rimB, 0.75f * alpha, r, g, b, 0f);
        }
        BufferUploader.drawWithShader(buf.end());
    }

    /** Pita antara dua jarak (pecahan dari pusat ke garis tepi) di segmen ke-{@code i}. */
    private static void band(BufferBuilder buf, Matrix4f m, float cx, float cy, float[] xs, float[] ys, int i,
                             float from, float to, float z,
                             float r0, float g0, float b0, float a0, float r1, float g1, float b1, float a1) {
        int j = i + 1;
        buf.vertex(m, Mth.lerp(from, cx, xs[i]), Mth.lerp(from, cy, ys[i]), z).color(r0, g0, b0, a0).endVertex();
        buf.vertex(m, Mth.lerp(from, cx, xs[j]), Mth.lerp(from, cy, ys[j]), z).color(r0, g0, b0, a0).endVertex();
        buf.vertex(m, Mth.lerp(to, cx, xs[j]), Mth.lerp(to, cy, ys[j]), z).color(r1, g1, b1, a1).endVertex();
        buf.vertex(m, Mth.lerp(to, cx, xs[i]), Mth.lerp(to, cy, ys[i]), z).color(r1, g1, b1, a1).endVertex();
    }

    /**
     * Garis tepi selubung di titik {@code u} (0..1 mengelilingi badan, mulai dari bawah):
     * melebar di sekitar badan, menyempit dan meruncing ke atas, dengan ujung-ujung api
     * yang bergerak naik di bagian atas.
     */
    private static void outline(float u, float t, float scale, float[] xs, float[] ys, int i) {
        float angle = -Mth.HALF_PI + u * Mth.TWO_PI;
        float sin = Mth.sin(angle);
        float cos = Mth.cos(angle);
        float up = Math.max(0f, sin);

        float radiusX = 0.85f * (1f - 0.45f * up * up);
        float radiusY = sin > 0f ? 1.75f : 0.95f;

        // Ujung api: makin tajam & tinggi di bagian atas, bergerak seperti api yang menjilat
        float spike = Mth.sin(angle * 7f - t * 0.55f) * 0.5f + 0.5f;
        spike = spike * spike * spike * 0.38f * up;
        float wobble = 0.05f * Mth.sin(angle * 5f + t * 0.8f) + 0.04f * Mth.sin(angle * 11f - t * 1.7f);
        float k = (1f + spike + wobble) * scale;

        xs[i] = cos * radiusX * k;
        ys[i] = 0.85f * scale + sin * radiusY * k;
    }

    /** Lidah api tipis yang naik dari bawah dan memudar di atas. */
    private static void licks(Matrix4f m, float t, float alpha, float r, float g, float b) {
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < LICKS; i++) {
            float phase = (t * (0.035f + 0.01f * i) + i * 0.37f) % 1f;
            float x = Mth.sin(i * 2.4f) * 0.6f;
            float bottom = 0.1f + phase * 2.2f;
            float height = 0.5f + 0.3f * Mth.sin(i * 1.7f);
            float half = 0.07f;
            float a = Mth.sin(phase * Mth.PI) * 0.6f * alpha;
            float z = 0.06f;
            // Mulai dari sisi kanan supaya diagonal pembagi segitiga melintang di bagian terang
            buf.vertex(m, x + half, bottom + height * 0.4f, z).color(r, g, b, a).endVertex();
            buf.vertex(m, x, bottom + height, z).color(r, g, b, 0f).endVertex();
            buf.vertex(m, x - half, bottom + height * 0.4f, z).color(r, g, b, a).endVertex();
            buf.vertex(m, x, bottom, z).color(r, g, b, 0f).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }
}

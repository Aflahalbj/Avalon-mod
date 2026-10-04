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
import id.avalon.block.PillarBlock;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Gambar pilar yang menyala: lingkaran sihir di atas block, tiang cahaya, dan bola di puncaknya
 * (kristal bersegi di dalam cincin-cincin berputar, dengan pendar & kilau bintang).
 * Semuanya cahaya aditif yang digambar langsung seperti {@link PortalRenderer}.
 */
final class PillarOrbRenderer {

    private PillarOrbRenderer() {}

    // Warna (r, g, b)
    private static final float[] AZURE = {0.28f, 0.68f, 1.0f};
    private static final float[] ICE = {0.78f, 0.93f, 1.0f};
    private static final float[] GOLD = {1.0f, 0.78f, 0.34f};
    private static final float[] WHITE = {1.0f, 1.0f, 1.0f};

    /** Lama tiang cahaya naik sampai puncak saat dinyalakan / turun lagi saat dimatikan (tick). */
    private static final float BEAM_RISE_TICKS = 16f;
    private static final float BEAM_FALL_TICKS = 8f;
    /** Lama bola membesar setelah tiang sampai / mengecil saat dimatikan (tick). */
    private static final float IGNITE_TICKS = 24f;
    private static final float FADE_TICKS = 14f;
    private static final float SHOCKWAVE_TICKS = 22f;

    private static final int RING_SEGMENTS = 64;
    /** Jumlah cahaya yang berlari di tiap cincin. */
    private static final int RING_COMETS = 2;
    private static final int SHARDS = 5;
    private static final int BEAM_PULSES = 3;

    private static final class Orb {
        boolean lit;
        boolean seen;
        /** Tiang cahaya: 0 = belum ada, 1 = sudah sampai puncak. */
        float beam;
        /** Bola: 0 = mati, 1 = menyala penuh. Baru naik setelah tiang sampai puncak. */
        float power;
        /** Tick sejak bola mulai muncul, untuk gelombang kejut. */
        float litFor;
    }

    private static final Map<BlockPos, Orb> ORBS = new HashMap<>();
    private static double clock;

    /** Dipanggil {@link PillarRenderer} tiap frame untuk pilar yang sedang terlihat. */
    static void track(BlockPos pos, boolean lit) {
        Orb orb = ORBS.get(pos);
        if (orb == null) {
            if (ORBS.isEmpty()) PortalRenderer.prepareTextures();
            orb = new Orb();
            // Pilar yang sudah menyala saat pertama terlihat langsung tampil penuh
            orb.beam = lit ? 1f : 0f;
            orb.power = lit ? 1f : 0f;
            orb.litFor = lit ? SHOCKWAVE_TICKS : 0f;
            ORBS.put(pos.immutable(), orb);
        }
        orb.lit = lit;
        orb.seen = true;
    }

    static void clear() {
        ORBS.clear();
    }

    static void render(RenderLevelStageEvent event) {
        // Setelah awan (bukan AFTER_PARTICLES): cahayanya tidak menulis depth, jadi awan yang
        // digambar belakangan akan menimpanya walau posisinya di belakang bola.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || ORBS.isEmpty()) return;

        float delta = Minecraft.getInstance().getDeltaFrameTime();
        clock += delta;

        // Di tahap ini vanilla sudah memasang rotasi kamera di model-view (untuk awan & hujan),
        // sedangkan pose dari event juga memuatnya. Kosongkan dulu supaya tidak terputar dua kali.
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        // Semuanya cahaya: aditif, saling menumpuk jadi makin terang
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);

        for (Iterator<Map.Entry<BlockPos, Orb>> it = ORBS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Orb> entry = it.next();
            Orb orb = entry.getValue();
            // Tidak dilaporkan frame ini: pilarnya hilang atau di luar layar
            if (!orb.seen) {
                it.remove();
                continue;
            }
            orb.seen = false;

            if (orb.lit) {
                // Tiang naik dulu; bola baru muncul setelah tiangnya sampai puncak
                if (orb.beam < 1f) {
                    orb.beam = Math.min(1f, orb.beam + delta / BEAM_RISE_TICKS);
                } else {
                    orb.power = Math.min(1f, orb.power + delta / IGNITE_TICKS);
                    orb.litFor += delta;
                }
            } else {
                // Kebalikannya: bola padam dulu, baru tiangnya turun
                if (orb.power > 0f) {
                    orb.power = Math.max(0f, orb.power - delta / FADE_TICKS);
                } else {
                    orb.beam = Math.max(0f, orb.beam - delta / BEAM_FALL_TICKS);
                }
                orb.litFor = 0f;
            }
            if (orb.beam > 0f) draw(event.getPoseStack(), event.getCamera(), entry.getKey(), orb);
        }

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
    }

    private static void draw(PoseStack pose, Camera camera, BlockPos pos, Orb orb) {
        // Tiap pilar punya fase sendiri supaya tidak bergerak serempak
        double t = clock + (pos.hashCode() & 1023);
        float power = orb.power;
        float grow = Math.max(0.001f, power >= 1f ? 1f : PortalActorAnim.backOut(power));

        Vec3 cam = camera.getPosition();
        // Titik asal: tengah permukaan atas block
        double x = pos.getX() + 0.5 - cam.x;
        double y = pos.getY() + 1.0 - cam.y;
        double z = pos.getZ() + 0.5 - cam.z;
        float orbY = PillarBlock.ORB_HEIGHT - 0.5f + 0.12f * wave(t, 0.08);

        pose.pushPose();
        pose.translate(x, y, z);

        // Lingkaran di block & tiang mengikuti tiang, bukan bola; tiangnya cepat terang lalu memanjang
        float rise = PortalActorAnim.smooth(0f, 1f, orb.beam);
        float beamAlpha = Math.min(1f, orb.beam * 4f);
        base(pose, t, Math.min(1f, orb.beam * 2f), beamAlpha);
        beam(pose, camera, t, (float) x, (float) z, orbY * rise, beamAlpha);
        if (power <= 0f) {
            pose.popPose();
            return;
        }

        pose.translate(0f, orbY, 0f);
        pose.scale(grow, grow, grow);

        halo(pose, camera, t, power, orb.litFor / SHOCKWAVE_TICKS);
        rings(pose, t, power);
        crystal(pose, t, power);

        pose.popPose();
    }

    // ── Bagian ────────────────────────────────────────────────────────────────

    /** Lingkaran sihir yang berputar di atas block. */
    private static void base(PoseStack pose, double t, float grow, float power) {
        pose.pushPose();
        pose.translate(0f, 0.03f, 0f);
        pose.mulPose(Axis.XP.rotationDegrees(90f));
        Matrix4f m = pose.last().pose();
        glow(m, PortalRenderer.MOTE, 1.9f * grow, 0f, 0f, AZURE, 0.40f * power);
        glow(m, PortalRenderer.CIRCLE, 1.5f * grow, deg(t, 1.1), -0.01f, AZURE, 0.85f * power);
        glow(m, PortalRenderer.CIRCLE, 0.8f * grow, deg(t, -2.6), -0.02f, GOLD, 0.75f * power);
        pose.popPose();
    }

    /** Tiang cahaya dari block ke bola, selalu menghadap kamera, dengan denyut yang naik. */
    private static void beam(PoseStack pose, Camera camera, double t, float x, float z, float height, float power) {
        // Arah mendatar yang tegak lurus dengan arah pandang
        float length = Mth.sqrt(x * x + z * z);
        float sx = length < 0.001f ? 1f : -z / length;
        float sz = length < 0.001f ? 0f : x / length;
        float flicker = 0.85f + 0.15f * wave(t, 0.9) * wave(t, 0.37);

        Matrix4f m = pose.last().pose();
        strip(m, sx, sz, 0.60f, height, AZURE, 0.28f * power * flicker);
        strip(m, sx, sz, 0.22f, height, ICE, 0.55f * power);
        strip(m, sx, sz, 0.07f, height, WHITE, 0.90f * power);

        for (int i = 0; i < BEAM_PULSES; i++) {
            float rise = (float) ((t / 46.0 + i / (double) BEAM_PULSES) % 1.0);
            float fade = Mth.sin(rise * Mth.PI);
            pose.pushPose();
            pose.translate(0f, rise * height, 0f);
            pose.mulPose(camera.rotation());
            glow(pose.last().pose(), PortalRenderer.MOTE, 0.30f + 0.25f * fade, 0f, 0f, ICE, 0.9f * fade * power);
            pose.popPose();
        }
    }

    /** Pendar, kilau bintang, dan gelombang kejut saat dinyalakan. Semuanya menghadap kamera. */
    private static void halo(PoseStack pose, Camera camera, double t, float power, float shock) {
        float breathe = 1f + 0.06f * wave(t, 0.11);
        float twinkle = 0.75f + 0.25f * wave(t, 0.23);

        pose.pushPose();
        pose.mulPose(camera.rotation());
        Matrix4f m = pose.last().pose();
        glow(m, PortalRenderer.MOTE, 3.6f * breathe, 0f, 0f, AZURE, 0.50f * power);
        glow(m, PortalRenderer.MOTE, 1.7f * breathe, 0f, 0.01f, ICE, 0.80f * power);
        glow(m, PortalRenderer.MOTE, 0.8f, 0f, 0.02f, WHITE, power);
        shockwave(m, shock);

        // Kilau empat arah yang berputar pelan, ditambah empat yang lebih pendek di sela-selanya
        pose.mulPose(Axis.ZP.rotationDegrees(deg(t, 0.35)));
        flare(pose.last().pose(), 0.09f, 3.2f * twinkle, ICE, 0.90f * power);
        pose.mulPose(Axis.ZP.rotationDegrees(45f));
        flare(pose.last().pose(), 0.06f, 1.7f * (1.75f - twinkle), GOLD, 0.70f * power);
        pose.popPose();
    }

    /** Cincin-cincin yang berputar mengelilingi kristal, plus piringan sihir yang hampir mendatar. */
    private static void rings(PoseStack pose, double t, float power) {
        ring(pose, deg(t, 1.9), 68f, deg(t, 5.0), 0.98f, 0.045f, GOLD, 0.95f * power);
        ring(pose, deg(t, -1.3) + 120f, -52f, deg(t, -6.5), 1.20f, 0.040f, ICE, 0.85f * power);
        ring(pose, deg(t, 0.8) + 240f, 24f, deg(t, 4.0), 1.42f, 0.035f, AZURE, 0.90f * power);

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(deg(t, 0.6)));
        pose.mulPose(Axis.XP.rotationDegrees(80f));
        glow(pose.last().pose(), PortalRenderer.CIRCLE, 1.75f, deg(t, -1.6), 0f, AZURE, 0.55f * power);
        pose.popPose();
    }

    /** Kristal bersegi dengan inti yang berputar berlawanan, dan serpihan kecil yang mengorbit. */
    private static void crystal(PoseStack pose, double t, float power) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(deg(t, 2.2)));
        gem(pose.last().pose(), 6, 0.44f, 0.80f, ICE, AZURE, 0.85f * power, t);
        pose.popPose();

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(deg(t, -5.5)));
        pose.mulPose(Axis.XP.rotationDegrees(deg(t, 3.1)));
        gem(pose.last().pose(), 4, 0.21f, 0.36f, WHITE, GOLD, power, t);
        pose.popPose();

        for (int i = 0; i < SHARDS; i++) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(deg(t, 1.4) + i * 360f / SHARDS));
            pose.translate(2.0f + 0.15f * wave(t + i * 40.0, 0.05), 0.40f * wave(t + i * 23.0, 0.07), 0f);
            pose.mulPose(Axis.ZP.rotationDegrees(22f));
            pose.mulPose(Axis.YP.rotationDegrees(deg(t, 6.0) + i * 50f));
            gem(pose.last().pose(), 4, 0.10f, 0.26f, ICE, AZURE, 0.80f * power, t + i * 9.0);
            pose.popPose();
        }
    }

    private static void ring(PoseStack pose, float precession, float tilt, float spin,
                             float radius, float width, float[] rgb, float alpha) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(precession));
        pose.mulPose(Axis.XP.rotationDegrees(tilt));
        pose.mulPose(Axis.ZP.rotationDegrees(spin));
        band(pose.last().pose(), radius, width, rgb, alpha);
        pose.popPose();
    }

    // ── Bentuk ────────────────────────────────────────────────────────────────

    private static void glow(Matrix4f m, ResourceLocation texture, float half, float degrees, float z,
                             float[] rgb, float alpha) {
        PortalRenderer.quad(m, texture, half, degrees, z, rgb[0], rgb[1], rgb[2], alpha);
    }

    /** Pita tegak setinggi {@code height}: terang di tengah, memudar ke kedua sisi. */
    private static void strip(Matrix4f m, float sx, float sz, float half, float height, float[] rgb, float alpha) {
        if (alpha <= 0.003f || height <= 0f) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int side = -1; side <= 1; side += 2) {
            float ex = sx * half * side;
            float ez = sz * half * side;
            buf.vertex(m, ex, 0f, ez).color(rgb[0], rgb[1], rgb[2], 0f).endVertex();
            buf.vertex(m, 0f, 0f, 0f).color(rgb[0], rgb[1], rgb[2], alpha).endVertex();
            buf.vertex(m, 0f, height, 0f).color(rgb[0], rgb[1], rgb[2], alpha).endVertex();
            buf.vertex(m, ex, height, ez).color(rgb[0], rgb[1], rgb[2], 0f).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }

    /** Dua belah ketupat tipis bersilang: kilau bintang empat arah. */
    private static void flare(Matrix4f m, float width, float length, float[] rgb, float alpha) {
        if (alpha <= 0.003f) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 4; i++) {
            // Ujung kilau dan dua sisi pangkalnya, diputar 90° tiap arah
            float dx = i == 0 ? 1f : i == 2 ? -1f : 0f;
            float dy = i == 1 ? 1f : i == 3 ? -1f : 0f;
            buf.vertex(m, 0f, 0f, 0.03f).color(rgb[0], rgb[1], rgb[2], alpha).endVertex();
            buf.vertex(m, -dy * width, dx * width, 0.03f).color(rgb[0], rgb[1], rgb[2], 0f).endVertex();
            buf.vertex(m, dx * length, dy * length, 0.03f).color(rgb[0], rgb[1], rgb[2], 0f).endVertex();
            buf.vertex(m, 0f, 0f, 0.03f).color(rgb[0], rgb[1], rgb[2], alpha).endVertex();
            buf.vertex(m, dx * length, dy * length, 0.03f).color(rgb[0], rgb[1], rgb[2], 0f).endVertex();
            buf.vertex(m, dy * width, -dx * width, 0.03f).color(rgb[0], rgb[1], rgb[2], 0f).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }

    /** Cincin tipis yang membesar & memudar; {@code progress} di luar 0..1 = tidak digambar. */
    private static void shockwave(Matrix4f m, float progress) {
        if (progress <= 0f || progress >= 1f) return;
        float outer = Mth.lerp(progress, 0.6f, 6.5f);
        float inner = outer - 1.4f * (1f - progress);
        float alpha = 0.85f * (1f - progress);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < RING_SEGMENTS; i++) {
            float a0 = i * Mth.TWO_PI / RING_SEGMENTS;
            float a1 = (i + 1) * Mth.TWO_PI / RING_SEGMENTS;
            buf.vertex(m, Mth.cos(a0) * inner, Mth.sin(a0) * inner, 0.04f).color(AZURE[0], AZURE[1], AZURE[2], 0f).endVertex();
            buf.vertex(m, Mth.cos(a1) * inner, Mth.sin(a1) * inner, 0.04f).color(AZURE[0], AZURE[1], AZURE[2], 0f).endVertex();
            buf.vertex(m, Mth.cos(a1) * outer, Mth.sin(a1) * outer, 0.04f).color(ICE[0], ICE[1], ICE[2], alpha).endVertex();
            buf.vertex(m, Mth.cos(a0) * outer, Mth.sin(a0) * outer, 0.04f).color(ICE[0], ICE[1], ICE[2], alpha).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }

    /**
     * Cincin di bidang XY: pita datar + pita tegak supaya tetap terlihat dari samping,
     * dengan cahaya yang berlari di sepanjangnya (terang di kepala, memudar ke ekor).
     */
    private static void band(Matrix4f m, float radius, float width, float[] rgb, float alpha) {
        if (alpha <= 0.003f) return;
        float half = width / 2f;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < RING_SEGMENTS; i++) {
            float a0 = i * Mth.TWO_PI / RING_SEGMENTS;
            float a1 = (i + 1) * Mth.TWO_PI / RING_SEGMENTS;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0);
            float c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            float k0 = comet(i);
            float k1 = comet(i + 1);

            bandVertex(buf, m, c0 * (radius - half), s0 * (radius - half), 0f, rgb, k0, alpha);
            bandVertex(buf, m, c1 * (radius - half), s1 * (radius - half), 0f, rgb, k1, alpha);
            bandVertex(buf, m, c1 * (radius + half), s1 * (radius + half), 0f, rgb, k1, alpha);
            bandVertex(buf, m, c0 * (radius + half), s0 * (radius + half), 0f, rgb, k0, alpha);

            bandVertex(buf, m, c0 * radius, s0 * radius, -half, rgb, k0, alpha);
            bandVertex(buf, m, c1 * radius, s1 * radius, -half, rgb, k1, alpha);
            bandVertex(buf, m, c1 * radius, s1 * radius, half, rgb, k1, alpha);
            bandVertex(buf, m, c0 * radius, s0 * radius, half, rgb, k0, alpha);
        }
        BufferUploader.drawWithShader(buf.end());
    }

    /** Terang cahaya berlari di titik ke-{@code i} cincin: 0 di ekor → 1 di kepala. */
    private static float comet(int i) {
        float k = (i * RING_COMETS / (float) RING_SEGMENTS) % 1f;
        k *= k;
        return k * k;
    }

    private static void bandVertex(BufferBuilder buf, Matrix4f m, float x, float y, float z,
                                   float[] rgb, float k, float alpha) {
        // Kepala cahaya memutih
        buf.vertex(m, x, y, z)
                .color(Mth.lerp(k, rgb[0], 1f), Mth.lerp(k, rgb[1], 1f), Mth.lerp(k, rgb[2], 1f),
                        alpha * (0.25f + 0.75f * k))
                .endVertex();
    }

    /**
     * Kristal dua limas (puncak atas & bawah, {@code sides} sudut di tengah).
     * Rusuknya terang dan tengah tiap sisinya redup, jadi terlihat seperti kaca bersegi;
     * tiap sisi berkilau bergantian.
     */
    private static void gem(Matrix4f m, int sides, float radius, float half,
                            float[] edge, float[] face, float alpha, double t) {
        if (alpha <= 0.003f) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < sides; i++) {
            float a0 = i * Mth.TWO_PI / sides;
            float a1 = (i + 1) * Mth.TWO_PI / sides;
            float x0 = Mth.cos(a0) * radius, z0 = Mth.sin(a0) * radius;
            float x1 = Mth.cos(a1) * radius, z1 = Mth.sin(a1) * radius;
            for (int side = -1; side <= 1; side += 2) {
                float tip = side * half;
                float glint = 0.5f + 0.5f * (float) Math.sin(t * 0.12 + i * 2.1 + side);
                float faceAlpha = alpha * (0.10f + 0.50f * glint * glint);
                float cx = (x0 + x1) / 3f, cy = tip / 3f, cz = (z0 + z1) / 3f;

                // Tiga segitiga dari titik tengah sisi ke tiap rusuknya
                facet(buf, m, cx, cy, cz, face, faceAlpha, 0f, tip, 0f, x0, 0f, z0, edge, alpha);
                facet(buf, m, cx, cy, cz, face, faceAlpha, x0, 0f, z0, x1, 0f, z1, edge, alpha);
                facet(buf, m, cx, cy, cz, face, faceAlpha, x1, 0f, z1, 0f, tip, 0f, edge, alpha);
            }
        }
        BufferUploader.drawWithShader(buf.end());
    }

    private static void facet(BufferBuilder buf, Matrix4f m,
                              float cx, float cy, float cz, float[] face, float faceAlpha,
                              float ax, float ay, float az, float bx, float by, float bz,
                              float[] edge, float edgeAlpha) {
        buf.vertex(m, cx, cy, cz).color(face[0], face[1], face[2], faceAlpha).endVertex();
        buf.vertex(m, ax, ay, az).color(edge[0], edge[1], edge[2], edgeAlpha).endVertex();
        buf.vertex(m, bx, by, bz).color(edge[0], edge[1], edge[2], edgeAlpha).endVertex();
    }

    // ── Waktu ─────────────────────────────────────────────────────────────────

    /** Sudut putaran (derajat) dengan kecepatan {@code perTick}; dihitung di double supaya tetap halus. */
    private static float deg(double t, double perTick) {
        return (float) ((t * perTick) % 360.0);
    }

    private static float wave(double t, double speed) {
        return (float) Math.sin(t * speed);
    }
}

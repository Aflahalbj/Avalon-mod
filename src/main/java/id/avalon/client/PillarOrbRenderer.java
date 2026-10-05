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
import id.avalon.block.PillarBlockEntity;
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
import java.util.Map;

/**
 * Gambar pilar yang menyala: lingkaran sihir di atas block, tiang cahaya, dan bola di puncaknya
 * (kristal bersegi di dalam cincin-cincin berputar, dengan pendar & kilau bintang).
 * Juga animasi meluap: tiang naik seperti biasa, lalu bolanya memerah, tidak stabil, dan pecah.
 * Semuanya cahaya aditif yang digambar langsung seperti {@link PortalRenderer}.
 */
final class PillarOrbRenderer {

    private PillarOrbRenderer() {}

    /** Tiga warna satu pilar: warna utama, versi terangnya, dan aksen. */
    private record Palette(float[] main, float[] light, float[] accent) {
        static Palette mix(Palette from, Palette to, float k) {
            if (k <= 0f) return from;
            if (k >= 1f) return to;
            return new Palette(lerp(from.main, to.main, k), lerp(from.light, to.light, k), lerp(from.accent, to.accent, k));
        }

        private static float[] lerp(float[] a, float[] b, float k) {
            return new float[]{Mth.lerp(k, a[0], b[0]), Mth.lerp(k, a[1], b[1]), Mth.lerp(k, a[2], b[2])};
        }
    }

    private static final float[] WHITE = {1.0f, 1.0f, 1.0f};
    /** Biru es + emas. */
    private static final Palette NORMAL = new Palette(
            new float[]{0.28f, 0.68f, 1.0f}, new float[]{0.78f, 0.93f, 1.0f}, new float[]{1.0f, 0.78f, 0.34f});
    /** Merah membara. */
    private static final Palette OVERLOAD = new Palette(
            new float[]{1.0f, 0.20f, 0.14f}, new float[]{1.0f, 0.72f, 0.58f}, new float[]{1.0f, 0.48f, 0.10f});

    private static final float SHOCKWAVE_TICKS = 26f;
    /** Lama bola yang meluap terbentuk, memerah, dan menciut sebelum pecah (tick). */
    private static final float OVERLOAD_FORM_TICKS = 18f;
    private static final float OVERLOAD_TINT_TICKS = 15f;
    private static final float IMPLODE_TICKS = 10f;

    private static final int RING_SEGMENTS = 64;
    /** Jumlah cahaya yang berlari di tiap cincin. */
    private static final int RING_COMETS = 2;
    private static final int SHARDS = 5;
    private static final int BURST_SHARDS = 16;
    private static final int BEAM_PULSES = 3;

    /** Keadaan satu pilar di frame ini (dari block entity-nya). */
    private record Frame(float beam, float power, float orbAge, float overload) {}

    private static final Map<BlockPos, Frame> FRAMES = new HashMap<>();
    private static boolean texturesReady;
    private static double clock;

    /** Dipanggil {@link PillarRenderer} tiap frame untuk pilar yang sedang terlihat. */
    static void track(PillarBlockEntity pillar, float partialTick) {
        float beam = pillar.beam(partialTick);
        float overload = pillar.overloadAge(partialTick);
        if (beam <= 0f && overload < 0f) return;
        FRAMES.put(pillar.getBlockPos().immutable(),
                new Frame(beam, pillar.power(partialTick), pillar.orbAge(partialTick), overload));
    }

    static void clear() {
        FRAMES.clear();
        texturesReady = false;
    }

    static void render(RenderLevelStageEvent event) {
        // Setelah awan (bukan AFTER_PARTICLES): cahayanya tidak menulis depth, jadi awan yang
        // digambar belakangan akan menimpanya walau posisinya di belakang bola.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;

        clock += Minecraft.getInstance().getDeltaFrameTime();
        if (FRAMES.isEmpty()) return;
        if (!texturesReady) {
            PortalRenderer.prepareTextures();
            texturesReady = true;
        }

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

        for (Map.Entry<BlockPos, Frame> entry : FRAMES.entrySet()) {
            draw(event.getPoseStack(), event.getCamera(), entry.getKey(), entry.getValue());
        }
        // Pilar yang tidak dilaporkan lagi frame berikutnya (hilang / di luar layar) tidak digambar
        FRAMES.clear();

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        modelView.popPose();
        RenderSystem.applyModelViewMatrix();
    }

    private static void draw(PoseStack pose, Camera camera, BlockPos pos, Frame frame) {
        // Tiap pilar punya fase sendiri supaya tidak bergerak serempak
        double t = clock + (pos.hashCode() & 1023);

        Vec3 cam = camera.getPosition();
        // Titik asal: tengah permukaan atas block
        double x = pos.getX() + 0.5 - cam.x;
        double y = pos.getY() + 1.0 - cam.y;
        double z = pos.getZ() + 0.5 - cam.z;
        float orbY = PillarBlock.ORB_HEIGHT - 0.5f + 0.12f * wave(t, 0.08);

        pose.pushPose();
        pose.translate(x, y, z);
        if (frame.overload >= 0f) {
            overload(pose, camera, t, (float) x, (float) z, orbY, frame.overload);
        } else {
            lit(pose, camera, t, (float) x, (float) z, orbY, frame);
        }
        pose.popPose();
    }

    // ── Menyala ───────────────────────────────────────────────────────────────

    private static void lit(PoseStack pose, Camera camera, double t, float x, float z, float orbY, Frame frame) {
        // Lingkaran di block & tiang muncul lebih dulu; tiangnya cepat terang lalu memanjang pelan
        float height = orbY * PortalActorAnim.smooth(0f, 1f, frame.beam);
        float beamAlpha = Math.min(1f, frame.beam * 6f);
        base(pose, t, Math.min(1f, frame.beam * 4f), beamAlpha, NORMAL);
        beam(pose, camera, t, x, z, height, 1f, beamAlpha, NORMAL);
        if (frame.beam < 1f) head(pose, camera, t, height, beamAlpha, NORMAL);

        float power = frame.power;
        if (power <= 0f) return;

        pose.translate(0f, orbY, 0f);
        arrival(pose, camera, frame.orbAge, NORMAL);
        halo(pose, camera, t, stage(power, 0f, 0.4f), stage(power, 0.3f, 0.8f), NORMAL);
        rings(pose, t, power, 0f, NORMAL);
        crystal(pose, t, power, 1f, NORMAL);
    }

    // ── Meluap ────────────────────────────────────────────────────────────────

    private static void overload(PoseStack pose, Camera camera, double t, float x, float z, float orbY, float age) {
        if (age >= PillarBlock.OVERLOAD_TICKS) return;
        float burst = age - PillarBlock.OVERLOAD_BURST_TICK;

        if (burst >= 0f) {
            // Sudah pecah: tiang & lingkarannya padam, sisanya serpihan dan gelombang kejut
            float dying = Math.max(0f, 1f - burst / 14f) * (0.6f + 0.4f * wave(age, 2.7));
            base(pose, t, 1f, dying, OVERLOAD);
            beam(pose, camera, t, x, z, orbY, 1.6f, dying, OVERLOAD);
            pose.translate(0f, orbY, 0f);
            burst(pose, camera, t, burst);
            return;
        }

        // Tiang naik persis seperti saat menyala, jadi sampai di puncak hasilnya belum ketahuan
        float rise = Math.min(1f, age / PillarBlock.RISE_TICKS);
        float unstableFor = age - PillarBlock.RISE_TICKS;
        float unstable = Mth.clamp(unstableFor / (PillarBlock.OVERLOAD_BURST_TICK - PillarBlock.RISE_TICKS), 0f, 1f);
        Palette palette = Palette.mix(NORMAL, OVERLOAD, unstableFor / OVERLOAD_TINT_TICKS);
        float flicker = unstableFor < 0f ? 1f : 1f - 0.4f * unstable * (0.5f + 0.5f * wave(age, 3.1) * wave(age, 1.7));

        float height = orbY * PortalActorAnim.smooth(0f, 1f, rise);
        float beamAlpha = Math.min(1f, rise * 6f) * flicker;
        base(pose, t + unstable * unstable * 40.0, Math.min(1f, rise * 4f), beamAlpha, palette);
        beam(pose, camera, t, x, z, height, 1f + 0.6f * unstable, beamAlpha, palette);
        if (rise < 1f) head(pose, camera, t, height, beamAlpha, palette);
        if (unstableFor <= 0f) return;

        // Bola terbentuk cepat, bergetar makin liar, lalu menciut sesaat sebelum pecah
        float power = Math.min(1f, unstableFor / OVERLOAD_FORM_TICKS);
        float implode = PortalActorAnim.smooth(PillarBlock.OVERLOAD_BURST_TICK - IMPLODE_TICKS,
                PillarBlock.OVERLOAD_BURST_TICK, age);
        float shake = 0.09f * unstable;
        float size = (1f + 0.12f * unstable * wave(age, 2.3)) * (1f - 0.85f * implode);

        pose.translate(shake * wave(age, 2.9), orbY + shake * wave(age + 1.0, 3.7), shake * wave(age + 2.0, 4.3));
        arrival(pose, camera, unstableFor, palette);
        // Pendarnya justru memutih & membesar saat bolanya menciut
        halo(pose, camera, t, Math.min(1f, power * flicker + implode), power * flicker, palette);
        pose.scale(size, size, size);
        // Cincin berputar makin cepat
        rings(pose, t, power, unstable * unstable * 1400f, palette);
        crystal(pose, t, power, flicker, palette);
    }

    /** Bola pecah: kilatan, gelombang kejut, dan serpihan kristal yang terlempar ke segala arah. */
    private static void burst(PoseStack pose, Camera camera, double t, float age) {
        pose.pushPose();
        pose.mulPose(camera.rotation());
        Matrix4f m = pose.last().pose();
        glow(m, PortalRenderer.MOTE, 2.5f + age * 0.9f, 0f, 0f, WHITE, Math.max(0f, 1f - age / 9f));
        glow(m, PortalRenderer.MOTE, 4.5f + age * 0.4f, 0f, 0.01f, OVERLOAD.accent, 0.8f * Math.max(0f, 1f - age / 20f));
        glow(m, PortalRenderer.MOTE, 1.4f, 0f, 0.02f, OVERLOAD.main, 0.5f * Math.max(0f, 1f - age / 38f));
        for (int i = 0; i < 3; i++) {
            shockwave(m, (age - i * 5f) / 24f, 0.6f, 9f + 2f * i, 1.6f, OVERLOAD.main, OVERLOAD.light, 0.9f);
        }
        float spikes = Math.max(0f, 1f - age / 16f);
        pose.mulPose(Axis.ZP.rotationDegrees(deg(t, 0.35)));
        flare(pose.last().pose(), 0.14f, 6.5f * spikes, OVERLOAD.light, spikes);
        pose.mulPose(Axis.ZP.rotationDegrees(45f));
        flare(pose.last().pose(), 0.10f, 4.0f * spikes, OVERLOAD.accent, spikes);
        pose.popPose();

        // Gelombang kejut mendatar
        pose.pushPose();
        pose.mulPose(Axis.XP.rotationDegrees(90f));
        shockwave(pose.last().pose(), age / 30f, 0.8f, 13f, 2.2f, OVERLOAD.main, OVERLOAD.light, 0.8f);
        pose.popPose();

        // Serpihan: melesat lalu melambat, tersebar merata di permukaan bola
        float fade = Math.max(0f, 1f - age / 36f);
        float distance = 9.5f * (1f - (float) Math.exp(-age / 9.0));
        for (int i = 0; i < BURST_SHARDS; i++) {
            float up = 1f - 2f * (i + 0.5f) / BURST_SHARDS;
            float flat = Mth.sqrt(1f - up * up);
            float around = i * 2.39996f;
            pose.pushPose();
            pose.translate(Mth.cos(around) * flat * distance, up * distance - 0.02f * age * age * 0.1f, Mth.sin(around) * flat * distance);
            pose.mulPose(Axis.YP.rotationDegrees(deg(t, 9.0) + i * 47f));
            pose.mulPose(Axis.XP.rotationDegrees(deg(t, 7.0) + i * 31f));
            gem(pose.last().pose(), 4, 0.13f, 0.32f, OVERLOAD.light, OVERLOAD.main, fade, t + i * 9.0);
            pose.popPose();
        }
    }

    // ── Bagian ────────────────────────────────────────────────────────────────

    /** Lingkaran sihir yang berputar di atas block. */
    private static void base(PoseStack pose, double t, float grow, float alpha, Palette palette) {
        pose.pushPose();
        pose.translate(0f, 0.03f, 0f);
        pose.mulPose(Axis.XP.rotationDegrees(90f));
        Matrix4f m = pose.last().pose();
        glow(m, PortalRenderer.MOTE, 1.9f * grow, 0f, 0f, palette.main, 0.40f * alpha);
        glow(m, PortalRenderer.CIRCLE, 1.5f * grow, deg(t, 1.1), -0.01f, palette.main, 0.85f * alpha);
        glow(m, PortalRenderer.CIRCLE, 0.8f * grow, deg(t, -2.6), -0.02f, palette.accent, 0.75f * alpha);
        pose.popPose();
    }

    /** Tiang cahaya dari block ke bola, selalu menghadap kamera, dengan denyut yang naik. */
    private static void beam(PoseStack pose, Camera camera, double t, float x, float z, float height,
                             float width, float alpha, Palette palette) {
        // Arah mendatar yang tegak lurus dengan arah pandang
        float length = Mth.sqrt(x * x + z * z);
        float sx = length < 0.001f ? 1f : -z / length;
        float sz = length < 0.001f ? 0f : x / length;
        float flicker = 0.85f + 0.15f * wave(t, 0.9) * wave(t, 0.37);

        Matrix4f m = pose.last().pose();
        strip(m, sx, sz, 0.60f * width, height, palette.main, 0.28f * alpha * flicker);
        strip(m, sx, sz, 0.22f * width, height, palette.light, 0.55f * alpha);
        strip(m, sx, sz, 0.07f * width, height, WHITE, 0.90f * alpha);

        for (int i = 0; i < BEAM_PULSES; i++) {
            float rise = (float) ((t / 46.0 + i / (double) BEAM_PULSES) % 1.0);
            float fade = Mth.sin(rise * Mth.PI);
            pose.pushPose();
            pose.translate(0f, rise * height, 0f);
            pose.mulPose(camera.rotation());
            glow(pose.last().pose(), PortalRenderer.MOTE, 0.30f + 0.25f * fade, 0f, 0f, palette.light, 0.9f * fade * alpha);
            pose.popPose();
        }
    }

    /** Ujung tiang yang sedang naik: titik terang dengan kilau kecil. */
    private static void head(PoseStack pose, Camera camera, double t, float height, float alpha, Palette palette) {
        float pulse = 1f + 0.15f * wave(t, 0.9);
        pose.pushPose();
        pose.translate(0f, height, 0f);
        pose.mulPose(camera.rotation());
        Matrix4f m = pose.last().pose();
        glow(m, PortalRenderer.MOTE, 1.5f * pulse, 0f, 0f, palette.main, 0.55f * alpha);
        glow(m, PortalRenderer.MOTE, 0.6f * pulse, 0f, 0.01f, WHITE, alpha);
        pose.mulPose(Axis.ZP.rotationDegrees(deg(t, 2.0)));
        flare(pose.last().pose(), 0.05f, 1.3f * pulse, palette.light, 0.8f * alpha);
        pose.popPose();
    }

    /** Tiang sampai di puncak: kilatan dan dua gelombang kejut (menghadap kamera & mendatar). */
    private static void arrival(PoseStack pose, Camera camera, float age, Palette palette) {
        if (age >= SHOCKWAVE_TICKS + 8f) return;
        pose.pushPose();
        pose.mulPose(camera.rotation());
        Matrix4f m = pose.last().pose();
        glow(m, PortalRenderer.MOTE, 2.0f + age * 0.35f, 0f, 0.03f, WHITE, Math.max(0f, 1f - age / 12f));
        shockwave(m, age / SHOCKWAVE_TICKS, 0.6f, 6.5f, 1.4f, palette.main, palette.light, 0.85f);
        pose.popPose();

        pose.pushPose();
        pose.mulPose(Axis.XP.rotationDegrees(90f));
        shockwave(pose.last().pose(), (age - 4f) / SHOCKWAVE_TICKS, 0.8f, 9f, 1.8f, palette.main, palette.light, 0.7f);
        pose.popPose();
    }

    /** Pendar dan kilau bintang. Semuanya menghadap kamera. */
    private static void halo(PoseStack pose, Camera camera, double t, float alpha, float flareAlpha, Palette palette) {
        float breathe = 1f + 0.06f * wave(t, 0.11);
        float twinkle = 0.75f + 0.25f * wave(t, 0.23);

        pose.pushPose();
        pose.mulPose(camera.rotation());
        Matrix4f m = pose.last().pose();
        glow(m, PortalRenderer.MOTE, 3.6f * breathe, 0f, 0f, palette.main, 0.50f * alpha);
        glow(m, PortalRenderer.MOTE, 1.7f * breathe, 0f, 0.01f, palette.light, 0.80f * alpha);
        glow(m, PortalRenderer.MOTE, 0.8f, 0f, 0.02f, WHITE, alpha);

        // Kilau empat arah yang berputar pelan, ditambah empat yang lebih pendek di sela-selanya
        pose.mulPose(Axis.ZP.rotationDegrees(deg(t, 0.35)));
        flare(pose.last().pose(), 0.09f, 3.2f * twinkle * flareAlpha, palette.light, 0.90f * flareAlpha);
        pose.mulPose(Axis.ZP.rotationDegrees(45f));
        flare(pose.last().pose(), 0.06f, 1.7f * (1.75f - twinkle) * flareAlpha, palette.accent, 0.70f * flareAlpha);
        pose.popPose();
    }

    /**
     * Cincin-cincin yang berputar mengelilingi kristal, plus piringan sihir yang hampir mendatar.
     * Muncul satu per satu seiring {@code power} naik.
     */
    private static void rings(PoseStack pose, double t, float power, float extraSpin, Palette palette) {
        ring(pose, deg(t, 1.9) + extraSpin, 68f, deg(t, 5.0) + extraSpin, 0.98f, 0.045f,
                palette.accent, 0.95f, stage(power, 0.15f, 0.45f));
        ring(pose, deg(t, -1.3) + 120f - extraSpin * 0.8f, -52f, deg(t, -6.5), 1.20f, 0.040f,
                palette.light, 0.85f, stage(power, 0.30f, 0.60f));
        ring(pose, deg(t, 0.8) + 240f + extraSpin * 0.6f, 24f, deg(t, 4.0), 1.42f, 0.035f,
                palette.main, 0.90f, stage(power, 0.45f, 0.75f));

        float disc = stage(power, 0.60f, 0.90f);
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(deg(t, 0.6)));
        pose.mulPose(Axis.XP.rotationDegrees(80f));
        glow(pose.last().pose(), PortalRenderer.CIRCLE, 1.75f * (0.5f + 0.5f * disc), deg(t, -1.6) - extraSpin, 0f,
                palette.main, 0.55f * disc);
        pose.popPose();
    }

    /** Kristal bersegi dengan inti yang berputar berlawanan, dan serpihan kecil yang mengorbit. */
    private static void crystal(PoseStack pose, double t, float power, float alpha, Palette palette) {
        float grow = power >= 0.5f ? 1f : Math.max(0.001f, PortalActorAnim.backOut(power * 2f));

        pose.pushPose();
        pose.scale(grow, grow, grow);
        pose.mulPose(Axis.YP.rotationDegrees(deg(t, 2.2)));
        gem(pose.last().pose(), 6, 0.44f, 0.80f, palette.light, palette.main, 0.85f * alpha, t);
        pose.popPose();

        pose.pushPose();
        pose.scale(grow, grow, grow);
        pose.mulPose(Axis.YP.rotationDegrees(deg(t, -5.5)));
        pose.mulPose(Axis.XP.rotationDegrees(deg(t, 3.1)));
        gem(pose.last().pose(), 4, 0.21f, 0.36f, WHITE, palette.accent, alpha, t);
        pose.popPose();

        // Serpihan terlempar dari kristal ke orbitnya
        float out = stage(power, 0.5f, 1f);
        if (out <= 0f) return;
        for (int i = 0; i < SHARDS; i++) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(deg(t, 1.4) + i * 360f / SHARDS));
            pose.translate((2.0f + 0.15f * wave(t + i * 40.0, 0.05)) * out, 0.40f * wave(t + i * 23.0, 0.07) * out, 0f);
            pose.mulPose(Axis.ZP.rotationDegrees(22f));
            pose.mulPose(Axis.YP.rotationDegrees(deg(t, 6.0) + i * 50f));
            gem(pose.last().pose(), 4, 0.10f, 0.26f, palette.light, palette.main, 0.80f * alpha * out, t + i * 9.0);
            pose.popPose();
        }
    }

    private static void ring(PoseStack pose, float precession, float tilt, float spin,
                             float radius, float width, float[] rgb, float alpha, float appear) {
        if (appear <= 0f) return;
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(precession));
        pose.mulPose(Axis.XP.rotationDegrees(tilt));
        pose.mulPose(Axis.ZP.rotationDegrees(spin));
        // Membuka dari kecil ke ukuran penuh
        band(pose.last().pose(), radius * (0.4f + 0.6f * appear), width, rgb, alpha * appear);
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
        if (alpha <= 0.003f || length <= 0f) return;
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

    /**
     * Cincin di bidang XY yang membesar dari {@code from} ke {@code to} sambil menipis & memudar;
     * {@code progress} di luar 0..1 = tidak digambar.
     */
    private static void shockwave(Matrix4f m, float progress, float from, float to, float thickness,
                                  float[] inside, float[] edge, float alpha) {
        if (progress <= 0f || progress >= 1f) return;
        float outer = Mth.lerp(progress, from, to);
        float inner = Math.max(0f, outer - thickness * (1f - progress));
        float a = alpha * (1f - progress);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < RING_SEGMENTS; i++) {
            float a0 = i * Mth.TWO_PI / RING_SEGMENTS;
            float a1 = (i + 1) * Mth.TWO_PI / RING_SEGMENTS;
            buf.vertex(m, Mth.cos(a0) * inner, Mth.sin(a0) * inner, 0.04f).color(inside[0], inside[1], inside[2], 0f).endVertex();
            buf.vertex(m, Mth.cos(a1) * inner, Mth.sin(a1) * inner, 0.04f).color(inside[0], inside[1], inside[2], 0f).endVertex();
            buf.vertex(m, Mth.cos(a1) * outer, Mth.sin(a1) * outer, 0.04f).color(edge[0], edge[1], edge[2], a).endVertex();
            buf.vertex(m, Mth.cos(a0) * outer, Mth.sin(a0) * outer, 0.04f).color(edge[0], edge[1], edge[2], a).endVertex();
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

    /** Seberapa jauh {@code power} sudah melewati rentang {@code from}..{@code to} (0..1, halus). */
    private static float stage(float power, float from, float to) {
        return PortalActorAnim.smooth(from, to, power);
    }
}

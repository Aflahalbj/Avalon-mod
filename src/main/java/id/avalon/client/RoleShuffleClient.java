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
import id.avalon.core.AvalonDimensions;
import id.avalon.cutscene.RoleShuffleTimeline;
import id.avalon.world.AvalonSeats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Animasi kocok peran di lingkaran kursi Avalon: bola di bawah lantai "bangun", roh-roh cahaya naik
 * menembus lantai, berputar saling menyalip di tengah, lalu melesat ke tiap kursi.
 *
 * Semua roh putih polos dan client hanya tahu kubu player-nya sendiri, jadi peran orang lain
 * tidak bisa terbaca dari animasinya.
 */
public final class RoleShuffleClient {

    private RoleShuffleClient() {}

    private static final float RISE_START = RoleShuffleTimeline.RISE_START;
    private static final float DEAL_START = RoleShuffleTimeline.DEAL_START;

    /** Tinggi pusaran roh di atas lantai, dan kedalaman pusat bola di bawahnya. */
    private static final double SWIRL_HEIGHT = 2.4;
    private static final double ORB_DEPTH = 5.5;
    /** Tinggi dada player yang duduk, dari dasar slab. */
    private static final double CHEST_HEIGHT = 1.3;

    private static final float FLOOR_RADIUS = 8.2f;
    private static final int TRAIL = 9;

    private static final int GOOD_RGB = 0x7FD4FF;
    private static final int EVIL_RGB = 0xFF3B3B;

    private static boolean active = false;
    private static int age = 0;
    private static int seats = 0;
    /** Kursi player client ini; -1 = hanya menonton. */
    private static int ownSeat = -1;
    private static boolean evil = false;

    public static void start(int seatCount, int own, boolean ownEvil) {
        PortalRenderer.prepareTextures();
        seats = Mth.clamp(seatCount, 0, AvalonSeats.OFFSETS.length);
        ownSeat = own;
        evil = ownEvil;
        age = 0;
        active = seats > 0;
    }

    public static void stop() {
        active = false;
    }

    // ── Geometri ──────────────────────────────────────────────────────────────

    /** Tengah lingkaran, tepat di permukaan lantai. */
    private static Vec3 center() {
        BlockPos c = AvalonSeats.CENTER;
        return new Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
    }

    private static Vec3 chest(int seat) {
        BlockPos slab = AvalonSeats.pos(seat);
        return new Vec3(slab.getX() + 0.5, slab.getY() + CHEST_HEIGHT, slab.getZ() + 0.5);
    }

    /** Posisi roh ke-{@code i} saat masih berputar di tengah (termasuk saat naik dari bola). */
    private static Vec3 orbit(int i, float t) {
        Vec3 c = center();
        float tau = t - RISE_START;
        // Menjelang dibagikan, semua roh merapat jadi satu cincin kecil yang berputar kencang
        float gather = PortalActorAnim.smooth(DEAL_START - 14f, DEAL_START - 2f, t);

        // Putaran bersama yang makin cepat + ayunan tiap roh, sehingga mereka saling menyalip
        float angle = 0.06f * tau + 0.0022f * tau * tau
                + i * Mth.TWO_PI / seats
                + 1.4f * Mth.sin(tau * 0.11f + i * 1.7f) * (1f - gather);
        float radius = Mth.lerp(gather, 3.0f + 0.9f * Mth.sin(tau * 0.17f + i * 2.3f), 1.3f);
        double height = SWIRL_HEIGHT + 0.5 * Mth.sin(tau * 0.21f + i) * (1f - gather);

        // Naik dari pusat bola sambil melebar
        float emerge = RISE_START + i * RoleShuffleTimeline.RISE_STAGGER;
        float rise = PortalActorAnim.smooth(emerge, emerge + 16f, t);
        radius *= rise;
        double y = Mth.lerp(rise, -ORB_DEPTH, height);

        return new Vec3(c.x + Mth.cos(angle) * radius, c.y + y, c.z + Mth.sin(angle) * radius);
    }

    /** Posisi roh ke-{@code i}; null = belum muncul atau sudah masuk ke player. */
    private static Vec3 wisp(int i, float t) {
        if (t < RISE_START + i * RoleShuffleTimeline.RISE_STAGGER) return null;

        float depart = DEAL_START + i * RoleShuffleTimeline.DEAL_STAGGER;
        if (t < depart) return orbit(i, t);

        float q = (t - depart) / RoleShuffleTimeline.DEAL_FLIGHT;
        if (q >= 1f) return null;
        // Melesat: pelan di awal, kencang di akhir, sedikit melambung
        Vec3 from = orbit(i, depart);
        Vec3 flown = from.lerp(chest(i), q * q);
        return flown.add(0, Mth.sin(q * Mth.PI) * 1.2, 0);
    }

    // ── Tick: suara & partikel ────────────────────────────────────────────────

    public static void tick() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level.dimension() != AvalonDimensions.AVALON) {
            active = false;
            return;
        }
        if (mc.isPaused()) return;
        if (++age > RoleShuffleTimeline.END) {
            active = false;
            return;
        }

        RandomSource rnd = level.random;
        Vec3 c = center();
        Vec3 swirl = c.add(0, SWIRL_HEIGHT, 0);

        // Bola bangun: dentum yang makin rapat
        if (age == 1) sound(level, c, SoundEvents.BEACON_ACTIVATE, 1.5f, 0.55f);
        if (age == 4 || age == 20 || age == 33 || age == 43 || age == 51) {
            sound(level, c, SoundEvents.WARDEN_HEARTBEAT, 2.0f, 0.7f + age * 0.006f);
        }

        // Bunga api naik dari bola menembus lantai
        if (age < DEAL_START) {
            for (int i = 0; i < 2; i++) {
                float angle = rnd.nextFloat() * Mth.TWO_PI;
                double r = rnd.nextDouble() * 3.0;
                level.addParticle(ParticleTypes.END_ROD, true,
                        c.x + Mth.cos(angle) * r, c.y - 3.5 + rnd.nextDouble() * 2, c.z + Mth.sin(angle) * r,
                        0, 0.08 + rnd.nextDouble() * 0.08, 0);
            }
        }

        // Tiap roh berdenting saat muncul
        for (int i = 0; i < seats; i++) {
            if (age == (int) (RISE_START + i * RoleShuffleTimeline.RISE_STAGGER)) {
                sound(level, c, SoundEvents.AMETHYST_BLOCK_CHIME, 1.6f, 0.7f + i * 0.09f);
            }
        }

        // Dikocok: denting acak yang makin rapat & makin tinggi
        if (age >= 52 && age < DEAL_START) {
            float progress = (age - 52) / (DEAL_START - 52f);
            int gap = Math.max(1, Math.round(Mth.lerp(progress, 6f, 1f)));
            if (age % gap == 0) {
                sound(level, swirl, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f, 0.6f + progress * 1.2f + rnd.nextFloat() * 0.2f);
            }
        }

        // Dibagikan: kilatan di tengah, roh melesat
        if (age == (int) DEAL_START) {
            sound(level, swirl, SoundEvents.FIREWORK_ROCKET_BLAST, 1.5f, 1.4f);
            sound(level, swirl, SoundEvents.TRIDENT_RIPTIDE_1, 1.2f, 1.3f);
            level.addParticle(ParticleTypes.FLASH, true, swirl.x, swirl.y, swirl.z, 0, 0, 0);
        }

        // Roh sampai: letupan kecil di dada pemilik kursi
        for (int i = 0; i < seats; i++) {
            if (age != Mth.ceil(RoleShuffleTimeline.arrival(i))) continue;
            Vec3 at = chest(i);
            for (int k = 0; k < 10; k++) {
                level.addParticle(ParticleTypes.END_ROD, true, at.x, at.y, at.z,
                        (rnd.nextDouble() - 0.5) * 0.18, rnd.nextDouble() * 0.14, (rnd.nextDouble() - 0.5) * 0.18);
            }
            if (i == ownSeat) {
                sound(level, at, SoundEvents.BEACON_POWER_SELECT, 1.2f, evil ? 0.6f : 1.3f);
            } else {
                sound(level, at, SoundEvents.AMETHYST_BLOCK_CHIME, 0.8f, 1.5f);
            }
        }
    }

    private static void sound(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.MASTER, volume, pitch, false);
    }

    // ── Render di dunia ───────────────────────────────────────────────────────

    public static void render(RenderLevelStageEvent event) {
        if (!active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != AvalonDimensions.AVALON) return;

        float t = age + event.getPartialTick();
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        Quaternionf facing = event.getCamera().rotation();
        Vec3 c = center();

        float fadeIn = PortalActorAnim.smooth(0f, 30f, t);
        float fadeOut = 1f - PortalActorAnim.smooth(RoleShuffleTimeline.REVEAL, RoleShuffleTimeline.END, t);
        // Makin dekat ke pembagian makin terang, lalu menyala sesaat ketika roh dilepas
        float charge = PortalActorAnim.smooth(RISE_START, DEAL_START, t);
        float flash = Mth.clamp(1f - (t - DEAL_START) / 12f, 0f, 1f) * (t >= DEAL_START ? 1f : 0f);
        float beat = 0.85f + 0.15f * Mth.sin(t * (0.25f + 0.5f * charge));

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);

        // Cahaya bola di bawah lantai
        float orb = fadeIn * fadeOut * beat;
        billboard(pose, cam, facing, c.add(0, -ORB_DEPTH, 0), 4.5f + 2.5f * charge + 2f * flash,
                0.60f, 0.30f, 1.0f, (0.35f + 0.35f * charge) * orb);

        // Lingkaran sihir rebah di lantai: dua lapis berputar berlawanan, makin cepat
        float spin = t * 1.2f + 0.02f * t * t * charge;
        float grow = t >= 35f ? 1f : PortalActorAnim.backOut(t / 35f);
        pose.pushPose();
        pose.translate(c.x - cam.x, c.y + 0.03 - cam.y, c.z - cam.z);
        pose.mulPose(Axis.XP.rotationDegrees(90f));
        Matrix4f floor = pose.last().pose();
        float floorAlpha = fadeIn * fadeOut * (0.55f + 0.25f * charge + 0.4f * flash) * beat;
        PortalRenderer.quad(floor, PortalRenderer.CIRCLE, FLOOR_RADIUS * grow, spin, 0f, 0.72f, 0.45f, 1.0f, floorAlpha);
        PortalRenderer.quad(floor, PortalRenderer.CIRCLE, FLOOR_RADIUS * 0.55f * grow, -spin * 2.2f, -0.01f, 1.0f, 0.75f, 1.0f, floorAlpha * 0.9f);
        pose.popPose();

        // Tiang cahaya dari bola ke pusaran
        float beamAlpha = PortalActorAnim.smooth(RISE_START, RISE_START + 25f, t)
                * (1f - PortalActorAnim.smooth(DEAL_START + 2f, DEAL_START + 16f, t));
        if (beamAlpha > 0.01f) {
            pose.pushPose();
            pose.translate(c.x - cam.x, c.y - cam.y, c.z - cam.z);
            beam(pose.last().pose(), (float) -ORB_DEPTH, (float) SWIRL_HEIGHT + 1.5f,
                    0.5f + 0.5f * charge, (0.25f + 0.35f * charge) * beamAlpha * beat);
            pose.popPose();
        }

        // Kilatan saat roh dilepas
        if (flash > 0f) {
            billboard(pose, cam, facing, c.add(0, SWIRL_HEIGHT, 0), 2f + 5f * (1f - flash), 1f, 0.9f, 1f, flash);
        }

        // Roh: inti putih + pendar ungu + ekor yang memudar
        for (int i = 0; i < seats; i++) {
            for (int k = TRAIL; k >= 0; k--) {
                Vec3 p = wisp(i, t - k * 0.7f);
                if (p == null) continue;
                float tail = 1f - k / (float) (TRAIL + 1);
                billboard(pose, cam, facing, p, 0.45f * tail, 0.72f, 0.55f, 1.0f, 0.55f * tail * tail);
                if (k == 0) billboard(pose, cam, facing, p, 0.2f, 1f, 1f, 1f, 1f);
            }
        }

        // Tanda di lantai tiap kursi begitu rohnya masuk
        for (int i = 0; i < seats; i++) {
            float since = t - RoleShuffleTimeline.arrival(i);
            if (since < 0f || since > 45f) continue;
            BlockPos slab = AvalonSeats.pos(i);
            pose.pushPose();
            pose.translate(slab.getX() + 0.5 - cam.x, slab.getY() + 0.06 - cam.y, slab.getZ() + 0.5 - cam.z);
            pose.mulPose(Axis.XP.rotationDegrees(90f));
            float k = since / 45f;
            PortalRenderer.quad(pose.last().pose(), PortalRenderer.CIRCLE, 1.0f + 0.9f * Mth.sqrt(k), t * 6f, 0f,
                    0.85f, 0.65f, 1.0f, (1f - k) * (1f - k));
            pose.popPose();
        }

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** Titik cahaya yang selalu menghadap kamera. */
    private static void billboard(PoseStack pose, Vec3 cam, Quaternionf facing, Vec3 at, float half,
                                  float r, float g, float b, float a) {
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        pose.mulPose(facing);
        PortalRenderer.quad(pose.last().pose(), PortalRenderer.MOTE, half, 0f, 0f, r, g, b, a);
        pose.popPose();
    }

    /** Tiang cahaya tegak (dua bidang bersilang): terang di bawah, memudar ke atas. */
    private static void beam(Matrix4f m, float bottom, float top, float half, float alpha) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int side = 0; side < 2; side++) {
            float dx = side == 0 ? half : 0f;
            float dz = side == 0 ? 0f : half;
            buf.vertex(m, -dx, bottom, -dz).color(0.75f, 0.5f, 1f, alpha).endVertex();
            buf.vertex(m, dx, bottom, dz).color(0.75f, 0.5f, 1f, alpha).endVertex();
            buf.vertex(m, dx, top, dz).color(1f, 0.9f, 1f, 0f).endVertex();
            buf.vertex(m, -dx, top, -dz).color(1f, 0.9f, 1f, 0f).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }

    // ── Overlay layar ─────────────────────────────────────────────────────────

    /** Layar berkedip warna kubu sendiri saat roh masuk ke player ini. */
    public static void renderOverlay(GuiGraphics g, int width, int height, float partialTick) {
        if (!active || ownSeat < 0) return;
        float since = age + partialTick - RoleShuffleTimeline.arrival(ownSeat);
        if (since < 0f || since > 24f) return;
        float k = 1f - since / 24f;
        int alpha = (int) (k * k * 150f);
        if (alpha > 2) g.fill(0, 0, width, height, (alpha << 24) | (evil ? EVIL_RGB : GOOD_RGB));
    }
}

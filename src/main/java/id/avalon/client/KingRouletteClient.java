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
import id.avalon.cutscene.KingRouletteTimeline;
import id.avalon.world.AvalonSeats;
import net.minecraft.client.Minecraft;
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

import java.util.List;

/**
 * Animasi kocok raja: pedang naik dari bola di bawah lantai, rebah menjadi jarum kompas, berputar
 * makin pelan melewati kursi-kursi (berdetak & menyalakan kursi yang dilewati), kebablasan sedikit
 * lalu mundur mengunci ke kursi raja dan menembakkan semburan partikel ke raja. Mahkota terbentuk
 * di atas kepala raja, pedang tenggelam lagi.
 *
 * Raja adalah informasi umum, jadi semua client menampilkan hal yang sama.
 */
public final class KingRouletteClient {

    private KingRouletteClient() {}

    private static final float RISE_END = KingRouletteTimeline.RISE_END;
    private static final float TILT_START = KingRouletteTimeline.TILT_START;
    private static final float TILT_END = KingRouletteTimeline.TILT_END;
    private static final float SPIN_START = KingRouletteTimeline.SPIN_START;
    private static final float SPIN_END = KingRouletteTimeline.SPIN_END;
    private static final float LOCK = KingRouletteTimeline.LOCK;
    private static final float BOLT_HIT = KingRouletteTimeline.LOCK + KingRouletteTimeline.BOLT_TICKS;
    private static final float SINK_START = KingRouletteTimeline.SINK_START;
    private static final float END = KingRouletteTimeline.END;

    /** Panjang pedang (blok), tinggi porosnya di atas lantai, dan kedalaman bola. */
    private static final float SWORD_LENGTH = 3.4f;
    private static final double SWORD_HEIGHT = 1.6;
    private static final double ORB_DEPTH = 5.5;
    /** Poros putar di tengah panjang pedang (satuan model). */
    private static final float PIVOT = 0.5f;

    private static boolean active = false;
    private static int age = 0;
    private static int target = -1;
    private static String king = "";
    private static int[] seats = new int[0];
    /** Arah tiap kursi dari tengah (derajat, searah putaran pedang). */
    private static float[] seatAngles = new float[0];
    /** Umur terakhir pedang melewati tiap kursi (untuk nyala sesaat). */
    private static float[] passedAt = new float[0];
    private static float finalAngle = 0f;

    public static void start(int targetSeat, List<Integer> seatList, String kingName) {
        PortalRenderer.prepareTextures();
        target = targetSeat;
        king = kingName;
        seats = seatList.stream().mapToInt(Integer::intValue)
                .filter(s -> s >= 0 && s < AvalonSeats.OFFSETS.length).toArray();
        seatAngles = new float[seats.length];
        passedAt = new float[seats.length];
        for (int i = 0; i < seats.length; i++) {
            seatAngles[i] = seatAngle(seats[i]);
            passedAt[i] = -100f;
        }
        // Berhenti tepat di kursi raja setelah beberapa putaran penuh
        float targetAngle = target >= 0 && target < AvalonSeats.OFFSETS.length ? seatAngle(target) : 0f;
        finalAngle = Mth.positiveModulo(targetAngle, 360f) + 360f * KingRouletteTimeline.TURNS;
        age = 0;
        active = true;
    }

    public static void stop() {
        active = false;
    }

    /** Arah kursi dari tengah lingkaran, dalam derajat putaran sumbu Y (0 = arah +Z). */
    private static float seatAngle(int seat) {
        int[] off = AvalonSeats.OFFSETS[seat];
        return (float) (Mth.atan2(off[0], off[1]) * Mth.RAD_TO_DEG);
    }

    /** Arah pedang (derajat) pada waktu {@code t}. */
    private static float heading(float t) {
        if (t <= SPIN_START) return 0f;
        float over = finalAngle + KingRouletteTimeline.OVERSHOOT;
        if (t <= SPIN_END) {
            // Melesat kencang lalu melambat seperti roda undian
            float k = (t - SPIN_START) / (SPIN_END - SPIN_START);
            float inv = 1f - k;
            return over * (1f - inv * inv * inv * inv);
        }
        // Kebablasan sedikit, lalu mundur mengunci ke kursi raja
        return over - KingRouletteTimeline.OVERSHOOT * PortalActorAnim.smooth(SPIN_END, LOCK, t);
    }

    private static Vec3 center() {
        BlockPos c = AvalonSeats.CENTER;
        return new Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
    }

    private static Vec3 seatPos(int seat, double up) {
        BlockPos slab = AvalonSeats.pos(seat);
        return new Vec3(slab.getX() + 0.5, slab.getY() + up, slab.getZ() + 0.5);
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
        age++;
        if (age > END) {
            active = false;
            return;
        }

        RandomSource rnd = level.random;
        Vec3 c = center();
        Vec3 hub = c.add(0, SWORD_HEIGHT, 0);

        if (age == 1) sound(level, c, SoundEvents.BEACON_ACTIVATE, 1.5f, 0.8f);
        if (age == 8) sound(level, c, SoundEvents.ANVIL_LAND, 0.4f, 0.5f);
        if (age == (int) TILT_END) sound(level, hub, SoundEvents.TRIDENT_RIPTIDE_3, 1.0f, 1.2f);

        // Bunga api emas naik dari bola selama pedang naik
        if (age < RISE_END + 5) {
            for (int i = 0; i < 2; i++) {
                float a = rnd.nextFloat() * Mth.TWO_PI;
                double r = rnd.nextDouble() * 1.5;
                level.addParticle(ParticleTypes.WAX_OFF, true, c.x + Mth.cos(a) * r, c.y - 2.5, c.z + Mth.sin(a) * r,
                        0, 0.2 + rnd.nextDouble() * 0.15, 0);
            }
        }

        // Detak tiap kali pedang melewati sebuah kursi (paling banyak satu bunyi per tick)
        float prev = heading(age - 1);
        float now = heading(age);
        boolean clicked = false;
        for (int i = 0; i < seats.length; i++) {
            if (crossed(prev, now, seatAngles[i])) {
                passedAt[i] = age;
                if (!clicked) {
                    float speed = Math.abs(now - prev);
                    sound(level, seatPos(seats[i], 1.0), SoundEvents.NOTE_BLOCK_HAT.value(), 1.0f,
                            Mth.clamp(1.6f - speed * 0.01f, 0.9f, 1.6f));
                    clicked = true;
                }
            }
        }

        // Terkunci: pedang menembakkan semburan partikel dari ujungnya ke dada raja
        if (target >= 0 && age >= LOCK && age <= BOLT_HIT) {
            if (age == (int) LOCK) sound(level, hub, SoundEvents.TRIDENT_THROW, 1.4f, 1.3f);
            float a = heading(LOCK) * Mth.DEG_TO_RAD;
            Vec3 tip = hub.add(Mth.sin(a) * SWORD_LENGTH * (1f - PIVOT), 0, Mth.cos(a) * SWORD_LENGTH * (1f - PIVOT));
            Vec3 seat = seatPos(target, 1.3);
            boltParticles(level, rnd, tip, seat, (age - LOCK) / (BOLT_HIT - LOCK));

            if (age == (int) BOLT_HIT) {
                sound(level, seat, SoundEvents.BELL_BLOCK, 1.5f, 0.9f);
                sound(level, seat, SoundEvents.AMETHYST_BLOCK_CHIME, 1.5f, 1.2f);
                CrownClient.form(king, target);
                for (int i = 0; i < 24; i++) {
                    level.addParticle(i % 2 == 0 ? ParticleTypes.END_ROD : ParticleTypes.WAX_ON, true,
                            seat.x, seat.y, seat.z,
                            (rnd.nextDouble() - 0.5) * 0.3, rnd.nextDouble() * 0.2, (rnd.nextDouble() - 0.5) * 0.3);
                }
            }
        }
        if (age == (int) SINK_START + 8) sound(level, c, SoundEvents.BEACON_DEACTIVATE, 1.2f, 0.9f);
    }

    /**
     * Semburan partikel yang melaju dari {@code from} ke {@code to}: kepala semburan di titik
     * {@code progress} (0..1) sepanjang garis, diikuti ekor yang tersebar di belakangnya.
     */
    private static void boltParticles(ClientLevel level, RandomSource rnd, Vec3 from, Vec3 to, float progress) {
        Vec3 dir = to.subtract(from);
        Vec3 step = dir.normalize().scale(0.12);
        for (int i = 0; i < 14; i++) {
            double k = Mth.clamp(progress - rnd.nextDouble() * 0.35, 0.0, 1.0);
            Vec3 at = from.add(dir.scale(k));
            double jitter = 0.12;
            level.addParticle(i % 3 == 0 ? ParticleTypes.WAX_ON : ParticleTypes.END_ROD, true,
                    at.x + (rnd.nextDouble() - 0.5) * jitter,
                    at.y + (rnd.nextDouble() - 0.5) * jitter,
                    at.z + (rnd.nextDouble() - 0.5) * jitter,
                    step.x, step.y, step.z);
        }
        // Percikan di ujung pedang saat menembak
        if (progress <= 0f) {
            for (int i = 0; i < 8; i++) {
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, true, from.x, from.y, from.z,
                        (rnd.nextDouble() - 0.5) * 0.3, (rnd.nextDouble() - 0.5) * 0.3, (rnd.nextDouble() - 0.5) * 0.3);
            }
        }
    }

    /** true = arah {@code angle} terlewati di antara {@code from} dan {@code to}. */
    private static boolean crossed(float from, float to, float angle) {
        if (from == to) return false;
        double a = Math.floor((from - angle) / 360.0);
        double b = Math.floor((to - angle) / 360.0);
        return a != b;
    }

    private static void sound(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.MASTER, volume, pitch, false);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    public static void render(RenderLevelStageEvent event) {
        if (!active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != AvalonDimensions.AVALON) return;

        float t = age + event.getPartialTick();
        Vec3 cam = event.getCamera().getPosition();
        Quaternionf facing = event.getCamera().rotation();
        PoseStack pose = event.getPoseStack();
        Vec3 c = center();

        // ── Gerak pedang ──
        float rise = PortalActorAnim.smooth(0f, RISE_END, t);
        float sink = PortalActorAnim.smooth(SINK_START + 8f, END - 4f, t);
        double y = Mth.lerp(rise, -ORB_DEPTH, SWORD_HEIGHT);
        y = Mth.lerp(sink, y, -ORB_DEPTH);
        float tilt = 90f * PortalActorAnim.smooth(TILT_START, TILT_END, t)
                * (1f - PortalActorAnim.smooth(SINK_START, SINK_START + 10f, t));
        // Saat tegak berputar pelan di sumbunya sendiri; saat mendatar sisi lebarnya menghadap ke samping
        float flat = PortalActorAnim.smooth(TILT_START, TILT_END, t) * (1f - PortalActorAnim.smooth(SINK_START, SINK_START + 10f, t));
        float roll = Mth.lerp(flat, t * 9f, 90f);
        float alpha = PortalActorAnim.smooth(0f, 10f, t) * (1f - PortalActorAnim.smooth(END - 10f, END, t));
        float heading = heading(t);
        float speed = Math.abs(heading(t + 0.5f) - heading(t - 0.5f));

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.depthMask(false);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);

        // Bola di bawah lantai menyala emas
        float glow = alpha * (0.45f + 0.15f * Mth.sin(t * 0.3f));
        billboard(pose, cam, facing, c.add(0, -ORB_DEPTH, 0), 5.5f, 1f, 0.75f, 0.3f, glow);

        // Lingkaran cahaya bekas putaran saat pedang berputar kencang
        float blur = Mth.clamp((speed - 6f) / 30f, 0f, 1f) * alpha;
        if (blur > 0.01f) {
            pose.pushPose();
            pose.translate(c.x - cam.x, c.y + y - cam.y, c.z - cam.z);
            disc(pose.last().pose(), SWORD_LENGTH * 0.5f, 0.18f * blur);
            pose.popPose();
        }

        // Kursi yang dilewati menyala sesaat; kursi raja menyala terus setelah terkunci
        for (int i = 0; i < seats.length; i++) {
            float k = Mth.clamp(1f - (t - passedAt[i]) / 8f, 0f, 1f);
            if (seats[i] == target && t >= LOCK) k = 1f - PortalActorAnim.smooth(END - 15f, END, t);
            if (k <= 0.01f) continue;
            BlockPos slab = AvalonSeats.pos(seats[i]);
            pose.pushPose();
            pose.translate(slab.getX() + 0.5 - cam.x, slab.getY() + 0.06 - cam.y, slab.getZ() + 0.5 - cam.z);
            pose.mulPose(Axis.XP.rotationDegrees(90f));
            PortalRenderer.quad(pose.last().pose(), PortalRenderer.CIRCLE, 1.4f, t * 4f, 0f, 1f, 0.8f, 0.35f, 0.9f * k);
            pose.popPose();
        }

        // Pendar di sekitar bilah
        billboard(pose, cam, facing, c.add(0, y, 0), SWORD_LENGTH * 0.45f, 1f, 0.85f, 0.5f, 0.25f * alpha);

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        // ── Pedang padat ──
        pose.pushPose();
        pose.translate(c.x - cam.x, c.y + y - cam.y, c.z - cam.z);
        pose.mulPose(Axis.YP.rotationDegrees(heading));
        pose.mulPose(Axis.XP.rotationDegrees(tilt));
        pose.mulPose(Axis.YP.rotationDegrees(roll));
        pose.scale(SWORD_LENGTH, SWORD_LENGTH, SWORD_LENGTH);
        pose.translate(0f, -PIVOT, 0f);
        SwordMesh.render(pose, mc.renderBuffers().bufferSource(), alpha);
        pose.popPose();
    }

    private static void billboard(PoseStack pose, Vec3 cam, Quaternionf facing, Vec3 at, float half,
                                  float r, float g, float b, float a) {
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        pose.mulPose(facing);
        PortalRenderer.quad(pose.last().pose(), PortalRenderer.MOTE, half, 0f, 0f, r, g, b, a);
        pose.popPose();
    }

    /** Cakram mendatar tipis (jejak putaran), lebih terang ke arah tepi. */
    private static void disc(Matrix4f m, float radius, float alpha) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int n = 40;
        float inner = radius * 0.2f;
        for (int i = 0; i < n; i++) {
            float a0 = i * Mth.TWO_PI / n, a1 = (i + 1) * Mth.TWO_PI / n;
            buf.vertex(m, Mth.cos(a0) * inner, 0f, Mth.sin(a0) * inner).color(1f, 0.85f, 0.5f, 0f).endVertex();
            buf.vertex(m, Mth.cos(a1) * inner, 0f, Mth.sin(a1) * inner).color(1f, 0.85f, 0.5f, 0f).endVertex();
            buf.vertex(m, Mth.cos(a1) * radius, 0f, Mth.sin(a1) * radius).color(1f, 0.95f, 0.8f, alpha).endVertex();
            buf.vertex(m, Mth.cos(a0) * radius, 0f, Mth.sin(a0) * radius).color(1f, 0.95f, 0.8f, alpha).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }
}

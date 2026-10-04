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
import id.avalon.world.AvalonSeats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Mahkota emas yang melayang di atas kepala raja. Muncul (terbentuk dari cahaya) saat raja pertama
 * dipilih, lalu terbang ke kepala raja berikutnya setiap kali raja berganti.
 */
public final class CrownClient {

    private CrownClient() {}

    private static final int SEGMENTS = 12;
    private static final float RADIUS = 0.28f;
    private static final float BAND = 0.12f;
    private static final float SPIKE = 0.18f;

    /** Lama animasi terbentuk & terbang (tick). */
    private static final float FORM_TICKS = 30f;
    private static final float FLY_TICKS = 30f;

    private static String king = "";
    private static int seat = -1;
    private static int age = 0;
    /** Umur saat mahkota mulai terbentuk / mulai terbang; -1 = tidak sedang beranimasi. */
    private static float formAt = -1f;
    private static float flyAt = -1f;
    private static Vec3 flyFrom = null;
    /** Posisi mahkota frame terakhir (titik awal kalau raja berganti). */
    private static Vec3 last = null;

    /** Mahkota terbentuk di atas raja baru (raja pertama). */
    public static void form(String name, int seatIndex) {
        king = name;
        seat = seatIndex;
        formAt = age;
        flyAt = -1f;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Vec3 at = anchor(mc, 0f);
            if (at != null) {
                sound(mc.level, at, SoundEvents.BELL_BLOCK, 1.2f, 1.4f);
                burst(mc.level, at, 24);
            }
        }
    }

    /** Pasang mahkota ke {@code name}; kosong = hapus. {@code animate} = terbang dari posisi lama. */
    public static void set(String name, int seatIndex, boolean animate) {
        if (name == null || name.isEmpty()) {
            king = "";
            seat = -1;
            last = null;
            return;
        }
        if (name.equals(king)) {
            seat = seatIndex;
            return;
        }
        Vec3 from = last;
        king = name;
        seat = seatIndex;
        formAt = -1f;
        if (animate && from != null) {
            flyFrom = from;
            flyAt = age;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) sound(mc.level, from, SoundEvents.TRIDENT_RIPTIDE_1, 0.8f, 1.6f);
        } else {
            flyAt = -1f;
        }
    }

    public static void tick() {
        if (king.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            set("", -1, false);
            return;
        }
        if (mc.isPaused()) return;
        age++;

        // Mendarat di kepala raja baru
        if (flyAt >= 0f && age - flyAt == (int) FLY_TICKS) {
            Vec3 at = anchor(mc, 0f);
            if (at != null) {
                sound(mc.level, at, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 1.2f);
                burst(mc.level, at, 12);
            }
        }
        // Kilau kecil sesekali
        Vec3 at = last;
        if (at != null && age % 6 == 0) {
            RandomSource rnd = mc.level.random;
            mc.level.addParticle(ParticleTypes.END_ROD, at.x + (rnd.nextDouble() - 0.5) * 0.6,
                    at.y + rnd.nextDouble() * 0.3, at.z + (rnd.nextDouble() - 0.5) * 0.6, 0, 0.01, 0);
        }
    }

    /** Titik di atas kepala raja (atau di atas kursinya kalau entity-nya tidak terlihat). */
    private static Vec3 anchor(Minecraft mc, float partial) {
        for (Player p : mc.level.players()) {
            if (p.getGameProfile().getName().equals(king)) {
                double x = Mth.lerp(partial, p.xOld, p.getX());
                double y = Mth.lerp(partial, p.yOld, p.getY());
                double z = Mth.lerp(partial, p.zOld, p.getZ());
                return new Vec3(x, y + p.getEyeHeight() + 0.42, z);
            }
        }
        if (seat < 0 || seat >= AvalonSeats.OFFSETS.length) return null;
        BlockPos slab = AvalonSeats.pos(seat);
        return new Vec3(slab.getX() + 0.5, slab.getY() + 1.96, slab.getZ() + 0.5);
    }

    private static boolean isOwnFirstPerson(Minecraft mc) {
        return mc.player != null && mc.player.getGameProfile().getName().equals(king)
                && mc.options.getCameraType().isFirstPerson() && !PortalCutsceneClient.hasCamera();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    public static void render(RenderLevelStageEvent event) {
        if (king.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        float partial = event.getPartialTick();
        float t = age + partial;
        Vec3 target = anchor(mc, partial);
        if (target == null) return;

        Vec3 at = target;
        float scale = 1f;
        float formGlow = 0f;
        if (formAt >= 0f && t - formAt < FORM_TICKS) {
            // Terbentuk: membesar dari titik cahaya sambil turun ke atas kepala
            float k = (t - formAt) / FORM_TICKS;
            scale = PortalActorAnim.backOut(Math.min(1f, k * 1.4f));
            at = target.add(0, 1.2 * (1f - PortalActorAnim.smooth(0f, 1f, k)), 0);
            formGlow = 1f - k;
        } else if (flyAt >= 0f && flyFrom != null && t - flyAt < FLY_TICKS) {
            // Terbang melengkung ke kepala raja baru
            float k = PortalActorAnim.smooth(0f, FLY_TICKS, t - flyAt);
            at = flyFrom.lerp(target, k).add(0, Math.sin(k * Math.PI) * 2.0, 0);
        }
        at = at.add(0, Mth.sin(t * 0.1f) * 0.04f, 0);
        last = at;

        if (isOwnFirstPerson(mc)) return;

        Vec3 cam = event.getCamera().getPosition();
        Quaternionf facing = event.getCamera().rotation();
        PoseStack pose = event.getPoseStack();

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        // Mahkota padat
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        pose.mulPose(Axis.YP.rotationDegrees(t * 1.5f));
        pose.scale(scale, scale, scale);
        crown(pose.last().pose());
        pose.popPose();

        // Pendar keemasan + permata
        RenderSystem.depthMask(false);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y + 0.1 - cam.y, at.z - cam.z);
        pose.mulPose(facing);
        float glow = 0.35f + 0.08f * Mth.sin(t * 0.2f) + 0.6f * formGlow;
        PortalRenderer.quad(pose.last().pose(), PortalRenderer.MOTE, (0.55f + 0.8f * formGlow) * scale, 0f, 0f,
                1f, 0.8f, 0.3f, glow);
        pose.popPose();

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** Cincin emas dengan duri-duri di atasnya (titik 0 = bawah cincin, di tengah kepala). */
    private static void crown(Matrix4f m) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = i * Mth.TWO_PI / SEGMENTS;
            float a1 = (i + 1) * Mth.TWO_PI / SEGMENTS;
            float x0 = Mth.cos(a0) * RADIUS, z0 = Mth.sin(a0) * RADIUS;
            float x1 = Mth.cos(a1) * RADIUS, z1 = Mth.sin(a1) * RADIUS;
            // Sisi yang menghadap "cahaya" lebih terang, supaya cincin terlihat bulat
            float shade = 0.72f + 0.28f * Mth.cos((a0 + a1) * 0.5f - 0.8f);
            float r = 1.0f * shade, g = 0.78f * shade, b = 0.22f * shade;

            // Pita cincin (bawah lebih gelap)
            buf.vertex(m, x0, 0f, z0).color(r * 0.7f, g * 0.7f, b * 0.7f, 1f).endVertex();
            buf.vertex(m, x1, 0f, z1).color(r * 0.7f, g * 0.7f, b * 0.7f, 1f).endVertex();
            buf.vertex(m, x1, BAND, z1).color(r, g, b, 1f).endVertex();
            buf.vertex(m, x0, BAND, z0).color(r, g, b, 1f).endVertex();

            // Duri runcing di tiap segmen genap (segitiga dikirim sebagai segi empat)
            if (i % 2 == 0) {
                float xm = Mth.cos((a0 + a1) * 0.5f) * RADIUS, zm = Mth.sin((a0 + a1) * 0.5f) * RADIUS;
                buf.vertex(m, x0, BAND, z0).color(r, g, b, 1f).endVertex();
                buf.vertex(m, x1, BAND, z1).color(r, g, b, 1f).endVertex();
                buf.vertex(m, xm, BAND + SPIKE, zm).color(1f, 0.95f, 0.6f, 1f).endVertex();
                buf.vertex(m, xm, BAND + SPIKE, zm).color(1f, 0.95f, 0.6f, 1f).endVertex();
            }
        }
        BufferUploader.drawWithShader(buf.end());
    }

    private static void burst(ClientLevel level, Vec3 at, int count) {
        RandomSource rnd = level.random;
        for (int i = 0; i < count; i++) {
            level.addParticle(ParticleTypes.END_ROD, true, at.x, at.y, at.z,
                    (rnd.nextDouble() - 0.5) * 0.2, rnd.nextDouble() * 0.15, (rnd.nextDouble() - 0.5) * 0.2);
        }
    }

    private static void sound(ClientLevel level, Vec3 at, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.MASTER, volume, pitch, false);
    }
}

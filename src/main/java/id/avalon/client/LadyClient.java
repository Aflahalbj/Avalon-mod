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
import id.avalon.cutscene.LadyTimeline;
import id.avalon.network.AvalonNetwork;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Lady of the Lake di sisi client.
 *
 * Token: bola air yang mengorbit badan pemegangnya, dan terbang ke pemegang berikutnya.
 * Pemeriksaan: danau terbuka di bawah pemain yang diperiksa, rohnya ditarik keluar, mengitari
 * tengah lingkaran kursi, lalu menukik masuk ke pemegang Lady.
 *
 * Rohnya putih kebiruan di semua client; hanya client si pemegang yang diberi tahu kubu yang
 * diperiksa (layarnya berkedip & danau target berubah warna), jadi hasilnya tidak bisa terbaca
 * dari animasi orang lain.
 */
public final class LadyClient {

    private LadyClient() {}

    private static final int GOOD_RGB = 0x7FD4FF;
    private static final int EVIL_RGB = 0xFF3B3B;

    // ── Token ─────────────────────────────────────────────────────────────────

    private static final float ORBIT_RADIUS = 0.6f;
    private static final float FORM_TICKS = 30f;
    private static final float FLY_TICKS = LadyTimeline.TOKEN_FLIGHT;
    private static final int TOKEN_TRAIL = 8;

    private static String holder = "";
    private static int seat = -1;
    private static int age = 0;
    /** Umur saat token mulai terbentuk / mulai terbang; -1 = tidak sedang beranimasi. */
    private static float formAt = -1f;
    private static float flyAt = -1f;
    private static Vec3 flyFrom = null;
    /** Posisi token frame terakhir (titik awal kalau pemegangnya berganti). */
    private static Vec3 last = null;

    // ── Pemeriksaan ───────────────────────────────────────────────────────────

    private static final float DRAW_START = LadyTimeline.DRAW_START;
    private static final float ORBIT_START = LadyTimeline.ORBIT_START;
    private static final float DIVE_START = LadyTimeline.DIVE_START;
    private static final float ARRIVE = LadyTimeline.ARRIVE;
    private static final float END = LadyTimeline.END;

    /** Tinggi roh melayang di atas dada sebelum berangkat / sebelum menukik. */
    private static final double HOVER = 1.5;
    private static final int WISP_TRAIL = 12;

    private static boolean inspecting = false;
    private static int inspectAge = 0;
    private static String target = "";
    private static int targetSeat = -1;
    private static String examiner = "";
    private static int examinerSeat = -1;
    private static int result = AvalonNetwork.LadyInspect.HIDDEN;

    /** Pasang token ke {@code name}; kosong = hapus semuanya. {@code animate} = muncul / terbang dari posisi lama. */
    public static void set(String name, int seatIndex, boolean animate) {
        if (name == null || name.isEmpty()) {
            holder = "";
            seat = -1;
            last = null;
            inspecting = false;
            return;
        }
        PortalRenderer.prepareTextures();
        if (name.equals(holder)) {
            seat = seatIndex;
            return;
        }
        Vec3 from = last;
        holder = name;
        seat = seatIndex;
        formAt = -1f;
        flyAt = -1f;
        if (!animate) return;

        Minecraft mc = Minecraft.getInstance();
        if (from != null) {
            flyFrom = from;
            flyAt = age;
            if (mc.level != null) sound(mc.level, from, SoundEvents.TRIDENT_RIPTIDE_1, 0.8f, 1.7f);
        } else {
            formAt = age;
            Vec3 at = mc.level == null ? null : body(mc, holder, seat, 1f);
            if (at != null) {
                sound(mc.level, at, SoundEvents.CONDUIT_ACTIVATE, 1.0f, 1.3f);
                burst(mc.level, at, 16);
            }
        }
    }

    /** Mulai animasi pemeriksaan: roh {@code targetName} terbang ke {@code holderName}. */
    public static void inspect(String targetName, int tSeat, String holderName, int hSeat, int res) {
        PortalRenderer.prepareTextures();
        target = targetName;
        targetSeat = tSeat;
        examiner = holderName;
        examinerSeat = hSeat;
        result = res;
        inspectAge = 0;
        inspecting = true;
    }

    // ── Geometri ──────────────────────────────────────────────────────────────

    /** Dada player (atau titik yang sama di atas kursinya kalau entity-nya tidak terlihat). */
    private static Vec3 body(Minecraft mc, String name, int seatIndex, float partial) {
        for (Player p : mc.level.players()) {
            if (p.getGameProfile().getName().equals(name)) {
                double x = Mth.lerp(partial, p.xOld, p.getX());
                double y = Mth.lerp(partial, p.yOld, p.getY());
                double z = Mth.lerp(partial, p.zOld, p.getZ());
                return new Vec3(x, y + (p.isPassenger() ? 1.25 : 1.0), z);
            }
        }
        if (seatIndex < 0 || seatIndex >= AvalonSeats.OFFSETS.length) return null;
        BlockPos slab = AvalonSeats.pos(seatIndex);
        return new Vec3(slab.getX() + 0.5, slab.getY() + 1.17, slab.getZ() + 0.5);
    }

    /** Titik di lantai di bawah player: permukaan kursinya, atau perkiraan dari posisi dadanya. */
    private static Vec3 floor(int seatIndex, Vec3 chest) {
        if (seatIndex >= 0 && seatIndex < AvalonSeats.OFFSETS.length) {
            BlockPos slab = AvalonSeats.pos(seatIndex);
            return new Vec3(slab.getX() + 0.5, slab.getY() + 0.06, slab.getZ() + 0.5);
        }
        return chest.add(0, -1.1, 0);
    }

    private static Vec3 center() {
        BlockPos c = AvalonSeats.CENTER;
        return new Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
    }

    /** Orbit miring mengelilingi badan pemegang. */
    private static Vec3 orbit(Vec3 c, float t, float phase) {
        float a = t * 0.11f + phase;
        return c.add(Mth.cos(a) * ORBIT_RADIUS, 0.22 * Mth.sin(a), Mth.sin(a) * ORBIT_RADIUS);
    }

    /** Posisi token: mengorbit, atau sedang terbang melengkung dari pemegang sebelumnya. */
    private static Vec3 tokenAt(Vec3 c, float t) {
        Vec3 at = orbit(c, t, 0f);
        if (flyAt >= 0f && flyFrom != null && t - flyAt < FLY_TICKS) {
            float k = PortalActorAnim.smooth(0f, FLY_TICKS, t - flyAt);
            return flyFrom.lerp(at, k).add(0, Mth.sin(k * Mth.PI) * 2.2, 0);
        }
        return at;
    }

    /** Posisi roh yang diperiksa; null = belum keluar atau sudah masuk ke pemegang Lady. */
    private static Vec3 wisp(Vec3 from, Vec3 to, float t) {
        if (t < DRAW_START || t >= ARRIVE) return null;
        Vec3 hoverFrom = from.add(0, HOVER, 0);
        Vec3 hoverTo = to.add(0, HOVER, 0);

        // Ditarik keluar dari dada sambil berpilin
        if (t < ORBIT_START) {
            float k = PortalActorAnim.smooth(DRAW_START, ORBIT_START, t);
            float a = (t - DRAW_START) * 0.45f;
            float r = 0.35f * Mth.sin(k * Mth.PI);
            return from.lerp(hoverFrom, k).add(Mth.cos(a) * r, 0, Mth.sin(a) * r);
        }

        // Mengitari tengah lingkaran kursi, merapat ke tengah lalu melebar lagi ke arah pemegang
        if (t < DIVE_START) {
            Vec3 c = center();
            float q = PortalActorAnim.smooth(ORBIT_START, DIVE_START, t);
            double a0 = Math.atan2(hoverFrom.z - c.z, hoverFrom.x - c.x);
            double a1 = Math.atan2(hoverTo.z - c.z, hoverTo.x - c.x);
            // Selalu searah, dan sedikitnya setengah putaran supaya terlihat melintasi meja
            double sweep = (a1 - a0) % Mth.TWO_PI;
            if (sweep <= 0) sweep += Mth.TWO_PI;
            if (sweep < Math.PI) sweep += Mth.TWO_PI;
            double r0 = Math.hypot(hoverFrom.x - c.x, hoverFrom.z - c.z);
            double r1 = Math.hypot(hoverTo.x - c.x, hoverTo.z - c.z);
            double arc = Mth.sin(q * Mth.PI);
            double r = Mth.lerp(q, r0, r1) * (1.0 - 0.62 * arc);
            double a = a0 + sweep * q;
            double y = Mth.lerp(q, hoverFrom.y, hoverTo.y) + 0.9 * arc;
            return new Vec3(c.x + Math.cos(a) * r, y, c.z + Math.sin(a) * r);
        }

        // Menukik: pelan di awal, kencang di akhir
        float q = (t - DIVE_START) / (ARRIVE - DIVE_START);
        return hoverTo.lerp(to, q * q);
    }

    // ── Tick: suara & partikel ────────────────────────────────────────────────

    public static void tick() {
        if (holder.isEmpty() && !inspecting) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            set("", -1, false);
            return;
        }
        if (mc.isPaused()) return;
        if (!holder.isEmpty()) tickToken(mc, level);
        if (inspecting) tickInspect(mc, level);
    }

    private static void tickToken(Minecraft mc, ClientLevel level) {
        age++;
        // Mendarat di pemegang baru
        if (flyAt >= 0f && age - flyAt == (int) FLY_TICKS && last != null) {
            sound(level, last, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 1.0f);
            sound(level, last, SoundEvents.CONDUIT_ACTIVATE, 0.7f, 1.6f);
            burst(level, last, 12);
        }
        // Tetes air & kilau kecil sesekali
        if (last != null) {
            RandomSource rnd = level.random;
            if (age % 7 == 0) level.addParticle(ParticleTypes.FALLING_WATER, last.x, last.y - 0.05, last.z, 0, 0, 0);
            if (age % 11 == 0) {
                level.addParticle(ParticleTypes.GLOW, last.x + (rnd.nextDouble() - 0.5) * 0.3,
                        last.y + (rnd.nextDouble() - 0.5) * 0.3, last.z + (rnd.nextDouble() - 0.5) * 0.3, 0, 0.01, 0);
            }
        }
    }

    private static void tickInspect(Minecraft mc, ClientLevel level) {
        if (level.dimension() != AvalonDimensions.AVALON || ++inspectAge > END) {
            inspecting = false;
            return;
        }
        Vec3 from = body(mc, target, targetSeat, 1f);
        Vec3 to = body(mc, examiner, examinerSeat, 1f);
        if (from == null || to == null) return;

        RandomSource rnd = level.random;
        Vec3 lake = floor(targetSeat, from);
        int t = inspectAge;

        // Danau terbuka: dengung dalam + dua detak
        if (t == 1) {
            sound(level, lake, SoundEvents.CONDUIT_ACTIVATE, 1.4f, 0.7f);
            sound(level, lake, SoundEvents.BEACON_ACTIVATE, 0.9f, 0.6f);
        }
        if (t == 8 || t == 16) sound(level, lake, SoundEvents.WARDEN_HEARTBEAT, 1.6f, 0.75f + t * 0.01f);

        // Air terangkat dari danau selama roh masih di dekat pemiliknya
        if (t < ORBIT_START + 10) {
            for (int i = 0; i < 2; i++) {
                float a = rnd.nextFloat() * Mth.TWO_PI;
                double r = 0.3 + rnd.nextDouble() * 0.9;
                level.addParticle(ParticleTypes.END_ROD, true,
                        lake.x + Mth.cos(a) * r, lake.y + rnd.nextDouble() * 0.3, lake.z + Mth.sin(a) * r,
                        0, 0.03 + rnd.nextDouble() * 0.05, 0);
            }
            if (t % 2 == 0) {
                float a = rnd.nextFloat() * Mth.TWO_PI;
                level.addParticle(ParticleTypes.SPLASH, lake.x + Mth.cos(a) * 1.1, lake.y + 0.05, lake.z + Mth.sin(a) * 1.1, 0, 0, 0);
            }
        }

        // Roh ditarik keluar
        if (t == (int) DRAW_START) {
            sound(level, from, SoundEvents.TRIDENT_RIPTIDE_1, 0.9f, 0.7f);
            sound(level, from, SoundEvents.AMETHYST_BLOCK_CHIME, 1.6f, 0.6f);
        }

        // Mengitari meja: denting yang makin rapat & makin tinggi, ekor bunga api
        Vec3 at = wisp(from, to, t);
        if (at != null && t >= ORBIT_START && t < DIVE_START) {
            float progress = (t - ORBIT_START) / (DIVE_START - ORBIT_START);
            int gap = Math.max(1, Math.round(Mth.lerp(progress, 6f, 2f)));
            if (t % gap == 0) {
                sound(level, at, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f, 0.6f + progress * 1.2f + rnd.nextFloat() * 0.15f);
            }
        }
        if (at != null && t % 2 == 0) {
            level.addParticle(ParticleTypes.END_ROD, true, at.x, at.y, at.z,
                    (rnd.nextDouble() - 0.5) * 0.03, -0.01, (rnd.nextDouble() - 0.5) * 0.03);
        }

        if (t == (int) DIVE_START) sound(level, to, SoundEvents.TRIDENT_RIPTIDE_1, 1.1f, 1.4f);

        // Masuk ke pemegang Lady. Nada kubunya hanya terdengar di client si pemegang.
        if (t == (int) ARRIVE) {
            burst(level, to, 18);
            float pitch = result == AvalonNetwork.LadyInspect.EVIL ? 0.6f
                    : result == AvalonNetwork.LadyInspect.GOOD ? 1.3f : 1.0f;
            sound(level, to, SoundEvents.BEACON_POWER_SELECT, 1.2f, pitch);
            sound(level, to, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 1.5f);
        }
    }

    private static void burst(ClientLevel level, Vec3 at, int count) {
        RandomSource rnd = level.random;
        for (int i = 0; i < count; i++) {
            level.addParticle(ParticleTypes.END_ROD, true, at.x, at.y, at.z,
                    (rnd.nextDouble() - 0.5) * 0.2, rnd.nextDouble() * 0.15, (rnd.nextDouble() - 0.5) * 0.2);
        }
    }

    private static void sound(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.MASTER, volume, pitch, false);
    }

    // ── Render di dunia ───────────────────────────────────────────────────────

    public static void render(RenderLevelStageEvent event) {
        if (holder.isEmpty() && !inspecting) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        float partial = event.getPartialTick();
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        Quaternionf facing = event.getCamera().rotation();

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);

        if (!holder.isEmpty()) renderToken(mc, pose, cam, facing, partial);
        if (inspecting && mc.level.dimension() == AvalonDimensions.AVALON) {
            renderInspect(mc, pose, cam, facing, inspectAge + partial, partial);
        }

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderToken(Minecraft mc, PoseStack pose, Vec3 cam, Quaternionf facing, float partial) {
        Vec3 c = body(mc, holder, seat, partial);
        if (c == null) return;
        float t = age + partial;

        float scale = 1f;
        float formGlow = 0f;
        if (formAt >= 0f && t - formAt < FORM_TICKS) {
            // Terbentuk: membesar dari titik cahaya
            float k = (t - formAt) / FORM_TICKS;
            scale = PortalActorAnim.backOut(Math.min(1f, k * 1.4f));
            formGlow = 1f - k;
        }
        float pulse = 0.9f + 0.1f * Mth.sin(t * 0.3f);

        // Ekor yang memudar, lalu bola air: pendar biru + inti putih
        for (int k = TOKEN_TRAIL; k >= 1; k--) {
            float tail = 1f - k / (float) (TOKEN_TRAIL + 1);
            billboard(pose, cam, facing, tokenAt(c, t - k * 0.9f), 0.2f * tail * scale, 0.30f, 0.75f, 1.0f, 0.45f * tail * tail);
        }
        Vec3 at = tokenAt(c, t);
        last = at;
        billboard(pose, cam, facing, at, (0.34f + 0.5f * formGlow) * scale * pulse, 0.25f, 0.70f, 1.0f, 0.55f + 0.4f * formGlow);
        billboard(pose, cam, facing, at, 0.12f * scale, 0.85f, 1.0f, 1.0f, 1f);

        // Tetes kecil yang mengorbit berlawanan sisi (hanya selagi token diam di pemegangnya)
        boolean flying = flyAt >= 0f && t - flyAt < FLY_TICKS;
        if (!flying) {
            Vec3 drop = orbit(c, t * 1.6f, Mth.PI).add(0, 0.25, 0);
            billboard(pose, cam, facing, drop, 0.13f * scale, 0.35f, 0.85f, 1.0f, 0.5f);
            billboard(pose, cam, facing, drop, 0.05f * scale, 1f, 1f, 1f, 0.9f);
        }
    }

    private static void renderInspect(Minecraft mc, PoseStack pose, Vec3 cam, Quaternionf facing, float t, float partial) {
        Vec3 from = body(mc, target, targetSeat, partial);
        Vec3 to = body(mc, examiner, examinerSeat, partial);
        if (from == null || to == null) return;
        Vec3 lake = floor(targetSeat, from);
        boolean known = result != AvalonNetwork.LadyInspect.HIDDEN;
        boolean evil = result == AvalonNetwork.LadyInspect.EVIL;

        float open = t >= 18f ? 1f : PortalActorAnim.backOut(t / 18f);
        // Danau menutup begitu rohnya sampai; di client si pemegang ia bertahan dengan warna kubu target
        float close = known
                ? 1f - PortalActorAnim.smooth(END - 22f, END - 2f, t)
                : 1f - PortalActorAnim.smooth(ARRIVE, ARRIVE + 25f, t);
        float tint = known ? PortalActorAnim.smooth(ARRIVE, ARRIVE + 10f, t) : 0f;
        float r = Mth.lerp(tint, 0.35f, evil ? 1.0f : 0.30f);
        float g = Mth.lerp(tint, 0.80f, evil ? 0.20f : 0.60f);
        float b = Mth.lerp(tint, 1.00f, evil ? 0.20f : 1.00f);
        float beat = 0.85f + 0.15f * Mth.sin(t * 0.35f);

        // Danau: dua lingkaran sihir berputar berlawanan + riak yang melebar
        pose.pushPose();
        pose.translate(lake.x - cam.x, lake.y - cam.y, lake.z - cam.z);
        pose.mulPose(Axis.XP.rotationDegrees(90f));
        Matrix4f ground = pose.last().pose();
        float lakeAlpha = Math.min(1f, t / 10f) * close * beat;
        PortalRenderer.quad(ground, PortalRenderer.CIRCLE, 1.6f * open, t * 2.2f, 0f, r, g, b, 0.75f * lakeAlpha);
        PortalRenderer.quad(ground, PortalRenderer.CIRCLE, 0.9f * open, -t * 4.5f, -0.01f,
                Mth.lerp(0.5f, r, 1f), Mth.lerp(0.5f, g, 1f), Mth.lerp(0.5f, b, 1f), 0.65f * lakeAlpha);
        if (t < DIVE_START) {
            for (int i = 0; i < 3; i++) {
                float phase = (t / 24f + i / 3f) % 1f;
                PortalRenderer.quad(ground, PortalRenderer.CIRCLE, (0.7f + 2.3f * phase) * open, t * 1.5f + i * 40f, -0.02f,
                        0.45f, 0.85f, 1.0f, (1f - phase) * (1f - phase) * 0.5f * lakeAlpha);
            }
        }
        pose.popPose();

        // Tiang cahaya dari danau, menipis saat rohnya pergi
        float beamAlpha = PortalActorAnim.smooth(4f, 18f, t) * (1f - PortalActorAnim.smooth(ORBIT_START, ORBIT_START + 16f, t));
        if (beamAlpha > 0.01f) {
            pose.pushPose();
            pose.translate(lake.x - cam.x, lake.y - cam.y, lake.z - cam.z);
            beam(pose.last().pose(), 0f, 4.2f, 0.55f, 0.4f * beamAlpha * beat);
            pose.popPose();
        }

        // Pendar di dada target: menguat sebelum rohnya keluar, lalu padam
        float chestGlow = PortalActorAnim.smooth(6f, DRAW_START, t) * (1f - PortalActorAnim.smooth(DRAW_START, ORBIT_START, t));
        billboard(pose, cam, facing, from, 0.5f + 0.25f * beat, 0.45f, 0.80f, 1.0f, 0.6f * chestGlow);

        // Roh: inti putih + pendar biru-air + ekor yang memudar
        for (int k = WISP_TRAIL; k >= 0; k--) {
            Vec3 p = wisp(from, to, t - k * 0.7f);
            if (p == null) continue;
            float tail = 1f - k / (float) (WISP_TRAIL + 1);
            billboard(pose, cam, facing, p, 0.5f * tail, 0.45f, 0.80f, 1.0f, 0.6f * tail * tail);
            if (k == 0) {
                billboard(pose, cam, facing, p, 0.9f, 0.30f, 0.60f, 1.0f, 0.18f);
                billboard(pose, cam, facing, p, 0.22f, 1f, 1f, 1f, 1f);
            }
        }

        // Pemegang Lady menyambut: pendar yang menguat selama roh menukik, kilatan saat masuk
        float gather = PortalActorAnim.smooth(DIVE_START - 10f, ARRIVE, t) * (t < ARRIVE ? 1f : 0f);
        billboard(pose, cam, facing, to, 0.3f + 0.4f * gather, 0.55f, 0.85f, 1.0f, 0.5f * gather);
        float since = t - ARRIVE;
        if (since >= 0f && since < 14f) {
            float flash = 1f - since / 14f;
            billboard(pose, cam, facing, to, 0.8f + 2.4f * (1f - flash), 0.85f, 0.95f, 1.0f, flash);
        }
        // Cincin di lantai kursi pemegang begitu rohnya masuk
        if (since >= 0f && since < 45f) {
            Vec3 at = floor(examinerSeat, to);
            float k = since / 45f;
            pose.pushPose();
            pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            pose.mulPose(Axis.XP.rotationDegrees(90f));
            PortalRenderer.quad(pose.last().pose(), PortalRenderer.CIRCLE, 1.0f + 1.1f * Mth.sqrt(k), t * 6f, 0f,
                    0.55f, 0.85f, 1.0f, (1f - k) * (1f - k));
            pose.popPose();
        }

        // Hanya di client si pemegang: target berpendar warna kubunya
        if (known && since >= 0f) {
            float show = PortalActorAnim.smooth(0f, 10f, since) * close;
            billboard(pose, cam, facing, from, 0.75f + 0.1f * beat, r, g, b, 0.55f * show * beat);
        }
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
            buf.vertex(m, -dx, bottom, -dz).color(0.45f, 0.8f, 1f, alpha).endVertex();
            buf.vertex(m, dx, bottom, dz).color(0.45f, 0.8f, 1f, alpha).endVertex();
            buf.vertex(m, dx, top, dz).color(0.9f, 1f, 1f, 0f).endVertex();
            buf.vertex(m, -dx, top, -dz).color(0.9f, 1f, 1f, 0f).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }

    // ── Overlay layar ─────────────────────────────────────────────────────────

    /** Layar si pemegang berkedip warna kubu yang diperiksa saat rohnya masuk. */
    public static void renderOverlay(GuiGraphics g, int width, int height, float partialTick) {
        if (!inspecting || result == AvalonNetwork.LadyInspect.HIDDEN) return;
        float since = inspectAge + partialTick - ARRIVE;
        if (since < 0f || since > 30f) return;
        float k = 1f - since / 30f;
        int alpha = (int) (k * k * 160f);
        int rgb = result == AvalonNetwork.LadyInspect.EVIL ? EVIL_RGB : GOOD_RGB;
        if (alpha > 2) g.fill(0, 0, width, height, (alpha << 24) | rgb);
    }
}

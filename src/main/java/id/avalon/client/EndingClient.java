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
import id.avalon.core.AvalonDimensions;
import id.avalon.cutscene.EndingTimeline;
import id.avalon.mixin.CameraAccessor;
import id.avalon.network.AvalonNetwork;
import id.avalon.world.AvalonPillars;
import id.avalon.world.AvalonPortal;
import id.avalon.world.AvalonSeats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;

/**
 * Cutscene akhir game di sisi client (lihat {@link EndingTimeline}): kamera sinematik, garis-garis
 * cahaya antar bola pilar, bola tengah, sinar ke portal, pusaran portal, aura, dan animasi
 * perpisahan kubu baik. Semuanya dihitung dari umur cutscene; para player sudah ditaruh server
 * di tempatnya masing-masing.
 */
public final class EndingClient {

    private EndingClient() {}

    private static final int WIN = EndingTimeline.WIN;
    private static final int EVIL = EndingTimeline.EVIL;
    private static final int MERLIN = EndingTimeline.MERLIN;

    // Gaya berpamitan
    private static final int WAVE = 0, NOD = 1, SALUTE = 2, BOTH_ARMS = 3, BOW = 4;

    private static final float[] ICE = {0.45f, 0.80f, 1.0f};
    private static final float[] VIOLET = {0.80f, 0.62f, 1.0f};
    private static final float[] RED = {1.0f, 0.22f, 0.16f};
    private static final float[] WHITE = {1.0f, 0.97f, 1.0f};

    /** Tick (sejak bola tengah mulai mengumpulkan energi) tiap dengungnya terdengar. */
    private static final int[] CHARGE_BEATS = {4, 18, 30, 40, 48, 54, 58, 61};

    /** Jeda dari panah mengenai Merlin sampai ia mulai rebah (tick). */
    private static final int MERLIN_FALL_DELAY = 6;

    private static final int LETTERBOX_TICKS = 16;
    private static final int FADE_IN_TICKS = 12;
    private static final float BOW_PITCH = 0.9f;

    /** Satu player di cutscene. */
    private record Actor(boolean good, int style, int order) {}

    /** Satu sorotan kamera. */
    private static final class Shot {
        Vec3 pos, look;
        /** Titik asal pengecekan halangan; null = dari {@link #look}. */
        Vec3 anchor;
        float fov = 65f, roll = 0f, shake = 0f;
        /** true = kamera tidak boleh menembus blok di antara dirinya dan sasarannya. */
        boolean clip = true;
    }

    private static boolean active = false;
    private static int type;
    private static int total;
    private static int goodCount;
    /** Pilar yang sedang menyala saat cutscene dimulai (indeks AvalonPillars.SITES), berurut. */
    private static int[] litPillars = new int[0];
    private static int merlinId = -1;
    private static int assassinId = -1;
    /** Panah untuk reka ulang tembakan: entity bayangan, hanya dipakai untuk digambar. */
    private static Arrow arrow;
    private static final Map<Integer, Actor> actors = new HashMap<>();
    private static int age;
    private static float partial;
    /** Umur saat player terakhir masuk portal (kilatan). */
    private static float pulseAt = -100f;
    /** Tick sejak cutscene berakhir, untuk memudar dari layar putih / gelap; -1 = tidak ada. */
    private static int outro = -1;

    // ── Mulai / berhenti ──────────────────────────────────────────────────────

    public static void start(AvalonNetwork.EndingStart msg) {
        PortalRenderer.prepareTextures();
        actors.clear();
        for (AvalonNetwork.EndingActor a : msg.actors()) {
            actors.put(a.entityId(), new Actor(a.good(), a.style(), a.order()));
        }
        type = msg.type();
        total = msg.total();
        goodCount = msg.goodCount();
        litPillars = msg.litPillars().stream().mapToInt(Integer::intValue)
                .filter(i -> i >= 0 && i < AvalonPillars.SITES.size()).toArray();
        restoreMerlin();
        merlinId = msg.merlinId();
        assassinId = msg.assassinId();
        arrow = null;
        age = 0;
        pulseAt = -100f;
        outro = -1;
        active = true;
    }

    /** Cutscene selesai: layar memudar kembali ke permainan. */
    public static void stop() {
        if (active) outro = 0;
        active = false;
        actors.clear();
        restoreMerlin();
    }

    public static void reset() {
        active = false;
        outro = -1;
        actors.clear();
        restoreMerlin();
    }

    /** Merlin yang ditumbangkan di client (rebah, panah menancap) dikembalikan seperti semula. */
    private static void restoreMerlin() {
        Minecraft mc = Minecraft.getInstance();
        if (merlinId >= 0 && mc.level != null && mc.level.getEntity(merlinId) instanceof LivingEntity merlin) {
            merlin.deathTime = 0;
            merlin.hurtTime = 0;
            merlin.setArrowCount(0);
        }
        merlinId = -1;
        arrow = null;
    }

    public static boolean hasCamera() {
        return active;
    }

    public static void setPartialTick(float partialTick) {
        partial = partialTick;
    }

    private static float time() {
        return age + partial;
    }

    public static boolean isActor(Entity entity) {
        return active && actors.containsKey(entity.getId());
    }

    // ── Geometri ──────────────────────────────────────────────────────────────

    private static Vec3 orb(int site) {
        BlockPos p = AvalonPillars.SITES.get(site).pillarPos();
        return new Vec3(p.getX() + 0.5, p.getY() + PillarBlock.ORB_HEIGHT + 0.5, p.getZ() + 0.5);
    }

    /** Bola di bawah lantai barrier. */
    private static Vec3 core() {
        BlockPos c = AvalonSeats.CENTER;
        return new Vec3(c.getX() + 0.5, c.getY() - 5.5, c.getZ() + 0.5);
    }

    /**
     * Titik kumpul energi: melayang di tengah lingkaran kursi, di atas lantai barrier. Garis dari
     * tiga pilar bertemu di sini (bukan di dalam bola blok di bawah lantai, yang menutupi cahayanya).
     */
    private static Vec3 focus() {
        BlockPos c = AvalonSeats.CENTER;
        return new Vec3(c.getX() + 0.5, c.getY() + 3.0, c.getZ() + 0.5);
    }

    private static Vec3 gate() {
        return AvalonPortal.GATE_CENTER;
    }

    /** Tick player baik ini mulai berpamitan, dihitung dari umur cutscene. */
    private static float farewellTime(Actor a) {
        return time() - EndingTimeline.farewellAt(a.order);
    }

    // ── Tick: suara & partikel ────────────────────────────────────────────────

    public static void tick() {
        if (!active) {
            if (outro >= 0 && ++outro > EndingTimeline.FADE_TICKS) outro = -1;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level.dimension() != AvalonDimensions.AVALON) {
            reset();
            return;
        }
        if (mc.isPaused()) return;
        // Pengaman kalau paket penutup tidak pernah datang
        if (++age > total + 100) {
            stop();
            return;
        }

        if (type == WIN) {
            tickWin(level);
        } else {
            if (type == MERLIN) tickMerlinIntro(level);
            tickLose();
        }
    }

    private static void tickWin(ClientLevel level) {
        for (int k = 0; k < 3; k++) {
            if (age == EndingTimeline.WIN_EDGE_START + k * EndingTimeline.WIN_EDGE_GAP) {
                ui(SoundEvents.BEACON_POWER_SELECT, 0.8f + k * 0.15f, 0.8f);
            }
        }
        if (age == EndingTimeline.WIN_CORE_START) ui(SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.7f, 1f);
        // Bola tengah mengumpulkan energi: dengung yang makin rapat & makin tinggi
        int charging = age - EndingTimeline.WIN_CHARGE_START;
        if (charging == 0) ui(SoundEvents.BEACON_ACTIVATE, 0.6f, 1f);
        for (int beat : CHARGE_BEATS) {
            if (charging == beat) ui(SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.6f + beat * 0.016f, 0.8f);
        }
        if (charging >= 0 && age < EndingTimeline.WIN_BEAM_START) {
            // Percikan dari segala arah tersedot ke bola energi, makin ramai selagi energinya penuh
            RandomSource spark = level.random;
            Vec3 f = focus();
            for (int i = 0; i < 1 + charging / 14; i++) {
                Vec3 dir = new Vec3(spark.nextDouble() - 0.5, spark.nextDouble() - 0.3, spark.nextDouble() - 0.5).normalize();
                double far = 4.0 + spark.nextDouble() * 3.0;
                // END_ROD melambat (gesekan 0.91), jadi kecepatan awal ~9% jarak supaya sampai di tengah
                level.addParticle(ParticleTypes.END_ROD, true, f.x + dir.x * far, f.y + dir.y * far, f.z + dir.z * far,
                        -dir.x * far * 0.09, -dir.y * far * 0.09, -dir.z * far * 0.09);
            }
        }
        if (age == EndingTimeline.WIN_BEAM_START) ui(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.7f, 0.6f);
        if (age == EndingTimeline.WIN_SHATTER_START) ui(SoundEvents.GLASS_BREAK, 0.6f, 1f);
        if (age == EndingTimeline.WIN_GATE_OPEN_START) {
            ui(SoundEvents.END_PORTAL_SPAWN, 1.3f, 0.6f);
            ui(SoundEvents.PORTAL_TRIGGER, 0.7f, 0.8f);
        }
        if (age == EndingTimeline.winCloseStart(goodCount)) ui(SoundEvents.BEACON_DEACTIVATE, 0.6f, 1f);

        // Kubu baik berpamitan & masuk portal bersamaan: suara & kilatan cukup sekali,
        // letupan partikel di tempat tiap player masuk
        RandomSource rnd = level.random;
        int lt = age - EndingTimeline.farewellAt(0);
        if (lt == EndingTimeline.ACTOR_TURN_END) ui(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 0.8f);
        if (lt == EndingTimeline.ACTOR_BACK_END) ui(SoundEvents.TRIDENT_RIPTIDE_1, 1.3f, 0.5f);
        if (lt == EndingTimeline.ACTOR_TICKS) {
            pulseAt = age;
            ui(SoundEvents.CHORUS_FRUIT_TELEPORT, 0.8f, 0.9f);
            Vec3 e = AvalonPortal.GATE_ENTRY;
            level.addParticle(ParticleTypes.FLASH, true, e.x, e.y + 1.0, e.z, 0, 0, 0);
            for (Map.Entry<Integer, Actor> entry : actors.entrySet()) {
                if (!entry.getValue().good) continue;
                Entity entity = level.getEntity(entry.getKey());
                double x = entity == null ? e.x : entity.getX();
                for (int i = 0; i < 14; i++) {
                    level.addParticle(ParticleTypes.END_ROD, true, x, e.y + 1.0, e.z,
                            (rnd.nextDouble() - 0.5) * 0.3, rnd.nextDouble() * 0.25, (rnd.nextDouble() - 0.5) * 0.3);
                }
            }
        }
    }

    /** Ending kalah (dan lanjutan ending Merlin): bola pilar yang menyala meledak, lalu kubu jahat. */
    private static void tickLose() {
        for (int order = 0; order < litPillars.length; order++) {
            // Bolanya memerah & tidak stabil, lalu meledak
            if (age == EndingTimeline.evilOverload(type, order)) ui(SoundEvents.WARDEN_SONIC_CHARGE, 0.7f + order * 0.1f, 0.9f);
            if (age == EndingTimeline.evilBurst(type, order)) {
                ui(SoundEvents.WARDEN_SONIC_BOOM, 0.8f, 1f);
                ui(SoundEvents.GENERIC_EXPLODE, 0.7f + order * 0.1f, 0.9f);
                ui(SoundEvents.GLASS_BREAK, 0.5f, 1f);
            }
        }
        if (age == EndingTimeline.evilGroupStart(type, litPillars.length) + 1) ui(SoundEvents.WITHER_SPAWN, 1.2f, 0.5f);
    }

    /** Reka ulang Merlin tertembak: busur ditarik, panah dilepas, Merlin kena lalu tumbang. */
    private static void tickMerlinIntro(ClientLevel level) {
        if (age == EndingTimeline.MERLIN_DRAW_START) ui(SoundEvents.CROSSBOW_LOADING_MIDDLE, 0.8f, 0.9f);
        if (age == EndingTimeline.MERLIN_RELEASE) ui(SoundEvents.ARROW_SHOOT, 0.9f, 1f);

        if (!(level.getEntity(merlinId) instanceof LivingEntity merlin)) return;
        int hit = age - EndingTimeline.MERLIN_HIT;
        if (hit == 0) {
            ui(SoundEvents.ARROW_HIT_PLAYER, 0.8f, 1f);
            ui(SoundEvents.PLAYER_HURT, 0.9f, 1f);
            // Kilat merah kena pukul + panah menancap di badannya (keduanya milik vanilla)
            merlin.hurtDuration = 10;
            merlin.hurtTime = 10;
            merlin.setArrowCount(1);
            RandomSource rnd = level.random;
            for (int i = 0; i < 24; i++) {
                level.addParticle(i % 3 == 0 ? ParticleTypes.DAMAGE_INDICATOR : ParticleTypes.CRIT, true,
                        merlin.getX(), merlin.getY() + 1.0, merlin.getZ(),
                        (rnd.nextDouble() - 0.5) * 0.5, rnd.nextDouble() * 0.4, (rnd.nextDouble() - 0.5) * 0.5);
            }
        }
        if (hit == MERLIN_FALL_DELAY) ui(SoundEvents.PLAYER_DEATH, 0.9f, 0.9f);
        // Rebah seperti animasi mati vanilla, lalu tetap tergeletak sampai cutscene selesai
        if (hit >= MERLIN_FALL_DELAY) merlin.deathTime = Mth.clamp(hit - MERLIN_FALL_DELAY, 1, 20);
    }

    /** Suara sinematik: terdengar sama keras di mana pun kameranya. */
    private static void ui(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    // ── Kamera ────────────────────────────────────────────────────────────────

    /** Pasang kamera cutscene; false = tidak ada cutscene untuk client ini. */
    public static boolean applyCamera(ViewportEvent.ComputeCameraAngles event) {
        if (!active) return false;
        Shot shot = shot();
        Vec3 pos = shot.clip ? unobstructed(shot.anchor != null ? shot.anchor : shot.look, shot.pos) : shot.pos;

        float t = time();
        if (shot.shake > 0f) {
            pos = pos.add(Mth.sin(t * 2.9f) * shot.shake, Mth.sin(t * 3.7f + 1f) * shot.shake, Mth.sin(t * 4.3f + 2f) * shot.shake);
        }
        Vec3 dir = shot.look.subtract(pos);
        float yaw = (float) Math.toDegrees(Mth.atan2(-dir.x, dir.z));
        float pitch = (float) -Math.toDegrees(Mth.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z)));

        CameraAccessor camera = (CameraAccessor) event.getCamera();
        camera.avalon$setPosition(pos.x, pos.y, pos.z);
        camera.avalon$setRotation(yaw, pitch);
        camera.avalon$setDetached(true);
        event.setYaw(yaw);
        event.setPitch(pitch);
        event.setRoll(shot.roll + Mth.sin(t * 3.3f) * shot.shake * 6f);
        return true;
    }

    public static double fov() {
        return shot().fov;
    }

    private static Shot shot() {
        float t = time();
        return switch (type) {
            case WIN -> {
                if (t < EndingTimeline.WIN_CORE_START) {
                    yield followEdges(t, EndingTimeline.WIN_EDGE_START, EndingTimeline.WIN_EDGE_GAP, EndingTimeline.WIN_EDGE_TICKS);
                }
                if (t < EndingTimeline.WIN_CHARGE_START) yield coreFront();
                if (t < EndingTimeline.WIN_SHATTER_START) yield chargeShot(t);
                if (t < EndingTimeline.FAREWELL_START) yield gateFront(t);
                yield farewellShot(t);
            }
            default -> {
                // Ending Merlin: reka ulang tembakannya dulu, lalu sama seperti ending kalah
                float lose = t - EndingTimeline.loseStart(type);
                if (lose < 0f) yield merlinIntroShot(t);
                // Tidak ada pilar menyala: langsung ke sorotan kubu jahat
                float group = lose - litPillars.length * EndingTimeline.EVIL_ORB_TICKS;
                if (group < 0f) yield orbShot(lose);
                yield groupShot(group, EndingTimeline.EVIL_GROUP_TICKS);
            }
        };
    }

    /** Ujung garis segitiga yang sedang ditarik pada waktu {@code t}: bola 0 → 1 → 2 → kembali ke 0. */
    private static Vec3 edgeHead(float t, int start, int gap, int ticks) {
        float local = t - start;
        if (local <= 0f) return orb(0);
        int edge = Math.min(2, (int) (local / gap));
        float grow = Mth.clamp((local - edge * gap) / ticks, 0f, 1f);
        return orb(edge).lerp(orb((edge + 1) % 3), grow);
    }

    /**
     * Terbang mengikuti ujung garis: kamera sedikit di belakang, di atas, dan di sisi luar segitiga,
     * memandang ke depan sepanjang garis. Karena kamera mengikuti jejak ujungnya dengan jeda,
     * tikungan di tiap bola terpotong mulus.
     */
    private static Shot followEdges(float t, int start, int gap, int ticks) {
        Vec3 behind = edgeHead(t - 11f, start, gap, ticks);
        Vec3 ahead = edgeHead(t + 3f, start, gap, ticks);
        Vec3 mid = orb(0).add(orb(1)).add(orb(2)).scale(1.0 / 3.0);
        return follow(behind, ahead, mid, 7.5, 7.0);
    }

    /**
     * Di depan titik kumpul energi sebelum energinya mulai terkumpul: ketiga garis dari pilar terlihat
     * datang dan bertemu. Sama dengan awal {@link #chargeShot}, jadi sorotan berikutnya menyambung.
     */
    private static Shot coreFront() {
        return chargeShot(EndingTimeline.WIN_CHARGE_START);
    }

    private static Shot follow(Vec3 behind, Vec3 ahead, Vec3 inside, double up, double out) {
        Vec3 away = new Vec3(behind.x - inside.x, 0, behind.z - inside.z);
        away = away.lengthSqr() < 1.0 ? new Vec3(1, 0, 0) : away.normalize();
        Shot s = new Shot();
        s.pos = behind.add(away.scale(out)).add(0, up, 0);
        s.look = ahead;
        s.fov = 72f;
        s.clip = false;
        return s;
    }

    /** Dari depan cincin portal, agak rendah: bola kaca pecah dan portal menyebar memenuhi cincin. */
    private static Shot gateFront(float t) {
        Shot s = new Shot();
        Vec3 g = gate();
        float since = t - EndingTimeline.WIN_SHATTER_START;
        s.pos = g.add(6.0 - since * 0.03, -7.5, -27.0 + since * 0.04);
        s.look = g;
        // Bola kaca ada tepat di titik pandang: cek halangan dari depan bolanya, bukan dari dalamnya
        s.anchor = g.add(0, 0, -8.0);
        s.fov = 68f;
        // Terguncang saat bola kacanya pecah
        s.shake = 0.3f * Math.max(0f, 1f - Math.abs(since - 4f) / 10f);
        return s;
    }

    /**
     * Dekat di depan bola pilar yang menyala: bolanya memerah, meledak, kamera tersentak mundur,
     * lalu pindah ke bola menyala berikutnya.
     */
    private static Shot orbShot(float t) {
        // Satu bola menyala per sorotan: dari sebelum ia memerah sampai sesaat setelah ledakannya
        int order = Mth.clamp((int) (t / EndingTimeline.EVIL_ORB_TICKS), 0, litPillars.length - 1);
        float since = t - order * EndingTimeline.EVIL_ORB_TICKS - EndingTimeline.EVIL_BURST_AT;
        float blast = PortalActorAnim.smooth(0f, 14f, since);
        float jolt = since < 0f ? 0f : Math.max(0f, 1f - since / 16f);

        Vec3 o = orb(litPillars[order]);
        Vec3 mid = orb(0).add(orb(1)).add(orb(2)).scale(1.0 / 3.0);
        Vec3 out = new Vec3(o.x - mid.x, 0, o.z - mid.z).normalize();
        Vec3 side = new Vec3(-out.z, 0, out.x);

        Shot s = new Shot();
        // Mendekat pelan selagi bolanya makin tidak stabil, terdorong mundur saat meledak
        double distance = 13.0 + since * 0.04 + 5.0 * blast;
        s.pos = o.add(out.scale(distance)).add(side.scale(4.0)).add(0, 2.5 + blast, 0);
        s.look = o;
        s.fov = 60f;
        s.shake = 0.03f * Mth.clamp(1f + since / 40f, 0f, 1f) + 0.5f * jolt * jolt;
        s.clip = false;
        return s;
    }

    /**
     * Setinggi mata, dari tepi lingkaran kursi: bola energi di depan, cincin portal di belakangnya.
     * Kamera mendekat pelan selagi energinya terkumpul; saat sinarnya melesat, pandangan ikut
     * terangkat ke portal dalam sorotan yang sama.
     */
    private static Shot chargeShot(float t) {
        float since = t - EndingTimeline.WIN_CHARGE_START;
        float charge = Mth.clamp(since / EndingTimeline.WIN_CHARGE_TICKS, 0f, 1f);
        float fire = PortalActorAnim.smooth(EndingTimeline.WIN_BEAM_START,
                EndingTimeline.WIN_BEAM_START + EndingTimeline.WIN_BEAM_TICKS, t);
        Shot s = new Shot();
        Vec3 f = focus();
        // Sedikit di atas batu-batu di bibir lubang, supaya tidak menghalangi pandangan
        s.pos = f.add(3.5 - 1.5 * charge, 0.6, -13.0 + 2.5 * charge);
        s.look = f.lerp(gate(), 0.12 + 0.4 * fire);
        s.anchor = f;
        s.fov = Mth.lerp(charge, 70f, 64f) + 8f * fire;
        // Bergetar makin kuat menjelang tembakan, lalu tersentak saat sinarnya lepas
        float kick = Math.max(0f, 1f - Math.abs(t - EndingTimeline.WIN_BEAM_START - 2f) / 8f);
        s.shake = 0.1f * charge * charge * (1f - fire) + 0.3f * kick;
        return s;
    }

    /**
     * Dari belakang kubu jahat, setinggi mata, lurus ke tangga: kubu jahat melambai di depan,
     * kubu baik di tangga menghadap mereka, cincin portal menjulang memenuhi layar.
     */
    private static Shot farewellShot(float t) {
        Shot s = new Shot();
        float span = Math.max(1f, total - EndingTimeline.FAREWELL_START);
        float k = PortalActorAnim.smooth(0f, span, t - EndingTimeline.FAREWELL_START);
        Vec3 e = AvalonPortal.GATE_ENTRY;
        s.pos = new Vec3(e.x + 0.5, e.y + Mth.lerp(k, -5.0, -4.7), e.z + Mth.lerp(k, -25.8, -23.3));
        s.look = new Vec3(e.x, e.y + 4.5, e.z - 1.8);
        s.fov = 70f;
        return s;
    }

    /** Mengitari kubu jahat dari depan (dari arah kursi), rendah, mendongak. */
    private static Shot groupShot(float since, float span) {
        Shot s = new Shot();
        float k = Mth.clamp(since / Math.max(1f, span), 0f, 1f);
        float angle = Mth.lerp(k, 40f, 12f) * Mth.DEG_TO_RAD;
        Vec3 group = evilGroup();
        // Dari sisi kursi (utara), tempat kubu baik duduk: wajah kubu jahat terlihat, portal di belakang mereka
        s.pos = group.add(Mth.sin(angle) * 6.5, 0.5, -Mth.cos(angle) * 6.5);
        s.look = group.add(0, 1.2, 0);
        s.fov = 58f;
        return s;
    }

    /** Posisi assassin (kaki); kalau tidak ada, titik tengah kubu jahat. */
    private static Vec3 archerPos() {
        Minecraft mc = Minecraft.getInstance();
        Entity archer = mc.level == null ? null : mc.level.getEntity(assassinId);
        return archer != null ? archer.position() : evilGroup();
    }

    /** Arah mendatar dari assassin ke Merlin. */
    private static Vec3 shotDir() {
        Vec3 a = archerPos(), m = merlinPos();
        Vec3 flat = new Vec3(m.x - a.x, 0, m.z - a.z);
        return flat.lengthSqr() < 0.01 ? new Vec3(0, 0, -1) : flat.normalize();
    }

    private static Vec3 arrowStart() {
        return archerPos().add(shotDir().scale(0.7)).add(0, 1.45, 0);
    }

    /** Dada Merlin yang sedang duduk. */
    private static Vec3 arrowEnd() {
        return merlinPos().add(0, 1.0, 0);
    }

    /** Posisi panah yang sedang terbang (melambung sedikit). */
    private static Vec3 arrowPos(float t) {
        float k = Mth.clamp((t - EndingTimeline.MERLIN_RELEASE) / EndingTimeline.MERLIN_FLIGHT_TICKS, 0f, 1f);
        return arrowStart().lerp(arrowEnd(), k).add(0, Mth.sin(k * Mth.PI) * 0.35, 0);
    }

    /**
     * Reka ulang Merlin tertembak, tiga sorotan: assassin menarik busur (dekat, dari depan-samping),
     * panah terbang diikuti dari belakang, lalu Merlin dari depan saat kena dan tumbang.
     */
    private static Shot merlinIntroShot(float t) {
        Vec3 a = archerPos(), m = merlinPos();
        Vec3 dir = shotDir();
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        Shot s = new Shot();
        if (t < EndingTimeline.MERLIN_RELEASE) {
            s.pos = a.add(dir.scale(2.7 - t * 0.008)).add(side.scale(1.5)).add(0, 1.55, 0);
            s.look = a.add(0, 1.45, 0);
            s.fov = 50f;
        } else if (t < EndingTimeline.MERLIN_HIT) {
            Vec3 p = arrowPos(t);
            s.pos = p.subtract(dir.scale(2.8)).add(side.scale(0.7)).add(0, 0.45, 0);
            s.look = p.add(dir.scale(5.0));
            s.fov = 62f;
            s.clip = false;
        } else {
            float hit = t - EndingTimeline.MERLIN_HIT;
            s.pos = m.subtract(dir.scale(3.4 - hit * 0.01)).add(side.scale(1.4)).add(0, 1.3, 0);
            s.look = m.add(0, 0.9, 0);
            s.fov = 48f;
            s.shake = 0.3f * Math.max(0f, 1f - hit / 12f);
        }
        return s;
    }

    /** Titik tengah kubu jahat (rata-rata posisinya), setinggi kaki. */
    private static Vec3 evilGroup() {
        Minecraft mc = Minecraft.getInstance();
        double x = 0, y = 0, z = 0;
        int n = 0;
        if (mc.level != null) {
            for (Map.Entry<Integer, Actor> e : actors.entrySet()) {
                if (e.getValue().good) continue;
                Entity entity = mc.level.getEntity(e.getKey());
                if (entity == null) continue;
                x += entity.getX();
                y += entity.getY();
                z += entity.getZ();
                n++;
            }
        }
        if (n == 0) return new Vec3(gate().x, AvalonSeats.CENTER.getY(), -493.5);
        return new Vec3(x / n, y / n, z / n);
    }

    private static Vec3 merlinPos() {
        Minecraft mc = Minecraft.getInstance();
        Entity merlin = mc.level == null ? null : mc.level.getEntity(merlinId);
        if (merlin != null) return merlin.position();
        BlockPos c = AvalonSeats.CENTER;
        return new Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
    }

    /** Kalau ada blok di antara sasaran dan kamera, majukan kamera ke depan blok itu. */
    private static Vec3 unobstructed(Vec3 anchor, Vec3 pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return pos;
        HitResult hit = mc.level.clip(new ClipContext(anchor, pos,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) return pos;
        return hit.getLocation().add(anchor.subtract(pos).normalize().scale(0.4));
    }

    // ── Render di dunia ───────────────────────────────────────────────────────

    public static void render(RenderLevelStageEvent event) {
        if (!active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        float t = time();
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        Quaternionf facing = event.getCamera().rotation();

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);

        if (type == WIN) {
            renderWin(pose, cam, facing, t);
        } else {
            renderLose(pose, cam, facing, t);
        }

        gateGlow(pose, cam, facing, t);

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        // Panah memakai render type entity sendiri, jadi digambar setelah state di atas dikembalikan
        if (type == MERLIN) renderArrow(pose, cam, t);
    }

    /** Panah yang terbang dari assassin ke Merlin (model panah vanilla). */
    private static void renderArrow(PoseStack pose, Vec3 cam, float t) {
        if (t < EndingTimeline.MERLIN_RELEASE || t >= EndingTimeline.MERLIN_HIT) return;
        Minecraft mc = Minecraft.getInstance();
        if (arrow == null) arrow = new Arrow(mc.level, 0, 0, 0);

        Vec3 at = arrowPos(t);
        Vec3 heading = arrowPos(t + 1f).subtract(at);
        // Konvensi proyektil vanilla: yaw dari atan2(x, z), pitch dari kemiringan naik
        float yaw = (float) (Mth.atan2(heading.x, heading.z) * Mth.RAD_TO_DEG);
        float pitch = (float) (Mth.atan2(heading.y, Math.sqrt(heading.x * heading.x + heading.z * heading.z)) * Mth.RAD_TO_DEG);
        arrow.setYRot(yaw);
        arrow.yRotO = yaw;
        arrow.setXRot(pitch);
        arrow.xRotO = pitch;

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        mc.getEntityRenderDispatcher().render(arrow, at.x - cam.x, at.y - cam.y, at.z - cam.z, yaw, partial,
                pose, buffers, LightTexture.FULL_BRIGHT);
        buffers.endBatch();
    }

    private static void renderWin(PoseStack pose, Vec3 cam, Quaternionf facing, float t) {
        // Semua cahaya meredup bersama portal yang menutup
        float closing = 1f - PortalActorAnim.smooth(EndingTimeline.winCloseStart(goodCount),
                EndingTimeline.winCloseStart(goodCount) + EndingTimeline.WIN_CLOSE_TICKS, t);
        float beat = 0.85f + 0.15f * Mth.sin(t * 0.35f);

        // Tiga bola berdenyut bersama
        for (int i = 0; i < 3; i++) {
            billboard(pose, cam, facing, orb(i), 5f + 1.5f * beat, ICE, 0.35f * closing * PortalActorAnim.smooth(0f, 25f, t));
        }
        // Sisi-sisi segitiga, satu per satu
        for (int k = 0; k < 3; k++) {
            float grow = (t - EndingTimeline.WIN_EDGE_START - k * EndingTimeline.WIN_EDGE_GAP) / EndingTimeline.WIN_EDGE_TICKS;
            line(pose, cam, facing, orb(k), orb((k + 1) % 3), grow, 0.9f, ICE, closing, t, 0f);
        }
        // Dari tiga bola pilar ke titik kumpul di atas lantai: biru yang sama dengan sisi segitiga
        Vec3 f = focus();
        float coreGrow = (t - EndingTimeline.WIN_CORE_START) / EndingTimeline.WIN_CORE_TICKS;
        for (int i = 0; i < 3; i++) {
            line(pose, cam, facing, orb(i), f, coreGrow, 0.6f, ICE, closing, t, 0f);
        }

        // Energinya terkumpul: bola cahaya yang membesar, berdenyut makin cepat, berubah dari biru ke ungu
        float arrived = PortalActorAnim.smooth(EndingTimeline.WIN_CHARGE_START - 8f, EndingTimeline.WIN_CHARGE_START + 4f, t);
        float charge = Mth.clamp((t - EndingTimeline.WIN_CHARGE_START) / EndingTimeline.WIN_CHARGE_TICKS, 0f, 1f);
        float fired = PortalActorAnim.smooth(EndingTimeline.WIN_BEAM_START, EndingTimeline.WIN_BEAM_START + 10f, t);
        float[] hue = mix(ICE, VIOLET, PortalActorAnim.smooth(0.15f, 0.9f, charge));
        float throb = 0.8f + 0.2f * Mth.sin(t * (0.35f + 1.4f * charge));
        float shown = arrived * closing;

        // Sumbernya: bola blok di bawah lantai ikut menyala, energinya naik tipis ke titik kumpul
        billboard(pose, cam, facing, core(), 6f + 3f * charge, hue, 0.35f * shown * throb);
        line(pose, cam, facing, core(), f, 1f, 0.25f + 0.2f * charge, hue, 0.5f * shown * (1f - 0.5f * fired), t, 0f);

        // Bola energi: pendar lebar, badan berwarna, inti putih panas. Setelah menembak ia mengecil
        // dan terus menyuplai portal sampai portalnya menutup.
        float size = (0.9f + 2.6f * charge) * (1f - 0.55f * fired);
        billboard(pose, cam, facing, f, size * 3.2f, hue, 0.30f * shown * throb);
        billboard(pose, cam, facing, f, size * 1.6f, hue, 0.75f * shown * throb);
        billboard(pose, cam, facing, f, size * 0.7f, WHITE, 0.95f * shown);

        // Semburat sinar yang berputar pelan dari bola, makin panjang selagi energinya penuh
        float rays = charge * shown * (1f - fired);
        if (rays > 0.01f) {
            for (int j = 0; j < 6; j++) {
                float around = j * Mth.TWO_PI / 6f + t * 0.03f;
                float tilt = Mth.sin(j * 2.1f + t * 0.02f) * 0.9f;
                Vec3 dir = new Vec3(Mth.cos(around) * Mth.cos(tilt), Mth.sin(tilt), Mth.sin(around) * Mth.cos(tilt));
                double length = size * (1.3 + 0.5 * Mth.sin(t * 0.9f + j));
                line(pose, cam, facing, f, f.add(dir.scale(length)), 1f, 0.1f + 0.06f * charge, hue, 0.45f * rays, t, 0f);
            }
        }

        // Kilatan saat sinarnya lepas, lalu sinar ungu melesat ke bola kaca di cincin portal
        float flash = Mth.clamp(1f - Math.abs(t - EndingTimeline.WIN_BEAM_START - 1f) / 7f, 0f, 1f);
        billboard(pose, cam, facing, f, 4f + 10f * flash, WHITE, flash * closing);
        float beam = (t - EndingTimeline.WIN_BEAM_START) / EndingTimeline.WIN_BEAM_TICKS;
        line(pose, cam, facing, f, gate(), beam, 1.3f, VIOLET, closing, t, 0f);
    }

    /** Ending kalah & lanjutan ending Merlin. */
    private static void renderLose(PoseStack pose, Vec3 cam, Quaternionf facing, float t) {
        // Jejak tipis di belakang panah yang terbang
        if (type == MERLIN && t >= EndingTimeline.MERLIN_RELEASE && t < EndingTimeline.MERLIN_HIT) {
            Vec3 head = arrowPos(t);
            line(pose, cam, facing, arrowPos(t - 4f), head, 1f, 0.06f, WHITE, 0.5f, t, 0f);
        }
        // Bola pilarnya sendiri (memerah & pecah) digambar PillarOrbRenderer; di sini tinggal kilatan
        // ledakannya dan aura kubu jahat di sorotan terakhir.
        for (int order = 0; order < litPillars.length; order++) {
            float burst = Mth.clamp(1f - Math.abs(t - EndingTimeline.evilBurst(type, order) - 2f) / 9f, 0f, 1f);
            Vec3 o = orb(litPillars[order]);
            billboard(pose, cam, facing, o, 6f + 16f * burst, RED, 0.9f * burst);
            billboard(pose, cam, facing, o, 3f + 8f * burst, WHITE, burst);
        }
        int groupStart = EndingTimeline.evilGroupStart(type, litPillars.length);
        auras(pose, cam, t, PortalActorAnim.smooth(groupStart - 10f, groupStart + 20f, t));
    }

    /**
     * Cahaya di cincin portal raksasa. Portalnya sendiri blok (dipasang server, lihat AvalonGate);
     * di sini hanya pendar, kilatan saat bola kaca pecah / meledak, dan kilatan saat ada yang masuk.
     */
    private static void gateGlow(PoseStack pose, Vec3 cam, Quaternionf facing, float t) {
        if (type == WIN) {
            float open = PortalActorAnim.smooth(EndingTimeline.WIN_GATE_OPEN_START, EndingTimeline.WIN_GATE_OPEN_END, t)
                    * (1f - PortalActorAnim.smooth(EndingTimeline.winCloseStart(goodCount),
                            EndingTimeline.winCloseStart(goodCount) + EndingTimeline.WIN_CLOSE_TICKS, t));
            float burst = Mth.clamp(1f - Math.abs(t - EndingTimeline.WIN_SHATTER_START - 3f) / 9f, 0f, 1f);
            float pulse = Mth.clamp(1f - (t - pulseAt) / 12f, 0f, 1f);
            billboard(pose, cam, facing, gate(), 16f, VIOLET, 0.22f * open);
            billboard(pose, cam, facing, gate(), 6f + 10f * burst, new float[]{1f, 0.95f, 1f}, 0.9f * burst);
            billboard(pose, cam, facing, AvalonPortal.GATE_ENTRY.add(0, 1.2, 0), 2.5f + 2.5f * pulse, VIOLET, 0.8f * pulse * pulse);
        }
    }

    /** Aura merah semua kubu jahat. */
    private static void auras(PoseStack pose, Vec3 cam, float t, float alpha) {
        if (alpha <= 0.01f) return;
        Minecraft mc = Minecraft.getInstance();
        for (Map.Entry<Integer, Actor> e : actors.entrySet()) {
            if (e.getValue().good) continue;
            Entity entity = mc.level.getEntity(e.getKey());
            if (entity != null) aura(pose, cam, entity, t, alpha, true);
        }
    }

    private static void aura(PoseStack pose, Vec3 cam, Entity e, float t, float alpha, boolean red) {
        if (alpha <= 0.01f) return;
        double x = Mth.lerp(partial, e.xOld, e.getX());
        double y = Mth.lerp(partial, e.yOld, e.getY());
        double z = Mth.lerp(partial, e.zOld, e.getZ());
        pose.pushPose();
        pose.translate(x - cam.x, y - cam.y, z - cam.z);
        pose.mulPose(Axis.YP.rotation((float) Mth.atan2(cam.x - x, cam.z - z)));
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RevealClient.aura(pose.last().pose(), t + (e.getId() * 0.618f) % 1f * 40f, alpha, red);
        pose.popPose();
    }

    private static float[] mix(float[] a, float[] b, float k) {
        return new float[]{Mth.lerp(k, a[0], b[0]), Mth.lerp(k, a[1], b[1]), Mth.lerp(k, a[2], b[2])};
    }

    private static void billboard(PoseStack pose, Vec3 cam, Quaternionf facing, Vec3 at, float half, float[] rgb, float alpha) {
        if (alpha <= 0.005f) return;
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        pose.mulPose(facing);
        PortalRenderer.quad(pose.last().pose(), PortalRenderer.MOTE, half, 0f, 0f, rgb[0], rgb[1], rgb[2], alpha);
        pose.popPose();
    }

    /**
     * Garis cahaya dari {@code from} ke {@code to} yang selalu menghadap kamera: pendar lebar berwarna
     * dengan inti putih, denyut energi yang berlari di sepanjangnya, dan titik terang di ujung yang
     * sedang tumbuh.
     *
     * @param grow   seberapa jauh garis sudah ditarik (0..1; di luar itu dijepit)
     * @param broken 0 = utuh; makin besar makin banyak ruas yang putus & bergeser (garis runtuh)
     */
    private static void line(PoseStack pose, Vec3 cam, Quaternionf facing, Vec3 from, Vec3 to, float grow,
                             float half, float[] rgb, float alpha, float t, float broken) {
        grow = Mth.clamp(grow, 0f, 1f);
        if (grow <= 0f || alpha <= 0.01f) return;
        Vec3 end = from.lerp(to, grow);
        Vec3 dir = end.subtract(from);
        Vec3 side = dir.cross(from.lerp(end, 0.5).subtract(cam));
        if (side.lengthSqr() < 1.0e-6) return;
        side = side.normalize();

        int segments = Math.max(4, (int) (24 * grow));
        Matrix4f m = pose.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < segments; i++) {
            // Ruas yang putus berkedip hilang & bergeser dari garisnya
            float hash = Mth.frac(Mth.sin(i * 12.9898f + Mth.floor(t * 0.5f) * 78.233f) * 43758.547f);
            if (hash < broken) continue;
            Vec3 shift = broken <= 0f ? Vec3.ZERO : side.scale((hash - 0.5) * broken * 3.0);

            Vec3 a = from.add(dir.scale(i / (double) segments)).add(shift);
            Vec3 b = from.add(dir.scale((i + 1) / (double) segments)).add(shift);
            float pulse = 0.75f + 0.25f * Mth.sin(i * 0.9f - t * 0.6f);

            // Pendar lebar, lalu inti putih yang sempit
            strip(buf, m, cam, a, b, side.scale(half), rgb[0], rgb[1], rgb[2], 0.38f * alpha * pulse);
            strip(buf, m, cam, a, b, side.scale(half * 0.28), 1f, 1f, 1f, 0.9f * alpha * pulse);
        }
        BufferUploader.drawWithShader(buf.end());

        if (grow < 1f) billboard(pose, cam, facing, end, half * 3.5f, rgb, alpha);
    }

    /** Satu ruas pita: terang di tengah lebarnya, memudar ke kedua tepi. */
    private static void strip(BufferBuilder buf, Matrix4f m, Vec3 cam, Vec3 a, Vec3 b, Vec3 side,
                              float r, float g, float bl, float alpha) {
        for (int half = 0; half < 2; half++) {
            Vec3 s = half == 0 ? side : side.scale(-1);
            vertex(buf, m, cam, a, r, g, bl, alpha);
            vertex(buf, m, cam, b, r, g, bl, alpha);
            vertex(buf, m, cam, b.add(s), r, g, bl, 0f);
            vertex(buf, m, cam, a.add(s), r, g, bl, 0f);
        }
    }

    private static void vertex(BufferBuilder buf, Matrix4f m, Vec3 cam, Vec3 p, float r, float g, float b, float a) {
        buf.vertex(m, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).color(r, g, b, a).endVertex();
    }

    // ── Render player ─────────────────────────────────────────────────────────

    /** false = player ini sudah masuk portal, jangan digambar. */
    public static boolean isVisible(Player player) {
        if (!active || type != WIN) return true;
        Actor a = actors.get(player.getId());
        return a == null || !a.good || farewellTime(a) < EndingTimeline.ACTOR_TICKS;
    }

    /** true = bayangan di tanah disembunyikan (badannya sedang digambar menjauh dari posisi aslinya). */
    public static boolean hidesShadow(Player player) {
        if (!active || type != WIN) return false;
        Actor a = actors.get(player.getId());
        return a != null && a.good && farewellTime(a) >= EndingTimeline.ACTOR_BACK_END;
    }

    /** true = ending menang sudah sampai bagian perpisahan (para player sudah berdiri di depan portal). */
    private static boolean atFarewell() {
        return active && type == WIN && time() >= EndingTimeline.FAREWELL_MOVE_AT;
    }

    /** Seberapa kuat gerakan pamit player ini sekarang (0..1). */
    private static float gesture(float lt) {
        return PortalActorAnim.smooth(2f, 9f, lt)
                * (1f - PortalActorAnim.smooth(EndingTimeline.ACTOR_GESTURE_END - 6f, EndingTimeline.ACTOR_GESTURE_END, lt));
    }

    /**
     * Gerak badan saat berpamitan. Player baik berdiri menghadap kubu jahat: ia berpamitan dulu
     * (membungkuk / melompat sesuai gaya), baru berbalik ke portal dan melangkah masuk sambil mengecil.
     * Dipanggil di RenderPlayerEvent.Pre, sebelum renderer memutar badan sesuai yaw, jadi sumbunya
     * masih sumbu dunia.
     */
    public static void applyTransform(Player player, PoseStack pose, float partialTick) {
        if (active && type != WIN) {
            faceSeats(player, pose, partialTick);
            return;
        }
        if (!atFarewell()) return;
        Actor a = actors.get(player.getId());
        if (a == null) return;

        double ex = Mth.lerp(partialTick, player.xOld, player.getX());
        double ey = Mth.lerp(partialTick, player.yOld, player.getY());
        double ez = Mth.lerp(partialTick, player.zOld, player.getZ());
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        // Arah hadap digambar pasti, tidak bergantung yaw badan aslinya (yang bisa tertinggal jauh
        // dari arah teleport): kubu jahat menghadap portal, kubu baik menghadap kubu jahat.
        Vec3 g = gate();
        float toGate = (float) (Mth.atan2(-(g.x - ex), g.z - ez) * Mth.RAD_TO_DEG);
        if (!a.good) {
            pose.mulPose(Axis.YP.rotationDegrees(bodyYaw - toGate));
            return;
        }

        float lt = Math.max(0f, farewellTime(a));
        float gesture = gesture(lt);
        float turn = 180f * PortalActorAnim.smooth(EndingTimeline.ACTOR_GESTURE_END, EndingTimeline.ACTOR_BACK_END, lt);
        float walk = PortalActorAnim.smooth(EndingTimeline.ACTOR_BACK_END, EndingTimeline.ACTOR_TICKS, lt);
        Vec3 entry = AvalonPortal.GATE_ENTRY;

        // Melangkah lurus ke kaki portal, masing-masing di kolomnya sendiri supaya tidak menumpuk
        double hop = a.style == BOTH_ARMS ? Math.abs(Mth.sin(lt * 0.5f)) * 0.22 * gesture : 0.0;
        pose.translate(0.0, (entry.y - ey) * walk + hop, (entry.z - ez) * walk);

        // Membelakangi portal (menghadap kubu jahat), lalu berbalik ke portal
        float facing = toGate + 180f + turn;
        float lean = a.style == BOW ? 32f * gesture : 0f;
        pose.mulPose(Axis.YP.rotationDegrees(180f - facing));
        // Di kerangka badan, depan = -Z: putaran X negatif = membungkuk ke depan
        pose.mulPose(Axis.XP.rotationDegrees(-lean));
        float scale = 1f - 0.9f * PortalActorAnim.smooth(EndingTimeline.ACTOR_TICKS - 6f, EndingTimeline.ACTOR_TICKS, lt);
        pose.scale(scale, scale, scale);
        // Batalkan putaran yaw yang akan dipasang renderer; arah hadap sudah diatur di atas
        pose.mulPose(Axis.YP.rotationDegrees(bodyYaw - 180f));
    }

    /**
     * Ending kalah: kedua kubu digambar saling berhadapan, tidak bergantung yaw badan aslinya.
     * Kubu jahat (berdiri di antara kursi dan portal) menghadap lingkaran kursi; kubu baik
     * (duduk di kursinya) menghadap kubu jahat.
     */
    private static void faceSeats(Player player, PoseStack pose, float partialTick) {
        Actor a = actors.get(player.getId());
        if (a == null || keepsOwnFacing(a, player)) return;
        double x = Mth.lerp(partialTick, player.xOld, player.getX());
        double z = Mth.lerp(partialTick, player.zOld, player.getZ());
        // Assassin membidik Merlin; kubu jahat lainnya menghadap lingkaran kursi
        Vec3 target = a.good ? evilGroup()
                : type == MERLIN && player.getId() == assassinId ? merlinPos()
                : Vec3.atBottomCenterOf(AvalonSeats.CENTER);
        double dx = target.x - x, dz = target.z - z;
        if (dx * dx + dz * dz < 0.01) return;
        float toward = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
        pose.mulPose(Axis.YP.rotationDegrees(renderedBodyYaw(player, partialTick) - toward));
    }

    /**
     * Yaw badan yang akan dipakai renderer untuk player ini. Untuk yang duduk di kursi (ArmorStand),
     * vanilla tidak memakai yaw badan player, melainkan menurunkannya dari yaw kursi & arah kepala.
     */
    private static float renderedBodyYaw(Player player, float partialTick) {
        float body = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        if (player.isPassenger() && player.getVehicle() instanceof LivingEntity seat) {
            float head = Mth.rotLerp(partialTick, player.yHeadRotO, player.yHeadRot);
            body = Mth.rotLerp(partialTick, seat.yBodyRotO, seat.yBodyRot);
            float turn = Mth.clamp(Mth.wrapDegrees(head - body), -85f, 85f);
            body = head - turn;
            if (turn * turn > 2500f) body += turn * 0.2f;
        }
        return body;
    }

    /**
     * Ending Merlin: kubu jahat tetap di posisi terakhirnya dengan arah hadapnya sendiri. Hanya
     * assassin yang arah hadapnya diatur (membidik Merlin).
     */
    private static boolean keepsOwnFacing(Actor a, Entity entity) {
        return type == MERLIN && !a.good && entity.getId() != assassinId;
    }

    /** Pose kepala & tangan; dipanggil dari PlayerModelMixin setelah setupAnim vanilla. */
    public static void poseModel(PlayerModel<?> model, Entity entity) {
        if (!active) return;
        Actor a = actors.get(entity.getId());
        if (a == null) return;
        float t = time();

        // Ending menang: sebelum perpisahan semua masih duduk di kursinya dengan pose biasa
        if (type == WIN && !atFarewell()) return;
        if (type == WIN) {
            // Arah badan diatur applyTransform; kepala lurus mengikutinya (tidak ikut arah pandang aslinya)
            model.head.yRot = 0f;
            model.head.xRot = a.good ? 0.05f : -0.2f;
        }
        if (type == WIN && a.good) {
            farewellPose(model, a, farewellTime(a));
        } else {
            float bowed;
            if (type == WIN) {
                // Kubu jahat melepas kepergian mereka: semuanya melambai, iramanya berbeda-beda
                float wave = PortalActorAnim.smooth(EndingTimeline.FAREWELL_START - 6f, EndingTimeline.FAREWELL_START + 8f, t);
                float swing = Mth.sin(t * 0.8f + entity.getId() * 1.3f) * 0.5f;
                model.rightArm.xRot = Mth.lerp(wave, model.rightArm.xRot, -2.75f);
                model.rightArm.zRot = Mth.lerp(wave, model.rightArm.zRot, 0.25f + swing);
                if (entity.getId() % 3 == 0) {
                    model.leftArm.xRot = Mth.lerp(wave, model.leftArm.xRot, -2.75f);
                    model.leftArm.zRot = Mth.lerp(wave, model.leftArm.zRot, -0.25f + swing);
                }
                bowed = 0f;
            } else {
                // Ending kalah: arah badan diatur faceSeats; kepala lurus mengikutinya, jadi kedua kubu
                // saling menatap.
                if (keepsOwnFacing(a, entity)) return;
                model.head.yRot = 0f;
                model.head.xRot = 0.05f;
                if (type == MERLIN && entity.getId() == assassinId) {
                    // Menarik busur (pose memanah vanilla), lalu menurunkannya setelah panah lepas
                    float draw = PortalActorAnim.smooth(EndingTimeline.MERLIN_DRAW_START, EndingTimeline.MERLIN_DRAW_START + 18f, t)
                            * (1f - PortalActorAnim.smooth(EndingTimeline.MERLIN_RELEASE + 8f, EndingTimeline.MERLIN_RELEASE + 24f, t));
                    model.rightArm.xRot = Mth.lerp(draw, model.rightArm.xRot, -Mth.HALF_PI);
                    model.rightArm.yRot = Mth.lerp(draw, model.rightArm.yRot, -0.1f);
                    model.leftArm.xRot = Mth.lerp(draw, model.leftArm.xRot, -Mth.HALF_PI);
                    model.leftArm.yRot = Mth.lerp(draw, model.leftArm.yRot, 0.5f);
                }
                bowed = 0f;
            }
            model.head.xRot = Mth.lerp(bowed, model.head.xRot, BOW_PITCH);
            model.head.yRot = Mth.lerp(bowed, model.head.yRot, 0f);
        }

        model.hat.copyFrom(model.head);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightPants.copyFrom(model.rightLeg);
        model.leftPants.copyFrom(model.leftLeg);
    }

    private static void farewellPose(PlayerModel<?> model, Actor a, float lt) {
        if (lt <= 0f) return;
        float g = gesture(lt);
        float walk = PortalActorAnim.smooth(EndingTimeline.ACTOR_BACK_END - 2f, EndingTimeline.ACTOR_BACK_END + 3f, lt);

        // Selama berpamitan kepala lurus ke orang-orang yang ditinggalkan
        model.head.xRot = Mth.lerp(g, model.head.xRot, 0.05f);
        model.head.yRot = Mth.lerp(g, model.head.yRot, 0f);

        switch (a.style) {
            case WAVE -> {
                model.rightArm.xRot = Mth.lerp(g, model.rightArm.xRot, -2.75f);
                model.rightArm.zRot = Mth.lerp(g, model.rightArm.zRot, 0.25f + Mth.sin(lt * 0.85f) * 0.5f);
            }
            case NOD -> {
                model.head.xRot += g * (0.3f + 0.3f * Mth.sin(lt * 0.55f));
                model.rightArm.xRot = Mth.lerp(g, model.rightArm.xRot, -0.9f);
                model.rightArm.yRot = Mth.lerp(g, model.rightArm.yRot, -0.7f);
            }
            case SALUTE -> {
                // Hormat: tangan kanan ke dahi
                model.rightArm.xRot = Mth.lerp(g, model.rightArm.xRot, -2.35f);
                model.rightArm.zRot = Mth.lerp(g, model.rightArm.zRot, -0.6f);
                model.head.xRot = Mth.lerp(g, model.head.xRot, -0.1f);
            }
            case BOTH_ARMS -> {
                float swing = Mth.sin(lt * 0.9f) * 0.4f;
                model.rightArm.xRot = Mth.lerp(g, model.rightArm.xRot, -2.8f);
                model.rightArm.zRot = Mth.lerp(g, model.rightArm.zRot, 0.35f + swing);
                model.leftArm.xRot = Mth.lerp(g, model.leftArm.xRot, -2.8f);
                model.leftArm.zRot = Mth.lerp(g, model.leftArm.zRot, -0.35f + swing);
            }
            case BOW -> {
                model.head.xRot = Mth.lerp(g, model.head.xRot, 0.5f);
                model.rightArm.xRot = Mth.lerp(g, model.rightArm.xRot, -0.5f);
                model.rightArm.yRot = Mth.lerp(g, model.rightArm.yRot, -0.9f);
            }
            default -> {
            }
        }

        // Melangkah masuk portal
        // Langkah santai: ayunan kecil & pelan, tangan mengayun tipis
        float stride = Mth.cos(lt * 0.55f) * 0.5f * walk;
        model.rightLeg.xRot = Mth.lerp(walk, model.rightLeg.xRot, stride);
        model.leftLeg.xRot = Mth.lerp(walk, model.leftLeg.xRot, -stride);
        model.rightArm.xRot = Mth.lerp(walk, model.rightArm.xRot, -stride * 0.6f);
        model.leftArm.xRot = Mth.lerp(walk, model.leftArm.xRot, stride * 0.6f);
    }

    // ── Overlay layar ─────────────────────────────────────────────────────────

    /** Bilah hitam sinematik, terang dari gelap di awal, lalu memutih (menang) atau menggelap di akhir. */
    public static void renderOverlay(GuiGraphics g, int width, int height) {
        int fadeRgb = type == WIN ? 0xF6ECFF : 0x000000;
        if (!active) {
            if (outro >= 0) fill(g, width, height, 1f - (outro + partial) / EndingTimeline.FADE_TICKS, fadeRgb);
            return;
        }
        float t = time();
        int bar = Math.round(height * 0.11f * PortalActorAnim.smooth(0f, LETTERBOX_TICKS, t));
        if (bar > 0) {
            g.fill(0, 0, width, bar, 0xFF000000);
            g.fill(0, height - bar, width, height, 0xFF000000);
        }
        fill(g, width, height, 1f - t / FADE_IN_TICKS, 0x000000);
        // Kilatan portal menutupi perpindahan para player dari kursi ke depan portal
        if (type == WIN) {
            fill(g, width, height, 1f - Math.abs(t - EndingTimeline.FAREWELL_START) / EndingTimeline.FAREWELL_FLASH_TICKS, 0xF6ECFF);
        }
        fill(g, width, height, (t - (total - EndingTimeline.FADE_TICKS)) / EndingTimeline.FADE_TICKS, fadeRgb);
    }

    private static void fill(GuiGraphics g, int width, int height, float alpha, int rgb) {
        int a = Math.round(Mth.clamp(alpha, 0f, 1f) * 255f);
        if (a > 2) g.fill(0, 0, width, height, (a << 24) | rgb);
    }
}

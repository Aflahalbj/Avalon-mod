package id.avalon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import id.avalon.client.PortalActorAnim.Actor;
import id.avalon.client.PortalActorAnim.Frame;
import id.avalon.cutscene.PortalTimeline;
import id.avalon.mixin.CameraAccessor;
import id.avalon.network.AvalonNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ViewportEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Cutscene portal di sisi client: kamera sinematik, animasi player tersedot, partikel, suara,
 * dan layar memutih saat player sendiri masuk portal.
 *
 * Naskahnya datang dari server (AvalonNetwork.PortalStart); setelah itu semuanya dihitung dari
 * umur cutscene, tanpa paket tambahan. Cutscene berakhir sendiri saat client pindah dimensi.
 */
public final class PortalCutsceneClient {

    private PortalCutsceneClient() {}

    /** Kamera pindah dari sorotan pembuka ke sorotan lebar dari samping. */
    private static final float WIDE_CUT = PortalTimeline.FIRST_PULL - 8f;
    /** Kamera mulai mengikuti player sendiri (tick sejak ia mulai terangkat). */
    private static final float CHASE_START = PortalTimeline.LIFT_TICKS - 2f;

    /** Para player selesai berputar menghadap portal, sesaat sebelum mulai terangkat. */
    private static final float TURN_END = PortalTimeline.FIRST_PULL - 6f;

    private static final int BAR_COLOR = 0xFF000000;
    private static final int WHITE_RGB = 0xF6ECFF;
    private static final long AFTERGLOW_MS = 900L;

    static final class Scene {
        final ResourceKey<Level> dimension;
        final Vec3 portal;
        /** Arah mendatar dari para player ke portal, dan arah sampingnya. */
        final Vec3 dir;
        final Vec3 side;
        /** Titik tengah para player (di tanah). */
        final Vec3 center;
        final float radius;
        final Map<Integer, Actor> actors = new HashMap<>();
        /** Player client ini kalau ikut tersedot; null = hanya menonton dari tempatnya. */
        Actor local;
        int closeStart;
        int endTick;
        int age = 0;
        /** Umur saat player terakhir masuk portal (kilatan portal). */
        float pulseAt = -100f;
        /** Tick terakhir efek bersama para player dimainkan. */
        int effectsAge = -1;
        /** Sisi (+1 / -1) tempat kamera sorotan lebar berada. */
        double wideSide = 1.0;

        Scene(ResourceKey<Level> dimension, AvalonNetwork.PortalStart msg) {
            this.dimension = dimension;
            this.portal = new Vec3(msg.x(), msg.y(), msg.z());
            this.dir = new Vec3(msg.dirX(), 0, msg.dirZ());
            this.side = new Vec3(-msg.dirZ(), 0, msg.dirX());
            this.center = new Vec3(msg.centerX(), msg.centerY(), msg.centerZ());
            this.radius = msg.radius();
        }
    }

    /** Satu sorotan kamera. */
    private static final class Shot {
        double x, y, z;
        float yaw, pitch, roll, fov;
    }

    private static Scene scene;
    private static float partial;
    private static long afterglowUntil = 0L;

    // ── Mulai / berhenti ──────────────────────────────────────────────────────

    public static void start(AvalonNetwork.PortalStart msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        end(false);

        Scene s = new Scene(mc.level.dimension(), msg);
        int lastDelay = 0;
        for (AvalonNetwork.PortalActor a : msg.actors()) {
            Actor actor = new Actor(a, s.portal);
            s.actors.put(actor.entityId, actor);
            lastDelay = Math.max(lastDelay, actor.delay);
        }
        s.local = s.actors.get(mc.player.getId());
        s.closeStart = PortalTimeline.closeStart(lastDelay);
        s.endTick = PortalTimeline.end(lastDelay);
        s.wideSide = pickWideSide(mc, s);

        PortalRenderer.prepareTextures();
        scene = s;
    }

    public static void stop() {
        end(false);
    }

    /**
     * Player client ini sudah masuk portal tapi tetap di dunia ini: kembalikan kameranya dan
     * gambar badannya seperti biasa lagi. Portal & player lain tetap dianimasikan sampai selesai.
     */
    public static void release() {
        Scene s = scene;
        if (s == null || s.local == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.noCulling = false;
        s.actors.remove(s.local.entityId);
        s.local = null;
        afterglowUntil = System.currentTimeMillis() + AFTERGLOW_MS;
    }

    private static void end(boolean arrived) {
        Scene s = scene;
        if (s == null) return;
        scene = null;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            for (Actor a : s.actors.values()) {
                Entity e = mc.level.getEntity(a.entityId);
                if (e != null) e.noCulling = false;
            }
        }
        // Layar putih memudar di atas layar loading Avalon, bukan terpotong mendadak
        if (arrived && s.local != null) {
            afterglowUntil = System.currentTimeMillis() + AFTERGLOW_MS;
        }
    }

    static Scene scene() {
        return scene;
    }

    /** Umur cutscene dalam tick, halus per frame. */
    static float time() {
        return scene == null ? 0f : scene.age + partial;
    }

    public static void setPartialTick(float partialTick) {
        partial = partialTick;
    }

    /** true = client ini sedang menonton lewat kamera cutscene. */
    public static boolean hasCamera() {
        return scene != null && scene.local != null;
    }

    private static Actor actor(Entity entity) {
        return scene == null ? null : scene.actors.get(entity.getId());
    }

    public static boolean isActor(Entity entity) {
        return actor(entity) != null;
    }

    // ── Tick: suara, partikel, akhir cutscene ─────────────────────────────────

    public static void tick() {
        Scene s = scene;
        if (s == null) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level.dimension() != s.dimension) {
            end(true);
            return;
        }
        if (mc.isPaused()) return;

        s.age++;
        if (s.age > s.endTick) {
            end(false);
            return;
        }

        // Badan digambar jauh dari posisi entity aslinya, jadi jangan di-cull berdasarkan hitbox
        for (Actor a : s.actors.values()) {
            Entity e = level.getEntity(a.entityId);
            if (e != null) e.noCulling = true;
        }

        tickPortal(s, level);
        for (Actor a : s.actors.values()) tickActor(s, a, level);
    }

    private static void tickPortal(Scene s, ClientLevel level) {
        RandomSource rnd = level.random;
        Vec3 p = s.portal;
        int age = s.age;
        int closeEnd = s.closeStart + PortalTimeline.CLOSE_TICKS;

        // Celah cahaya: percikan listrik & dengung yang makin tinggi
        if (age < PortalTimeline.OPEN_START) {
            if (age == 1 || age == 9 || age == 17) {
                sound(level, p, SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.6f, 0.6f + age * 0.035f);
            }
            float reach = s.radius * 0.9f * age / PortalTimeline.OPEN_START;
            for (int i = 0; i < 3; i++) {
                particle(level, ParticleTypes.ELECTRIC_SPARK, p.x, p.y + (rnd.nextFloat() * 2f - 1f) * reach, p.z,
                        (rnd.nextFloat() - 0.5f) * 0.3f, (rnd.nextFloat() - 0.5f) * 0.3f, (rnd.nextFloat() - 0.5f) * 0.3f);
            }
            return;
        }

        if (age == PortalTimeline.OPEN_START) {
            sound(level, p, SoundEvents.END_PORTAL_SPAWN, 0.7f, 1.3f);
            sound(level, p, SoundEvents.PORTAL_TRIGGER, 1.2f, 0.7f);
            sound(level, p, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.5f, 0.55f);
            burst(s, level, 40);
        }
        if (age == PortalTimeline.OPEN_END) {
            sound(level, p, SoundEvents.BEACON_ACTIVATE, 2.0f, 0.6f);
            sound(level, p, SoundEvents.ELYTRA_FLYING, 0.5f, 0.7f);
        }
        if (age == s.closeStart) {
            sound(level, p, SoundEvents.BEACON_DEACTIVATE, 2.0f, 0.6f);
        }
        if (age == closeEnd) {
            sound(level, p, SoundEvents.GENERIC_EXPLODE, 1.2f, 1.7f);
            particle(level, ParticleTypes.FLASH, p.x, p.y, p.z, 0, 0, 0);
            burst(s, level, 50);
        }
        if (age >= closeEnd) return;

        if ((age - PortalTimeline.OPEN_START) % 40 == 20) {
            sound(level, p, SoundEvents.PORTAL_AMBIENT, 1.4f, 0.6f);
        }

        // Partikel tersedot dari sisi para player menuju tengah portal
        if (age > PortalTimeline.OPEN_START + 10) {
            for (int i = 0; i < 7; i++) {
                double fromSide = rnd.nextDouble() * 2 - 1;
                double fromUp = rnd.nextDouble() * 2 - 1;
                double fromFront = 0.3 + rnd.nextDouble() * 0.7;
                Vec3 from = s.side.scale(fromSide).add(0, fromUp, 0).subtract(s.dir.scale(fromFront))
                        .normalize().scale(4 + rnd.nextDouble() * 7);
                double spread = s.radius * 0.45;
                particle(level, ParticleTypes.PORTAL,
                        p.x + s.side.x * (rnd.nextDouble() - 0.5) * spread,
                        p.y + (rnd.nextDouble() - 0.5) * spread,
                        p.z + s.side.z * (rnd.nextDouble() - 0.5) * spread,
                        from.x, from.y, from.z);
            }
            Vec3 far = s.side.scale(rnd.nextDouble() * 2 - 1).add(0, rnd.nextDouble() * 1.2 - 0.8, 0)
                    .subtract(s.dir.scale(0.4 + rnd.nextDouble())).normalize().scale(6 + rnd.nextDouble() * 6);
            // END_ROD melambat (gesekan 0.91), jadi kecepatan awal ~9% jarak supaya sampai di tengah
            particle(level, ParticleTypes.END_ROD, p.x + far.x, p.y + far.y, p.z + far.z,
                    -far.x * 0.09, -far.y * 0.09, -far.z * 0.09);
        }

        // Percikan di tepi portal, bergerak searah putaran
        for (int i = 0; i < 2; i++) {
            float angle = rnd.nextFloat() * Mth.TWO_PI;
            double cos = Mth.cos(angle) * s.radius * 1.05;
            double sin = Mth.sin(angle) * s.radius * 1.05;
            particle(level, ParticleTypes.WITCH, p.x + s.side.x * cos, p.y + sin, p.z + s.side.z * cos,
                    -s.side.x * sin * 0.03, cos * 0.03, -s.side.z * sin * 0.03);
        }
    }

    private static void tickActor(Scene s, Actor a, ClientLevel level) {
        int t = s.age - a.delay;
        if (t < 0 || t > PortalTimeline.PLAYER_TICKS) return;
        RandomSource rnd = level.random;

        // Semua player bergerak serentak: efek bersama (suara, kilatan) cukup sekali per tick, bukan per player
        boolean first = s.effectsAge != s.age;
        s.effectsAge = s.age;

        if (t == PortalTimeline.PLAYER_TICKS) {
            // Masuk portal: kilatan + letupan
            if (!first) return;
            Vec3 p = s.portal;
            s.pulseAt = s.age;
            sound(level, p, SoundEvents.CHORUS_FRUIT_TELEPORT, 1.6f, 0.6f + rnd.nextFloat() * 0.3f);
            sound(level, p, SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.6f, 0.8f);
            sound(level, p, SoundEvents.AMETHYST_BLOCK_CHIME, 2.0f, 0.6f + rnd.nextFloat() * 0.5f);
            particle(level, ParticleTypes.FLASH, p.x, p.y, p.z, 0, 0, 0);
            burst(s, level, 26);
            return;
        }

        Frame f = PortalActorAnim.at(a, s.age);
        Vec3 here = new Vec3(f.x, f.y, f.z);
        if (first && t == 0) {
            sound(level, here, SoundEvents.ENDER_DRAGON_FLAP, 0.8f, 1.3f + rnd.nextFloat() * 0.3f);
        }
        if (first && t == PortalTimeline.LIFT_TICKS) {
            sound(level, here, SoundEvents.TRIDENT_RIPTIDE_2, 1.0f, 0.8f + rnd.nextFloat() * 0.4f);
        }

        // Debu tersapu dari tanah saat mulai terangkat
        if (t < 10) {
            for (int i = 0; i < 2; i++) {
                particle(level, ParticleTypes.CLOUD,
                        a.start.x + (rnd.nextDouble() - 0.5) * 0.8, a.start.y + 0.1, a.start.z + (rnd.nextDouble() - 0.5) * 0.8,
                        a.toPortal.x * 0.12, 0.08 + rnd.nextDouble() * 0.08, a.toPortal.z * 0.12);
            }
        }

        // Jejak cahaya di belakang badan
        double jitter = 0.5 * f.scale;
        particle(level, ParticleTypes.WITCH,
                f.x + (rnd.nextDouble() - 0.5) * jitter, f.y + (rnd.nextDouble() - 0.5) * jitter * 2, f.z + (rnd.nextDouble() - 0.5) * jitter,
                0, 0, 0);
        if (t >= PortalTimeline.LIFT_TICKS || t % 3 == 0) {
            particle(level, ParticleTypes.END_ROD,
                    f.x + (rnd.nextDouble() - 0.5) * jitter, f.y + (rnd.nextDouble() - 0.5) * jitter * 2, f.z + (rnd.nextDouble() - 0.5) * jitter,
                    (rnd.nextDouble() - 0.5) * 0.04, (rnd.nextDouble() - 0.5) * 0.04, (rnd.nextDouble() - 0.5) * 0.04);
        }
    }

    /** Letupan partikel melingkar di bidang portal. */
    private static void burst(Scene s, ClientLevel level, int count) {
        RandomSource rnd = level.random;
        Vec3 p = s.portal;
        for (int i = 0; i < count; i++) {
            float angle = rnd.nextFloat() * Mth.TWO_PI;
            double speed = 0.15 + rnd.nextDouble() * 0.3;
            double cos = Mth.cos(angle) * speed;
            double sin = Mth.sin(angle) * speed;
            double out = (rnd.nextDouble() - 0.7) * 0.2;
            particle(level, i % 2 == 0 ? ParticleTypes.END_ROD : ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z,
                    s.side.x * cos + s.dir.x * out, sin, s.side.z * cos + s.dir.z * out);
        }
    }

    private static void particle(ClientLevel level, ParticleOptions type, double x, double y, double z,
                                 double dx, double dy, double dz) {
        level.addParticle(type, true, x, y, z, dx, dy, dz);
    }

    private static void sound(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.MASTER, volume, pitch, false);
    }

    // ── Render player ─────────────────────────────────────────────────────────

    /** false = player ini sudah masuk portal, jangan digambar sama sekali. */
    public static boolean isVisible(Player player) {
        Actor a = actor(player);
        return a == null || time() - a.delay < PortalTimeline.PLAYER_TICKS;
    }

    /** true = bayangan di tanah harus disembunyikan (badannya sedang digambar di tempat lain). */
    public static boolean hidesShadow(Player player) {
        Actor a = actor(player);
        return a != null && time() >= a.delay;
    }

    /**
     * Pindahkan & putar badan player ke posisi animasinya. Dipanggil di RenderPlayerEvent.Pre,
     * sebelum renderer memutar badan sesuai yaw, jadi di sini sumbu masih sumbu dunia.
     */
    public static void applyTransform(Player player, PoseStack pose, float partialTick) {
        Actor a = actor(player);
        if (a == null) return;
        float time = time();
        Frame f = PortalActorAnim.at(a, time);
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);

        // Begitu portal terbuka, badan berputar pelan dari arah hadap terakhirnya ke arah portal.
        // Putarannya disimpan sebagai selisih, jadi gerakan mouse player tetap terlihat di atasnya.
        if (time < PortalTimeline.OPEN_START) return;
        if (Float.isNaN(a.turnFrom)) a.turnFrom = bodyYaw;
        float turn = PortalActorAnim.smooth(PortalTimeline.OPEN_START, TURN_END, time);
        float turned = bodyYaw + turn * Mth.wrapDegrees(a.facing - a.turnFrom);

        if (!f.airborne) {
            pose.mulPose(Axis.YP.rotationDegrees(bodyYaw - turned));
            return;
        }

        double ex = Mth.lerp(partialTick, player.xOld, player.getX());
        double ey = Mth.lerp(partialTick, player.yOld, player.getY());
        double ez = Mth.lerp(partialTick, player.zOld, player.getZ());

        // Lanjut dari arah itu ke arah gayanya selagi terangkat
        float facing = Mth.rotLerp(PortalActorAnim.smooth(0f, 0.5f, f.lift), turned, f.facing);

        pose.translate(f.x - ex, f.y - ey, f.z - ez);
        pose.mulPose(Axis.YP.rotationDegrees(180f - facing));
        pose.mulPose(f.local);
        pose.scale(f.scale, f.scale * f.stretch, f.scale);
        // Batalkan putaran yaw yang akan dipasang renderer; arah hadap sudah diatur di atas
        pose.mulPose(Axis.YP.rotationDegrees(bodyYaw - 180f));
        pose.translate(0f, -PortalActorAnim.PIVOT * a.scale, 0f);
    }

    /** Timpa pose tangan/kaki; dipanggil dari PlayerModelMixin setelah setupAnim vanilla. */
    public static void poseModel(PlayerModel<?> model, Entity entity) {
        if (scene == null || !(entity instanceof Player)) return;
        Actor a = actor(entity);
        if (a == null) return;
        PortalActorAnim.pose(model, a, time());
    }

    // ── Kamera ────────────────────────────────────────────────────────────────

    /** Pasang kamera cutscene; false = tidak ada cutscene untuk client ini. */
    public static boolean applyCamera(ViewportEvent.ComputeCameraAngles event) {
        if (!hasCamera()) return false;
        Shot shot = shot();

        CameraAccessor camera = (CameraAccessor) event.getCamera();
        camera.avalon$setPosition(shot.x, shot.y, shot.z);
        camera.avalon$setRotation(shot.yaw, shot.pitch);
        // Detached = badan player sendiri ikut digambar walau opsi kamera first person
        camera.avalon$setDetached(true);

        event.setYaw(shot.yaw);
        event.setPitch(shot.pitch);
        event.setRoll(shot.roll);
        return true;
    }

    public static double fov() {
        return shot().fov;
    }

    private static Shot shot() {
        Scene s = scene;
        float time = time();
        float own = time - s.local.delay;
        double distance = Math.sqrt(s.portal.distanceToSqr(s.center.x, s.portal.y, s.center.z));

        Vec3 pos;
        Vec3 look;
        Vec3 anchor;
        float fov;
        float roll = 0f;
        float shake = bump(time, PortalTimeline.OPEN_START, PortalTimeline.OPEN_START + 34f) * 0.10f;

        if (own >= CHASE_START) {
            // Mengikuti player sendiri dari belakang, lalu ikut masuk portal
            Frame f = PortalActorAnim.at(s.local, Math.min(time, s.local.delay + PortalTimeline.PLAYER_TICKS - 0.01f));
            Vec3 body = new Vec3(f.x, f.y, f.z);
            float e = f.pull * f.pull;

            pos = body.subtract(s.dir.scale(4.5 + 5.5 * e))
                    .add(s.side.scale(1.8 * (1f - e)))
                    .add(0, 1.4 + 0.8 * (1f - e), 0);
            look = body.lerp(s.portal, 0.3 + 0.7 * f.pull);
            anchor = body;
            fov = 70f + 38f * e;
            roll = Mth.sin(time * 0.9f) * 1.5f * f.pull;
            shake += 0.06f * f.pull;

            float after = own - PortalTimeline.PLAYER_TICKS;
            if (after > 0f) {
                float rush = Mth.clamp(after / PortalTimeline.TELEPORT_DELAY, 0f, 1f);
                rush *= rush;
                pos = pos.lerp(s.portal, rush * 0.92);
                look = s.portal.add(s.dir.scale(6));
                fov += 20f * rush;
                roll += 30f * rush;
            }
        } else if (time >= WIDE_CUT) {
            // Sorotan lebar dari samping, rendah, bergeser pelan mengitari
            Vec3 mid = new Vec3(
                    Mth.lerp(0.55, s.center.x, s.portal.x),
                    Mth.lerp(0.5, s.center.y, s.portal.y) + 0.5,
                    Mth.lerp(0.55, s.center.z, s.portal.z));
            float angle = Mth.clamp(-10f + (time - WIDE_CUT) * 0.12f, -10f, 25f) * Mth.DEG_TO_RAD;
            double reach = distance * 0.9 + 9;
            pos = mid.add(s.side.scale(s.wideSide * Mth.cos(angle) * reach))
                    .subtract(s.dir.scale(Mth.sin(angle) * reach));
            pos = new Vec3(pos.x, s.center.y + 2.2, pos.z);
            look = mid;
            anchor = mid;
            fov = 62f;
        } else {
            // Pembuka: dari belakang para player, mendekat pelan sambil portal terbuka
            float q = PortalActorAnim.smooth(0f, WIDE_CUT, time);
            pos = s.center.subtract(s.dir.scale(7.5 - 2.5 * q))
                    .add(s.side.scale(s.wideSide * (2.8 - 0.8 * q)))
                    .add(0, 2.6 - 0.5 * q, 0);
            look = s.portal.subtract(0, 1.2 * (1f - q), 0);
            anchor = s.center.add(0, 1.6, 0);
            fov = 66f - 8f * q;
        }

        pos = unobstructed(anchor, pos);

        Shot shot = new Shot();
        shot.x = pos.x + Mth.sin(time * 2.1f) * shake;
        shot.y = pos.y + Mth.sin(time * 2.7f + 1f) * shake;
        shot.z = pos.z + Mth.sin(time * 1.9f + 2f) * shake;
        shot.roll = roll + Mth.sin(time * 3.1f) * shake * 12f;
        shot.fov = fov;

        double dx = look.x - shot.x;
        double dy = look.y - shot.y;
        double dz = look.z - shot.z;
        shot.yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        shot.pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
        return shot;
    }

    /** Naik lalu turun mulus di antara {@code from} dan {@code to}, nol di luar itu. */
    private static float bump(float value, float from, float to) {
        if (value <= from || value >= to) return 0f;
        return Mth.sin((value - from) / (to - from) * Mth.PI);
    }

    /** Kalau ada blok di antara {@code anchor} dan posisi kamera, majukan kamera ke depan blok itu. */
    private static Vec3 unobstructed(Vec3 anchor, Vec3 pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return pos;
        HitResult hit = mc.level.clip(new ClipContext(anchor, pos,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) return pos;
        Vec3 back = anchor.subtract(pos).normalize().scale(0.4);
        return hit.getLocation().add(back);
    }

    /** Pilih sisi kamera sorotan lebar yang pandangannya paling lapang. */
    private static double pickWideSide(Minecraft mc, Scene s) {
        Vec3 mid = s.center.lerp(s.portal, 0.5);
        double best = 1.0;
        double bestClear = -1.0;
        for (double sign : new double[]{1.0, -1.0}) {
            Vec3 target = mid.add(s.side.scale(sign * 22));
            HitResult hit = mc.level.clip(new ClipContext(mid, target,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            double clear = hit.getType() == HitResult.Type.MISS ? 22 : hit.getLocation().distanceTo(mid);
            if (clear > bestClear) {
                bestClear = clear;
                best = sign;
            }
        }
        return best;
    }

    // ── Overlay layar ─────────────────────────────────────────────────────────

    /** Garis hitam sinematik + layar memutih saat player sendiri masuk portal. */
    public static void renderOverlay(GuiGraphics g, int width, int height) {
        if (hasCamera()) {
            float time = time();
            int bar = (int) (height * 0.11f * PortalActorAnim.smooth(0f, 14f, time));
            if (bar > 0) {
                g.fill(0, 0, width, bar, BAR_COLOR);
                g.fill(0, height - bar, width, height, BAR_COLOR);
            }

            float own = time - scene.local.delay;
            float white = PortalActorAnim.smooth(PortalTimeline.PLAYER_TICKS - 5f, PortalTimeline.PLAYER_TICKS + 9f, own);
            fillWhite(g, width, height, white);
        } else {
            renderAfterglow(g, width, height);
        }
    }

    /** Sisa putih yang memudar setelah pindah dimensi (digambar juga di atas layar loading). */
    public static void renderAfterglow(GuiGraphics g, int width, int height) {
        long left = afterglowUntil - System.currentTimeMillis();
        if (left <= 0) return;
        fillWhite(g, width, height, left / (float) AFTERGLOW_MS);
    }

    private static void fillWhite(GuiGraphics g, int width, int height, float alpha) {
        int a = (int) (Mth.clamp(alpha, 0f, 1f) * 255f);
        if (a <= 2) return;
        g.fill(0, 0, width, height, (a << 24) | WHITE_RGB);
    }
}

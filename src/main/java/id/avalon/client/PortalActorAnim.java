package id.avalon.client;

import id.avalon.cutscene.PortalTimeline;
import id.avalon.network.AvalonNetwork;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Animasi satu player yang tersedot portal: lintasan, putaran badan, dan pose tangan/kaki.
 * Semuanya fungsi murni dari waktu, jadi tiap client menggambar hal yang sama tanpa state.
 *
 * Tiap player lewat tiga tahap: menunggu (berdiri biasa, bebas menoleh), terangkat/meronta, lalu ditarik masuk.
 */
final class PortalActorAnim {

    private PortalActorAnim() {}

    // ── Gaya animasi ──────────────────────────────────────────────────────────
    /** Berputar seperti baling-baling, tangan terentang. */
    static final int PROPELLER = 0;
    /** Terseret kaki duluan sambil mencakar-cakar ke arah berlawanan. */
    static final int DRAGGED = 1;
    /** Jungkir balik ke depan sambil meringkuk. */
    static final int SOMERSAULT = 2;
    /** Meluncur kepala duluan sambil berputar seperti bor. */
    static final int SUPERMAN = 3;
    /** Lari menjauh di tempat, terseret mundur, lalu terlempar. */
    static final int RESIST = 4;
    /** Melingkar mengelilingi portal sambil meroda. */
    static final int SPIRAL = 5;
    /** Terpelanting tak beraturan, tangan-kaki lemas. */
    static final int RAGDOLL = 6;
    /** Terangkat terbalik seperti dijerat satu kaki, lalu ditarik. */
    static final int HOOKED = 7;

    /** Tinggi pusat putaran badan dari kaki (blok, sebelum skala player). */
    static final float PIVOT = 0.9f;

    private static final float LIFT = PortalTimeline.LIFT_TICKS;
    private static final float PULL = PortalTimeline.PULL_TICKS;
    private static final float TOTAL = PortalTimeline.PLAYER_TICKS;

    /** Data tetap satu player, dihitung sekali saat cutscene dimulai. */
    static final class Actor {
        final int entityId;
        final int delay;
        final int style;
        final float scale;
        /** Posisi kaki saat cutscene dimulai. */
        final Vec3 start;
        final Vec3 portal;
        /** Pusat badan saat diam, dan di akhir tahap terangkat. */
        final Vec3 origin;
        final Vec3 liftEnd;
        /** Arah mendatar ke portal. */
        final Vec3 toPortal;
        /** Yaw menghadap portal & sudut mendongak dari {@link #liftEnd} ke portal (derajat). */
        final float facing;
        final float elevation;
        /** Dua sumbu tegak lurus lintasan tarikan (untuk gerak melingkar). */
        final Vec3 perpA;
        final Vec3 perpB;
        final float phase;
        /** Yaw badan saat mulai berputar menghadap portal; NaN = belum mulai. */
        float turnFrom = Float.NaN;

        Actor(AvalonNetwork.PortalActor msg, Vec3 portal) {
            this.entityId = msg.entityId();
            this.delay = msg.delay();
            this.style = msg.style();
            this.scale = msg.scale();
            this.start = new Vec3(msg.x(), msg.y(), msg.z());
            this.portal = portal;
            this.origin = start.add(0, PIVOT * scale, 0);

            Vec3 flat = new Vec3(portal.x - start.x, 0, portal.z - start.z);
            this.toPortal = flat.lengthSqr() < 1.0e-4 ? new Vec3(0, 0, 1) : flat.normalize();
            this.facing = (float) (Mth.atan2(-toPortal.x, toPortal.z) * Mth.RAD_TO_DEG);

            this.liftEnd = switch (style) {
                case RESIST -> origin.add(toPortal.scale(2.2)).add(0, 0.15, 0);
                case HOOKED -> origin.add(toPortal.scale(0.4)).add(0, 2.4, 0);
                default -> origin.add(toPortal.scale(0.6)).add(0, 1.5, 0);
            };

            Vec3 path = portal.subtract(liftEnd);
            double flatLength = Math.sqrt(path.x * path.x + path.z * path.z);
            this.elevation = (float) (Mth.atan2(path.y, flatLength) * Mth.RAD_TO_DEG);

            Vec3 axis = path.normalize();
            Vec3 a = axis.cross(new Vec3(0, 1, 0));
            this.perpA = a.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : a.normalize();
            this.perpB = axis.cross(perpA);
            this.phase = (entityId * 2.399f) % Mth.TWO_PI;
        }
    }

    /** Keadaan satu player pada satu frame. */
    static final class Frame {
        /** false = sudah masuk portal, jangan digambar. */
        boolean visible = true;
        /** false = masih menunggu giliran (berdiri di tanah). */
        boolean airborne = false;
        /** Posisi pusat badan di dunia. */
        double x, y, z;
        /** Yaw arah hadap badan. */
        float facing;
        /** Putaran di kerangka badan: +X kanan, +Y atas, depan = -Z. */
        final Quaternionf local = new Quaternionf();
        float scale = 1f;
        /** Peregangan sepanjang badan saat hampir masuk portal. */
        float stretch = 1f;
        /** Progres tahap terangkat & ditarik, 0..1. */
        float lift, pull;

        private void at(Vec3 v) {
            x = v.x;
            y = v.y;
            z = v.z;
        }
    }

    // ── Lintasan & putaran badan ──────────────────────────────────────────────

    static Frame at(Actor a, float time) {
        Frame f = new Frame();
        f.facing = a.facing;
        float t = time - a.delay;

        if (t < 0) {
            f.at(a.origin);
            return f;
        }
        if (t >= TOTAL) {
            f.at(a.portal);
            f.visible = false;
            f.airborne = true;
            f.lift = 1f;
            f.pull = 1f;
            return f;
        }

        float u = Mth.clamp(t / LIFT, 0f, 1f);
        float v = Mth.clamp((t - LIFT) / PULL, 0f, 1f);
        float p = t / TOTAL;
        f.airborne = true;
        f.lift = u;
        f.pull = v;

        // Terangkat: sentakan cepat lalu melayang. RESIST tetap di tanah dan makin terseret.
        float rise = a.style == RESIST ? u * u : 1f - (1f - u) * (1f - u) * (1f - u);
        f.x = Mth.lerp(rise, a.origin.x, a.liftEnd.x);
        f.y = Mth.lerp(rise, a.origin.y, a.liftEnd.y);
        f.z = Mth.lerp(rise, a.origin.z, a.liftEnd.z);

        // Goyang saat melayang, hilang begitu ditarik
        float wobble = a.style == RESIST ? 0f : u * (1f - v);
        f.x += a.perpA.x * Mth.sin(t * 0.5f) * 0.12f * wobble;
        f.y += Mth.sin(t * 0.37f) * 0.08f * wobble;
        f.z += a.perpA.z * Mth.sin(t * 0.5f) * 0.12f * wobble;

        if (v > 0f) {
            // Ditarik: pelan di awal, melesat di akhir
            float e = (float) Math.pow(v, 2.6);
            f.x = Mth.lerp(e, f.x, a.portal.x);
            f.y = Mth.lerp(e, f.y, a.portal.y);
            f.z = Mth.lerp(e, f.z, a.portal.z);

            float bow = Mth.sin(v * Mth.PI);
            if (a.style == SPIRAL) {
                float angle = v * 4.5f * Mth.PI + a.phase;
                float radius = 2.6f * bow * (1f - 0.4f * e);
                f.x += (a.perpA.x * Mth.cos(angle) + a.perpB.x * Mth.sin(angle)) * radius;
                f.y += (a.perpA.y * Mth.cos(angle) + a.perpB.y * Mth.sin(angle)) * radius;
                f.z += (a.perpA.z * Mth.cos(angle) + a.perpB.z * Mth.sin(angle)) * radius;
            } else {
                f.y += 0.6f * bow * (1f - e);
            }

            f.scale = 1f - smooth(0.72f, 1f, v) * 0.93f;
            f.stretch = 1f + smooth(0.55f, 1f, v) * 1.4f;
        }

        Quaternionf q = f.local;
        switch (a.style) {
            case PROPELLER -> {
                q.rotateX(rad(-(20f * u + 35f * v)));
                q.rotateY(rad(1500f * p * p));
            }
            case DRAGGED -> {
                // Berbalik membelakangi portal, lalu rebah telungkup dengan kaki mengarah ke portal
                f.facing += 180f * smooth(0f, 0.35f, u);
                q.rotateX(rad(-(90f + a.elevation) * smooth(0.1f, 1f, u) + Mth.sin(t * 0.6f) * 4f));
                q.rotateZ(rad(Mth.sin(t * 0.5f) * 8f));
            }
            case SOMERSAULT -> q.rotateX(rad(-1080f * p * p));
            case SUPERMAN -> {
                q.rotateX(rad(-(90f - a.elevation) * smooth(0f, 1f, u)));
                q.rotateY(rad(900f * v * v));
            }
            case RESIST -> {
                f.facing += 180f * smooth(0f, 0.3f, u);
                float lean = -(18f + 6f * Mth.sin(t * 0.9f)) * Math.min(1f, u * 4f);
                q.rotateX(rad(lean + 800f * (float) Math.pow(v, 1.5)));
            }
            case SPIRAL -> {
                q.rotateX(rad(-30f * u));
                q.rotateZ(rad(1260f * p * p));
            }
            case RAGDOLL -> {
                q.rotateX(rad(700f * p * p));
                q.rotateZ(rad(520f * (float) Math.pow(p, 1.7)));
                q.rotateY(rad(300f * p));
            }
            case HOOKED -> {
                // Terayun sampai terbalik, lalu kaki mengarah ke portal
                float flipped = 172f * backOut(u);
                float angle = Mth.lerp(smooth(0f, 0.5f, v), flipped, 90f + a.elevation);
                q.rotateX(rad(angle));
                q.rotateZ(rad(Mth.sin(t * 0.35f) * 12f * (1f - v)));
            }
            default -> {
            }
        }
        return f;
    }

    // ── Pose tangan / kaki / kepala ───────────────────────────────────────────

    // Indeks sudut (radian): kepala x,y lalu x,y,z untuk tangan kanan, tangan kiri, kaki kanan, kaki kiri
    private static final int HEAD = 0, RIGHT_ARM = 2, LEFT_ARM = 5, RIGHT_LEG = 8, LEFT_LEG = 11, COUNT = 14;

    /** Timpa pose model player; dipanggil setelah setupAnim vanilla. */
    static void pose(PlayerModel<?> model, Actor a, float time) {
        float t = time - a.delay;
        // Selama masih di tanah, pose vanilla dibiarkan apa adanya
        if (t < 0f) return;

        // Kepala default-nya tetap mengikuti arah pandang player; gaya tertentu menimpanya
        float[] pose = new float[COUNT];
        pose[HEAD] = model.head.xRot;
        pose[HEAD + 1] = model.head.yRot;
        style(pose, a.style, t);
        float weight = smooth(0f, 5f, t);

        model.head.xRot = Mth.lerp(weight, model.head.xRot, pose[HEAD]);
        model.head.yRot = Mth.lerp(weight, model.head.yRot, pose[HEAD + 1]);
        apply(model.rightArm, pose, RIGHT_ARM, weight);
        apply(model.leftArm, pose, LEFT_ARM, weight);
        apply(model.rightLeg, pose, RIGHT_LEG, weight);
        apply(model.leftLeg, pose, LEFT_LEG, weight);

        // Lapisan luar skin mengikuti bagian dalamnya
        model.hat.copyFrom(model.head);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightPants.copyFrom(model.rightLeg);
        model.leftPants.copyFrom(model.leftLeg);
    }

    private static void apply(ModelPart part, float[] pose, int index, float weight) {
        part.xRot = Mth.lerp(weight, part.xRot, pose[index]);
        part.yRot = Mth.lerp(weight, part.yRot, pose[index + 1]);
        part.zRot = Mth.lerp(weight, part.zRot, pose[index + 2]);
    }

    private static void set(float[] pose, int index, float x, float y, float z) {
        pose[index] = x;
        pose[index + 1] = y;
        pose[index + 2] = z;
    }

    private static void style(float[] pose, int style, float t) {
        boolean pulled = t >= LIFT;
        switch (style) {
            case PROPELLER -> {
                float flap = Mth.sin(t * 0.8f) * 0.15f;
                set(pose, RIGHT_ARM, flap, 0f, 1.45f);
                set(pose, LEFT_ARM, -flap, 0f, -1.45f);
                set(pose, RIGHT_LEG, 0.2f, 0f, 0.35f);
                set(pose, LEFT_LEG, -0.2f, 0f, -0.35f);
            }
            case DRAGGED -> {
                float claw = Mth.sin(t * 0.7f) * 0.45f;
                float kick = Mth.sin(t * 0.9f) * 0.35f;
                pose[HEAD] = -0.7f;
                set(pose, RIGHT_ARM, -2.8f + claw, 0f, 0.15f);
                set(pose, LEFT_ARM, -2.8f - claw, 0f, -0.15f);
                set(pose, RIGHT_LEG, kick, 0f, 0.05f);
                set(pose, LEFT_LEG, -kick, 0f, -0.05f);
            }
            case SOMERSAULT -> {
                pose[HEAD] = 0.5f;
                set(pose, RIGHT_ARM, -1.2f, 0f, 0.3f);
                set(pose, LEFT_ARM, -1.2f, 0f, -0.3f);
                set(pose, RIGHT_LEG, -1.3f, 0f, 0.05f);
                set(pose, LEFT_LEG, -1.3f, 0f, -0.05f);
            }
            case SUPERMAN -> {
                float flutter = Mth.sin(t * 1.1f) * 0.25f;
                pose[HEAD] = -1.0f;
                set(pose, RIGHT_ARM, -3.0f, 0f, 0.12f);
                set(pose, LEFT_ARM, -3.0f, 0f, -0.12f);
                set(pose, RIGHT_LEG, flutter, 0f, 0.03f);
                set(pose, LEFT_LEG, -flutter, 0f, -0.03f);
            }
            case RESIST -> {
                if (!pulled) {
                    // Lari sekuat tenaga di tempat
                    float run = Mth.cos(t * 1.4f);
                    pose[HEAD] = 0.2f;
                    set(pose, RIGHT_ARM, run * 1.5f, 0f, 0.1f);
                    set(pose, LEFT_ARM, -run * 1.5f, 0f, -0.1f);
                    set(pose, RIGHT_LEG, -run * 1.3f, 0f, 0f);
                    set(pose, LEFT_LEG, run * 1.3f, 0f, 0f);
                } else {
                    float flail = Mth.sin(t * 1.3f) * 1.2f;
                    float kick = Mth.sin(t * 1.1f) * 0.9f;
                    set(pose, RIGHT_ARM, -1.5f + flail, 0f, 0.8f);
                    set(pose, LEFT_ARM, -1.5f - flail, 0f, -0.8f);
                    set(pose, RIGHT_LEG, kick, 0f, 0.2f);
                    set(pose, LEFT_LEG, -kick, 0f, -0.2f);
                }
            }
            case SPIRAL -> {
                float wiggle = Mth.sin(t * 0.9f) * 0.12f;
                set(pose, RIGHT_ARM, 0f, 0f, 1.9f + wiggle);
                set(pose, LEFT_ARM, 0f, 0f, -1.9f - wiggle);
                set(pose, RIGHT_LEG, 0f, 0f, 0.6f - wiggle);
                set(pose, LEFT_LEG, 0f, 0f, -0.6f + wiggle);
            }
            case RAGDOLL -> {
                pose[HEAD] = Mth.sin(t * 0.5f) * 0.5f;
                pose[HEAD + 1] = Mth.sin(t * 0.4f) * 0.6f;
                set(pose, RIGHT_ARM, Mth.sin(t * 0.9f) * 1.6f - 0.6f, 0f, 0.6f + Mth.sin(t * 0.7f) * 0.6f);
                set(pose, LEFT_ARM, Mth.sin(t * 1.1f + 2f) * 1.6f - 0.6f, 0f, -0.6f - Mth.sin(t * 0.6f + 1f) * 0.6f);
                set(pose, RIGHT_LEG, Mth.sin(t * 0.8f + 1f), 0f, 0.2f);
                set(pose, LEFT_LEG, Mth.sin(t * 1.0f + 3f), 0f, -0.2f);
            }
            case HOOKED -> {
                // Badan terbalik: tangan menjuntai ke arah kepala, satu kaki lurus (terjerat), satu menendang
                float dangle = Mth.sin(t * 0.45f) * 0.35f;
                pose[HEAD] = -0.4f;
                set(pose, RIGHT_ARM, -3.0f + dangle, 0f, 0.35f);
                set(pose, LEFT_ARM, -3.0f - dangle, 0f, -0.35f);
                set(pose, RIGHT_LEG, 0f, 0f, 0f);
                set(pose, LEFT_LEG, -1.1f + Mth.sin(t * 0.6f) * 0.4f, 0f, -0.3f);
            }
            default -> {
            }
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static float rad(float degrees) {
        return degrees * Mth.DEG_TO_RAD;
    }

    static float smooth(float from, float to, float value) {
        float k = Mth.clamp((value - from) / (to - from), 0f, 1f);
        return k * k * (3f - 2f * k);
    }

    /** Ease-out yang sedikit kebablasan lalu kembali. */
    static float backOut(float k) {
        float c = 1.70158f;
        float inv = k - 1f;
        return 1f + (c + 1f) * inv * inv * inv + c * inv * inv;
    }
}

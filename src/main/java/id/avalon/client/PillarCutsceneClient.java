package id.avalon.client;

import id.avalon.block.PillarBlock;
import id.avalon.cutscene.PillarTimeline;
import id.avalon.mixin.CameraAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ViewportEvent;

/**
 * Cutscene akhir misi: kamera lepas dari player dan menyorot pilar yang menyala atau meluap.
 * Satu gerakan kamera menerus: mulai rendah di kaki pilar, mendongak mengikuti ujung tiang cahaya,
 * lalu mundur & naik mengitari bola. Kalau bolanya pecah, kamera terguncang dan terdorong mundur.
 * Layar menggelap di akhir dan terang lagi setelah player kembali ke kursinya.
 */
final class PillarCutsceneClient {

    private PillarCutsceneClient() {}

    /** Jarak dari sumbu pilar tempat sinar pengecek halangan dimulai (di luar badan pilar). */
    private static final double CLEAR_RADIUS = 4.5;
    private static final int LETTERBOX_TICKS = 16;

    private static boolean active;
    private static BlockPos pillar;
    /** Tengah permukaan atas block pilar. */
    private static Vec3 base;
    /** Sudut (radian) arah rak baterai dari pilar: kamera mulai dari sisi itu. */
    private static float frontAngle;
    private static int duration;
    private static int age;
    private static float partialTick;
    /** Tick sejak cutscene berakhir, untuk terang-dari-gelap; -1 = tidak ada. */
    private static int outro = -1;

    static void start(BlockPos pos, int faceX, int faceZ, int durationTicks) {
        pillar = pos;
        base = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        frontAngle = (float) Mth.atan2(faceZ, faceX);
        duration = durationTicks;
        age = 0;
        outro = -1;
        active = true;
    }

    static void stop() {
        if (active) outro = 0;
        active = false;
    }

    static void reset() {
        active = false;
        outro = -1;
    }

    static boolean hasCamera() {
        return active;
    }

    static void setPartialTick(float partial) {
        partialTick = partial;
    }

    static void tick() {
        if (active) {
            // Pengaman kalau paket penutup tidak pernah datang
            if (++age > duration + 100) stop();
        } else if (outro >= 0 && ++outro > PillarTimeline.FADE_TICKS) {
            outro = -1;
        }
    }

    private static float time() {
        return age + partialTick;
    }

    // ── Kamera ────────────────────────────────────────────────────────────────

    /** Pasang kamera cutscene; false = tidak ada cutscene untuk client ini. */
    static boolean applyCamera(ViewportEvent.ComputeCameraAngles event) {
        if (!active) return false;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;

        float t = time();
        float lit = t - PillarTimeline.LEAD_TICKS;
        // Tiang naik (0..1), lalu kamera mundur ke sorotan bola (0..1)
        float rise = PortalActorAnim.smooth(0f, PillarBlock.RISE_TICKS, lit);
        float wide = PortalActorAnim.smooth(PillarBlock.RISE_TICKS - 12f, PillarBlock.RISE_TICKS + 55f, lit);

        // Meluap: makin lama makin terguncang, lalu terdorong mundur saat pecah
        float unstable = 0f;
        float blast = 0f;
        float jolt = 0f;
        BlockState state = level.getBlockState(pillar);
        if (state.getBlock() instanceof PillarBlock && state.getValue(PillarBlock.OVERLOAD)) {
            float burst = lit - PillarBlock.OVERLOAD_BURST_TICK;
            unstable = Mth.clamp((lit - PillarBlock.RISE_TICKS) / (PillarBlock.OVERLOAD_BURST_TICK - PillarBlock.RISE_TICKS), 0f, 1f);
            blast = PortalActorAnim.smooth(0f, 22f, burst);
            jolt = burst < 0f ? 0f : Math.max(0f, 1f - burst / 26f);
        }

        double radius = Mth.lerp(wide, Mth.lerp(rise, 6.5f, 9.0f), 15.5f) + 5.5 * blast;
        double height = Mth.lerp(wide, Mth.lerp(rise, 0.7f, 2.4f), 9.5f) + 1.5 * blast;
        double angle = frontAngle + Math.toRadians(32.0 + t * 0.38 + wide * 38.0);

        Vec3 out = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
        Vec3 wanted = base.add(out.x * radius, height, out.z * radius);
        Vec3 pos = unobstructed(level, base.add(out.x * CLEAR_RADIUS, height, out.z * CLEAR_RADIUS), wanted, out);

        // Sasaran pandang: badan pilar → ujung tiang yang sedang naik → bola
        double orbY = PillarBlock.ORB_HEIGHT - 0.5;
        double headY = orbY * PortalActorAnim.smooth(0f, 1f, Mth.clamp(lit / PillarBlock.RISE_TICKS, 0f, 1f));
        double lookY = Mth.lerp(PortalActorAnim.smooth(-10f, 14f, lit), 2.5, Math.max(2.5, headY));
        Vec3 look = base.add(0.0, lookY, 0.0);

        float shake = 0.05f * unstable * unstable + 0.45f * jolt * jolt;
        if (shake > 0f) {
            pos = pos.add(shake * Mth.sin(t * 2.9f), shake * Mth.sin(t * 3.7f + 1f), shake * Mth.sin(t * 4.3f + 2f));
        }

        Vec3 dir = look.subtract(pos);
        float yaw = (float) Math.toDegrees(Mth.atan2(-dir.x, dir.z));
        float pitch = (float) -Math.toDegrees(Mth.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z)));
        float roll = 1.8f * jolt * Mth.sin(t * 3.3f);

        CameraAccessor camera = (CameraAccessor) event.getCamera();
        camera.avalon$setPosition(pos.x, pos.y, pos.z);
        camera.avalon$setRotation(yaw, pitch);
        camera.avalon$setDetached(true);
        event.setYaw(yaw);
        event.setPitch(pitch);
        event.setRoll(roll);
        return true;
    }

    /** Kalau ada block di antara pilar dan posisi kamera, kamera berhenti sedikit sebelum block itu. */
    private static Vec3 unobstructed(ClientLevel level, Vec3 from, Vec3 wanted, Vec3 out) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return wanted;
        BlockHitResult hit = level.clip(new ClipContext(from, wanted,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) return wanted;
        return hit.getLocation().subtract(out.scale(0.4));
    }

    static double fov() {
        // Sedikit menyempit selama tiang naik (terasa megah), melebar lagi saat bolanya disorot
        float lit = time() - PillarTimeline.LEAD_TICKS;
        float wide = PortalActorAnim.smooth(PillarBlock.RISE_TICKS - 12f, PillarBlock.RISE_TICKS + 55f, lit);
        return Mth.lerp(wide, 62.0, 72.0);
    }

    // ── Layar ─────────────────────────────────────────────────────────────────

    /** Bilah hitam atas-bawah selama cutscene, plus gelap-terang di awal, akhir, dan sesudahnya. */
    static void renderOverlay(GuiGraphics graphics, int width, int height) {
        if (!active) {
            if (outro >= 0) fillBlack(graphics, width, height, 1f - (outro + partialTick) / PillarTimeline.FADE_TICKS);
            return;
        }
        float t = time();
        int bar = Math.round(height * 0.11f * PortalActorAnim.smooth(0f, LETTERBOX_TICKS, t));
        graphics.fill(0, 0, width, bar, 0xFF000000);
        graphics.fill(0, height - bar, width, height, 0xFF000000);

        // Terang dari gelap di awal (menutupi area yang baru dimuat), menggelap di akhir
        float fadeIn = 1f - t / PillarTimeline.FADE_TICKS;
        float fadeOut = (t - (duration - PillarTimeline.FADE_TICKS)) / PillarTimeline.FADE_TICKS;
        fillBlack(graphics, width, height, Math.max(fadeIn, fadeOut));
    }

    private static void fillBlack(GuiGraphics graphics, int width, int height, float alpha) {
        int a = Math.round(Mth.clamp(alpha, 0f, 1f) * 255f);
        if (a <= 0) return;
        graphics.fill(0, 0, width, height, a << 24);
    }
}

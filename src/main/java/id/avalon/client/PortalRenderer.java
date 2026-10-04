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
import id.avalon.AvalonMod;
import id.avalon.client.PortalCutsceneClient.Scene;
import id.avalon.cutscene.PortalTimeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Gambar portal cutscene di dunia: celah cahaya yang melebar jadi pusaran gelap
 * dengan lingkaran sihir berputar. Digambar langsung (bukan blok / entity), jadi bisa
 * muncul di mana saja tanpa mengubah dunia.
 */
final class PortalRenderer {

    private PortalRenderer() {}

    // Tekstur layar loading Avalon dipakai lagi di sini (dan di RoleShuffleClient)
    static final ResourceLocation CIRCLE = tex("circle");
    static final ResourceLocation MOTE = tex("mote");

    private static final int DISC_SEGMENTS = 48;
    private static final int ARMS = 5;
    private static final int ARM_SEGMENTS = 22;

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(AvalonMod.MOD_ID, "textures/gui/loading/" + name + ".png");
    }

    /** Filter linear supaya lingkaran tidak pecah saat diperbesar. */
    static void prepareTextures() {
        for (ResourceLocation t : new ResourceLocation[]{CIRCLE, MOTE}) {
            Minecraft.getInstance().getTextureManager().getTexture(t).setFilter(true, false);
        }
    }

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Scene s = PortalCutsceneClient.scene();
        if (s == null) return;

        float time = PortalCutsceneClient.time();
        float closeEnd = s.closeStart + PortalTimeline.CLOSE_TICKS;
        if (time >= closeEnd) return;

        float r = s.radius;
        float open = openness(s, time);
        // Kilatan tiap kali ada player yang masuk
        float pulse = Mth.clamp(1f - (time - s.pulseAt) / 12f, 0f, 1f);
        pulse *= pulse;

        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(s.portal.x - cam.x, s.portal.y - cam.y, s.portal.z - cam.z);
        // Bidang portal (XY lokal) menghadap para player
        pose.mulPose(Axis.YP.rotation((float) Mth.atan2(-s.dir.x, -s.dir.z)));
        Matrix4f m = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        if (open > 0.01f) {
            // Lubang gelap di tengah (blend biasa supaya benar-benar menutupi yang di belakangnya)
            RenderSystem.defaultBlendFunc();
            disc(m, r * 0.86f * open, 0.02f, 0f, 0.06f, 0.96f, 0.22f, 0.04f, 0.45f, 0.92f);
        }

        // Sisanya cahaya: aditif, saling menumpuk jadi makin terang
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);

        if (open > 0.01f) {
            float glow = Math.min(1f, open);
            quad(m, MOTE, r * 3.4f * open, 0f, -0.02f, 0.55f, 0.25f, 1.0f, (0.45f + 0.5f * pulse) * glow);
            arms(m, r * 0.84f * open, time, glow);
            quad(m, CIRCLE, r * 1.30f * open, time * -1.2f, 0.02f, 0.70f, 0.42f, 1.0f, 0.85f * glow);
            quad(m, CIRCLE, r * 0.96f * open, time * 2.6f, 0.04f, 1.0f, 0.70f, 1.0f, 0.95f * glow);
            quad(m, CIRCLE, r * 0.50f * open, time * -5.0f, 0.06f, 1.0f, 0.88f, 0.75f, 0.70f * glow);
            // Inti terang, menyala lebih kuat saat ada yang masuk
            quad(m, MOTE, r * (0.7f + 1.6f * pulse) * open, 0f, 0.08f, 1.0f, 0.85f, 1.0f, (0.35f + 0.65f * pulse) * glow);
        }

        slit(m, r, time);

        // Gelombang kejut saat portal terbuka & tiap ada yang masuk
        ring(m, r, (time - PortalTimeline.OPEN_START - 4f) / 20f, 0.6f, 3.4f, 0.85f);
        ring(m, r, (time - s.pulseAt) / 12f, 0.9f, 1.9f, 0.6f);

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    /** Ukuran portal 0..1: membesar dengan sedikit kebablasan, mengembang sebentar lalu menciut saat menutup. */
    private static float openness(Scene s, float time) {
        if (time < PortalTimeline.OPEN_START) return 0f;
        float opening = (time - PortalTimeline.OPEN_START) / (PortalTimeline.OPEN_END - PortalTimeline.OPEN_START);
        float open = opening >= 1f ? 1f : PortalActorAnim.backOut(opening);
        if (time > s.closeStart) {
            float c = Mth.clamp((time - s.closeStart) / PortalTimeline.CLOSE_TICKS, 0f, 1f);
            open *= (1f + 0.25f * Mth.sin(c * Mth.PI)) * (1f - c * c * c);
        }
        // Denyut pelan
        return open * (1f + 0.02f * Mth.sin(time * 0.3f));
    }

    /** Celah cahaya tegak yang memanjang sebelum portal terbuka, lalu memudar. */
    private static void slit(Matrix4f m, float r, float time) {
        float fade = 1f - PortalActorAnim.smooth(PortalTimeline.OPEN_START, PortalTimeline.OPEN_START + 14f, time);
        if (fade <= 0f) return;
        float grow = PortalActorAnim.smooth(0f, PortalTimeline.OPEN_START, time);
        float flicker = 0.7f + 0.3f * Mth.sin(time * 2.3f) * Mth.sin(time * 5.1f);
        float h = r * 0.95f * grow;
        float w = r * (0.03f + 0.05f * grow) * flicker;

        diamond(m, w * 6f, h * 1.1f, 0.10f, 0.6f, 0.3f, 1.0f, 0.6f * fade * flicker);
        diamond(m, w * 2f, h, 0.12f, 1.0f, 0.92f, 1.0f, fade);
    }

    // ── Bentuk ────────────────────────────────────────────────────────────────

    /** Persegi bertekstur (setengah sisi = {@code half}), diputar {@code degrees} di bidang portal. */
    static void quad(Matrix4f m, ResourceLocation texture, float half, float degrees, float z,
                             float r, float g, float b, float a) {
        if (a <= 0.003f || half <= 0f) return;
        float cos = Mth.cos(degrees * Mth.DEG_TO_RAD) * half;
        float sin = Mth.sin(degrees * Mth.DEG_TO_RAD) * half;

        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        // Sudut (-1,-1), (1,-1), (1,1), (-1,1) setelah diputar
        buf.vertex(m, -cos + sin, -sin - cos, z).uv(0f, 1f).color(r, g, b, a).endVertex();
        buf.vertex(m, cos + sin, sin - cos, z).uv(1f, 1f).color(r, g, b, a).endVertex();
        buf.vertex(m, cos - sin, sin + cos, z).uv(1f, 0f).color(r, g, b, a).endVertex();
        buf.vertex(m, -cos - sin, -sin + cos, z).uv(0f, 0f).color(r, g, b, a).endVertex();
        BufferUploader.drawWithShader(buf.end());
    }

    /** Cakram dengan warna tengah → warna tepi. */
    private static void disc(Matrix4f m, float radius,
                             float r0, float g0, float b0, float a0,
                             float r1, float g1, float b1, float a1) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(m, 0f, 0f, 0f).color(r0, g0, b0, a0).endVertex();
        for (int i = 0; i <= DISC_SEGMENTS; i++) {
            float angle = i * Mth.TWO_PI / DISC_SEGMENTS;
            buf.vertex(m, Mth.cos(angle) * radius, Mth.sin(angle) * radius, 0f).color(r1, g1, b1, a1).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }

    /** Lengan-lengan pusaran: terang di sisi depan putaran, memudar ke belakang seperti ekor komet. */
    private static void arms(Matrix4f m, float radius, float time, float alpha) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float spin = time * 0.11f;
        float twist = 4.2f;
        float tail = 0.55f;
        for (int arm = 0; arm < ARMS; arm++) {
            float base = arm * Mth.TWO_PI / ARMS + spin;
            for (int i = 0; i < ARM_SEGMENTS; i++) {
                float q0 = i / (float) ARM_SEGMENTS;
                float q1 = (i + 1) / (float) ARM_SEGMENTS;
                armVertex(buf, m, radius, base, twist, q0, 0f, alpha);
                armVertex(buf, m, radius, base, twist, q1, 0f, alpha);
                armVertex(buf, m, radius, base, twist, q1, tail, 0f);
                armVertex(buf, m, radius, base, twist, q0, tail, 0f);
            }
        }
        BufferUploader.drawWithShader(buf.end());
    }

    private static void armVertex(BufferBuilder buf, Matrix4f m, float radius, float base, float twist,
                                  float q, float behind, float alpha) {
        float angle = base - q * twist - behind;
        float rr = radius * q;
        // Putih kemerahan di tengah → ungu di tepi; redup di kedua ujung lengan
        float a = alpha * 0.55f * Mth.sqrt(Mth.sin(q * Mth.PI));
        buf.vertex(m, Mth.cos(angle) * rr, Mth.sin(angle) * rr, 0.01f)
                .color(Mth.lerp(q, 1.0f, 0.55f), Mth.lerp(q, 0.75f, 0.20f), 1.0f, a).endVertex();
    }

    /** Cincin tipis yang membesar & memudar; {@code progress} di luar 0..1 = tidak digambar. */
    private static void ring(Matrix4f m, float r, float progress, float from, float to, float alpha) {
        if (progress <= 0f || progress >= 1f) return;
        float radius = r * Mth.lerp(progress, from, to);
        float thickness = r * 0.35f * (1f - progress);
        float a = alpha * (1f - progress);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < DISC_SEGMENTS; i++) {
            float a0 = i * Mth.TWO_PI / DISC_SEGMENTS;
            float a1 = (i + 1) * Mth.TWO_PI / DISC_SEGMENTS;
            float inner = radius - thickness;
            buf.vertex(m, Mth.cos(a0) * inner, Mth.sin(a0) * inner, 0.09f).color(0.7f, 0.4f, 1f, 0f).endVertex();
            buf.vertex(m, Mth.cos(a1) * inner, Mth.sin(a1) * inner, 0.09f).color(0.7f, 0.4f, 1f, 0f).endVertex();
            buf.vertex(m, Mth.cos(a1) * radius, Mth.sin(a1) * radius, 0.09f).color(1f, 0.85f, 1f, a).endVertex();
            buf.vertex(m, Mth.cos(a0) * radius, Mth.sin(a0) * radius, 0.09f).color(1f, 0.85f, 1f, a).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }

    /** Belah ketupat tegak: terang di tengah, memudar ke semua ujung. */
    private static void diamond(Matrix4f m, float w, float h, float z, float r, float g, float b, float a) {
        if (a <= 0.003f || h <= 0f) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(m, 0f, 0f, z).color(r, g, b, a).endVertex();
        buf.vertex(m, 0f, h, z).color(r, g, b, 0f).endVertex();
        buf.vertex(m, -w, 0f, z).color(r, g, b, 0f).endVertex();
        buf.vertex(m, 0f, -h, z).color(r, g, b, 0f).endVertex();
        buf.vertex(m, w, 0f, z).color(r, g, b, 0f).endVertex();
        buf.vertex(m, 0f, h, z).color(r, g, b, 0f).endVertex();
        BufferUploader.drawWithShader(buf.end());
    }
}

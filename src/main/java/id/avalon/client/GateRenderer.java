package id.avalon.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import id.avalon.AvalonMod;
import id.avalon.block.GateBlock;
import id.avalon.core.AvalonDimensions;
import id.avalon.world.AvalonGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Gambar isi portal raksasa. Blok portalnya sendiri (GateBlock) tidak punya model; di sini semua
 * blok portal di cincin digambar sebagai SATU gambar utuh yang terpotong tepat mengikuti bloknya:
 * nebula ungu layar loading Avalon yang berputar pelan, lapisan kedua berputar berlawanan,
 * riak cahaya dari tengah, dan tepi terang di bagian yang sedang menyebar.
 */
final class GateRenderer {

    private GateRenderer() {}

    private static final ResourceLocation NEBULA =
            new ResourceLocation(AvalonMod.MOD_ID, "textures/gui/loading/background.png");
    /** Gambar nebula 16:9; tinggi gambarnya dipetakan ke lebar portal ini (blok). */
    private static final float NEBULA_ASPECT = 1280f / 720f;

    /** Tengah cincin & setengah lebar daerah yang diperiksa (lihat AvalonGate). */
    private static final int CENTER_X = AvalonGate.CENTER_X;
    private static final int CENTER_Y = AvalonGate.CENTER_Y;
    private static final int PLANE_Z = AvalonGate.PLANE_Z;
    private static final int REACH = 14;

    private static boolean textureReady;

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level.dimension() != AvalonDimensions.AVALON) return;

        Vec3 cam = event.getCamera().getPosition();
        if (cam.distanceToSqr(CENTER_X, CENTER_Y, PLANE_Z) > 220.0 * 220.0) return;

        // Blok portal yang sedang terpasang, dan mana yang ada di tepi sebaran (tetangganya masih udara)
        List<int[]> cells = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = CENTER_X - REACH; x <= CENTER_X + REACH; x++) {
            for (int y = CENTER_Y - REACH; y <= CENTER_Y + REACH; y++) {
                if (!(level.getBlockState(pos.set(x, y, PLANE_Z)).getBlock() instanceof GateBlock)) continue;
                boolean frontier = level.getBlockState(pos.set(x + 1, y, PLANE_Z)).isAir()
                        || level.getBlockState(pos.set(x - 1, y, PLANE_Z)).isAir()
                        || level.getBlockState(pos.set(x, y + 1, PLANE_Z)).isAir()
                        || level.getBlockState(pos.set(x, y - 1, PLANE_Z)).isAir();
                cells.add(new int[]{x, y, frontier ? 1 : 0});
            }
        }
        if (cells.isEmpty()) return;

        if (!textureReady) {
            mc.getTextureManager().getTexture(NEBULA).setFilter(true, false);
            PortalRenderer.prepareTextures();
            textureReady = true;
        }

        float t = (level.getGameTime() % 100000L) + event.getPartialTick();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // Titik asal = tengah portal, di tengah ketebalan bloknya
        pose.translate(CENTER_X + 0.5 - cam.x, CENTER_Y + 0.5 - cam.y, PLANE_Z + 0.5 - cam.z);
        Matrix4f m = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);

        // Dasar: nebula yang berputar pelan, menutupi apa pun di belakang portal
        RenderSystem.defaultBlendFunc();
        layer(m, cells, NEBULA, 30f, NEBULA_ASPECT, t * 0.25f, t, 0.80f, 0.72f, 1.0f, 0.97f, false);

        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        // Lapisan kedua berputar berlawanan: cahayanya bergeser-geser di atas dasar
        layer(m, cells, NEBULA, 38f, NEBULA_ASPECT, -t * 0.6f + 90f, t, 0.85f, 0.55f, 1.0f, 0.42f, false);
        // Inti terang di tengah
        layer(m, cells, PortalRenderer.MOTE, 16f, 1f, 0f, t, 1.0f, 0.85f, 1.0f, 0.55f, false);
        // Tepi sebaran menyala putih
        layer(m, cells, PortalRenderer.MOTE, 2f, 1f, 0f, t, 1.0f, 0.95f, 1.0f, 0.9f, true);

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    /**
     * Satu lapisan gambar yang membentang di seluruh portal, dipotong per blok.
     *
     * @param span     lebar daerah (blok) yang ditutupi tinggi gambar; di luar itu gambarnya habis
     * @param aspect   lebar / tinggi gambar
     * @param degrees  putaran gambar mengelilingi tengah portal
     * @param frontier true = hanya blok di tepi sebaran, masing-masing digambar penuh satu tekstur
     */
    private static void layer(Matrix4f m, List<int[]> cells, ResourceLocation texture, float span, float aspect,
                              float degrees, float t, float r, float g, float b, float alpha, boolean frontier) {
        RenderSystem.setShaderTexture(0, texture);
        float cos = Mth.cos(degrees * Mth.DEG_TO_RAD);
        float sin = Mth.sin(degrees * Mth.DEG_TO_RAD);

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int[] cell : cells) {
            if (frontier && cell[2] == 0) continue;
            // Pojok blok relatif ke tengah portal
            float x0 = cell[0] - CENTER_X - 0.5f, y0 = cell[1] - CENTER_Y - 0.5f;
            for (int corner = 0; corner < 4; corner++) {
                float x = x0 + (corner == 1 || corner == 2 ? 1f : 0f);
                float y = y0 + (corner >= 2 ? 1f : 0f);
                float u, v;
                if (frontier) {
                    u = corner == 1 || corner == 2 ? 1f : 0f;
                    v = corner >= 2 ? 0f : 1f;
                } else {
                    float rx = x * cos - y * sin;
                    float ry = x * sin + y * cos;
                    u = 0.5f + rx / (span * aspect);
                    v = 0.5f - ry / span;
                }
                // Riak cahaya yang merambat dari tengah ke tepi
                float ripple = frontier ? 1f : 0.82f + 0.18f * Mth.sin(Mth.sqrt(x * x + y * y) * 0.9f - t * 0.25f);
                buf.vertex(m, x, y, 0f).uv(u, v).color(r * ripple, g * ripple, b * ripple, alpha).endVertex();
            }
        }
        BufferUploader.drawWithShader(buf.end());
    }
}

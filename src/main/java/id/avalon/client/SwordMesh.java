package id.avalon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import id.avalon.AvalonMod;
import id.avalon.core.AvalonLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Model 3D pedang (assets/avalon/meshes/excalibur.obj): tegak di sumbu Y, gagang di y=0,
 * ujung bilah di y=1, lebar bilah di sumbu X. Dibaca sekali dari resource pack lalu disimpan.
 */
final class SwordMesh {

    private SwordMesh() {}

    private static final ResourceLocation MESH = new ResourceLocation(AvalonMod.MOD_ID, "meshes/excalibur.obj");
    private static final ResourceLocation TEXTURE = new ResourceLocation(AvalonMod.MOD_ID, "textures/entity/excalibur.png");

    /** Per titik segitiga: x, y, z, u, v, nx, ny, nz. Null = belum dibaca; kosong = gagal dibaca. */
    private static float[] vertices;

    private static final int STRIDE = 8;

    private static float[] vertices() {
        if (vertices != null) return vertices;
        vertices = new float[0];
        List<float[]> pos = new ArrayList<>();
        List<float[]> uv = new ArrayList<>();
        List<float[]> nrm = new ArrayList<>();
        List<Float> out = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                Minecraft.getInstance().getResourceManager().open(MESH), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] s = line.trim().split("\\s+");
                switch (s[0]) {
                    case "v" -> pos.add(new float[]{f(s[1]), f(s[2]), f(s[3])});
                    // OBJ menghitung v dari bawah, tekstur Minecraft dari atas
                    case "vt" -> uv.add(new float[]{f(s[1]), 1f - f(s[2])});
                    case "vn" -> nrm.add(new float[]{f(s[1]), f(s[2]), f(s[3])});
                    case "f" -> {
                        for (int k = 1; k <= 3; k++) {
                            String[] idx = s[k].split("/");
                            float[] p = pos.get(Integer.parseInt(idx[0]) - 1);
                            float[] t = uv.get(Integer.parseInt(idx[1]) - 1);
                            float[] n = nrm.get(Integer.parseInt(idx[2]) - 1);
                            out.add(p[0]); out.add(p[1]); out.add(p[2]);
                            out.add(t[0]); out.add(t[1]);
                            out.add(n[0]); out.add(n[1]); out.add(n[2]);
                        }
                    }
                    default -> {
                    }
                }
            }
            float[] v = new float[out.size()];
            for (int i = 0; i < v.length; i++) v[i] = out.get(i);
            vertices = v;
        } catch (Exception e) {
            AvalonLog.error("Gagal membaca model pedang " + MESH, e);
        }
        return vertices;
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }

    /**
     * Gambar pedang dengan transformasi {@code pose} (satuan model: panjang 1).
     * Selalu terang (tidak tergantung cahaya dunia), tetap diberi bayangan dari arah normal.
     */
    static void render(PoseStack pose, MultiBufferSource.BufferSource buffers, float alpha) {
        float[] v = vertices();
        if (v.length == 0 || alpha <= 0.01f) return;

        RenderType type = RenderType.entityTranslucent(TEXTURE);
        VertexConsumer vc = buffers.getBuffer(type);
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        int light = LightTexture.FULL_BRIGHT;

        // RenderType entity memakai mode QUADS: tiap segitiga dikirim sebagai segi empat
        // dengan titik terakhir diulang
        for (int i = 0; i < v.length; i += STRIDE * 3) {
            for (int k = 0; k < 4; k++) {
                int o = i + Math.min(k, 2) * STRIDE;
                vc.vertex(m, v[o], v[o + 1], v[o + 2])
                        .color(1f, 1f, 1f, alpha)
                        .uv(v[o + 3], v[o + 4])
                        .overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(light)
                        .normal(n, v[o + 5], v[o + 6], v[o + 7])
                        .endVertex();
            }
        }
        buffers.endBatch(type);
    }
}

package id.avalon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import id.avalon.block.PillarBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * Tidak menggambar apa-apa sendiri: hanya melaporkan pilar yang sedang terlihat ke
 * {@link PillarOrbRenderer}, yang menggambar cahayanya setelah awan.
 */
public class PillarRenderer implements BlockEntityRenderer<PillarBlockEntity> {

    public PillarRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(PillarBlockEntity pillar, float partialTick, PoseStack pose, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        PillarOrbRenderer.track(pillar, partialTick);
    }

    /** Bola tetap digambar walau block-nya sendiri di luar layar. */
    @Override
    public boolean shouldRenderOffScreen(PillarBlockEntity pillar) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}

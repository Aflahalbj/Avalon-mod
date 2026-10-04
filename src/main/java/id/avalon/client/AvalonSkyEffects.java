package id.avalon.client;

import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.phys.Vec3;

/**
 * Efek langit dimensi Avalon: tanpa awan, tanpa warna jingga matahari terbenam,
 * kabut tebal (seperti Nether) dengan warna persis fog_color biome.
 */
public class AvalonSkyEffects extends DimensionSpecialEffects {

    public AvalonSkyEffects() {
        super(Float.NaN, true, SkyType.NORMAL, false, false);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 color, float brightness) {
        return color;
    }

    @Override
    public boolean isFoggyAt(int x, int z) {
        return true;
    }

    @Override
    public float[] getSunriseColor(float timeOfDay, float partialTicks) {
        return null;
    }
}

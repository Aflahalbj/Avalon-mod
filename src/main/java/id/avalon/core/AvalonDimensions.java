package id.avalon.core;

import id.avalon.AvalonMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Dimensi milik mod. Isinya didefinisikan lewat JSON di data/avalon/dimension(_type).
 */
public final class AvalonDimensions {

    private AvalonDimensions() {}

    /** Id dimensi Avalon; dipakai juga sebagai id dimension_type dan efek langitnya. */
    public static final ResourceLocation AVALON_ID = new ResourceLocation(AvalonMod.MOD_ID, "avalon");

    public static final ResourceKey<Level> AVALON = ResourceKey.create(Registries.DIMENSION, AVALON_ID);

    /**
     * Seed tetap dimensi Avalon, supaya terrain-nya sama persis di semua world
     * (tidak ikut seed world). Lihat ServerLevelMixin.
     */
    public static final long AVALON_SEED = 20261002L;
}

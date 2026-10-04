package id.avalon.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Block entity pilar: tidak menyimpan data, hanya ada supaya pilar bisa
 * memancarkan partikel tiap tick dan bola kristalnya bisa digambar.
 */
public class PillarBlockEntity extends BlockEntity {

    /** Lama satu partikel naik dari block sampai bola (tick). */
    private static final int RISE_TICKS = 40;
    private static final int STRANDS = 2;
    private static final float STRAND_RADIUS = 0.35f;

    public PillarBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.PILLAR_ENTITY.get(), pos, state);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, PillarBlockEntity pillar) {
        if (!state.getValue(PillarBlock.LIT)) return;

        long time = level.getGameTime();
        double cx = pos.getX() + 0.5;
        double cz = pos.getZ() + 0.5;
        double bottom = pos.getY() + 1.0;
        double orbY = pos.getY() + PillarBlock.ORB_HEIGHT + 0.5;

        // Untai spiral yang naik dari block ke bola. Partikelnya bertahan ±3 detik,
        // jadi jejaknya menutup seluruh tinggi pilar. Always visible: terlihat dari jauh.
        for (int strand = 0; strand < STRANDS; strand++) {
            float phase = strand / (float) STRANDS;
            float rise = ((time % RISE_TICKS) / (float) RISE_TICKS + phase) % 1f;
            float angle = time * 0.35f + phase * Mth.TWO_PI;
            level.addAlwaysVisibleParticle(ParticleTypes.END_ROD, true,
                    cx + Mth.cos(angle) * STRAND_RADIUS,
                    bottom + rise * (orbY - bottom),
                    cz + Mth.sin(angle) * STRAND_RADIUS,
                    0.0, 0.03, 0.0);
        }

        // Huruf-huruf sihir tersedot ke bola
        RandomSource random = level.random;
        level.addAlwaysVisibleParticle(ParticleTypes.ENCHANT, true, cx, orbY, cz,
                (random.nextDouble() - 0.5) * 5.0, (random.nextDouble() - 0.5) * 5.0, (random.nextDouble() - 0.5) * 5.0);
    }

    /** Bola ada jauh di atas block: kotak render harus mencakup sampai ke sana, termasuk pendarnya. */
    @Override
    public AABB getRenderBoundingBox() {
        BlockPos pos = getBlockPos();
        return new AABB(pos, pos.offset(1, PillarBlock.ORB_HEIGHT + 1, 1)).inflate(4.0);
    }
}

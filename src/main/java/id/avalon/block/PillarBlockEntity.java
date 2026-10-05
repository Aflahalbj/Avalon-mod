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
 * Block entity pilar: tidak menyimpan data. Di client ia memegang jalannya animasi
 * (seberapa tinggi tiang, seberapa besar bola) dan memancarkan partikel tiap tick.
 */
public class PillarBlockEntity extends BlockEntity {

    /** Lama bola mengecil / tiang turun saat dimatikan (tick). */
    private static final float FADE_TICKS = 14f;
    private static final float FALL_TICKS = 8f;

    /** Lama satu partikel spiral naik dari block sampai bola (tick). */
    private static final int HELIX_TICKS = 40;
    private static final int STRANDS = 2;
    private static final float STRAND_RADIUS = 0.35f;

    // ── Animasi (client) ──────────────────────────────────────────────────────
    private boolean started;
    private float beam, lastBeam;
    private float power, lastPower;
    /** Tick sejak bola mulai terbentuk (untuk gelombang kejut); besar = sudah lama menyala. */
    private int orbAge = 1000;
    /** Tick sejak mulai meluap; -1 = tidak sedang meluap. */
    private int overloadAge = -1;

    public PillarBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.PILLAR_ENTITY.get(), pos, state);
    }

    /** Tinggi tiang cahaya 0..1. */
    public float beam(float partialTick) {
        return Mth.lerp(partialTick, lastBeam, beam);
    }

    /** Besar bola 0..1. */
    public float power(float partialTick) {
        return Mth.lerp(partialTick, lastPower, power);
    }

    public float orbAge(float partialTick) {
        return orbAge + partialTick;
    }

    /** Tick sejak mulai meluap, atau -1. */
    public float overloadAge(float partialTick) {
        return overloadAge < 0 ? -1f : overloadAge + partialTick;
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, PillarBlockEntity pillar) {
        pillar.animate(state);
        pillar.particles(level, pos);
    }

    private void animate(BlockState state) {
        boolean lit = state.getValue(PillarBlock.LIT);
        boolean overload = state.getValue(PillarBlock.OVERLOAD);

        if (!started) {
            // Pilar yang sudah menyala saat pertama terlihat langsung tampil penuh
            started = true;
            beam = power = lit ? 1f : 0f;
        }
        lastBeam = beam;
        lastPower = power;

        if (overload) {
            // Animasi meluap punya linimasanya sendiri; setelah selesai pilar mati tanpa animasi turun
            overloadAge++;
            beam = power = 0f;
            return;
        }
        overloadAge = -1;

        if (lit) {
            // Tiang naik dulu; bola baru terbentuk setelah tiangnya sampai puncak
            if (beam < 1f) {
                beam = Math.min(1f, beam + 1f / PillarBlock.RISE_TICKS);
                orbAge = 0;
            } else {
                power = Math.min(1f, power + 1f / PillarBlock.IGNITE_TICKS);
                if (orbAge < 1000) orbAge++;
            }
        } else if (power > 0f) {
            // Kebalikannya: bola padam dulu, baru tiangnya turun
            power = Math.max(0f, power - 1f / FADE_TICKS);
        } else {
            beam = Math.max(0f, beam - 1f / FALL_TICKS);
        }
    }

    private void particles(Level level, BlockPos pos) {
        double cx = pos.getX() + 0.5;
        double cz = pos.getZ() + 0.5;
        double bottom = pos.getY() + 1.0;
        double orbY = pos.getY() + PillarBlock.ORB_HEIGHT + 0.5;
        RandomSource random = level.random;
        long time = level.getGameTime();

        // Seberapa tinggi tiangnya sekarang: partikel tidak boleh mendahului tiang
        float reach = overloadAge >= 0
                ? (overloadAge < PillarBlock.OVERLOAD_BURST_TICK
                        ? Math.min(1f, overloadAge / (float) PillarBlock.RISE_TICKS)
                        : 0f)
                : beam;
        if (reach <= 0f) return;

        // Untai spiral yang naik dari block ke bola. Partikelnya bertahan ±3 detik,
        // jadi jejaknya menutup seluruh tinggi pilar. Always visible: terlihat dari jauh.
        for (int strand = 0; strand < STRANDS; strand++) {
            float phase = strand / (float) STRANDS;
            float rise = ((time % HELIX_TICKS) / (float) HELIX_TICKS + phase) % 1f;
            if (rise > reach) continue;
            float angle = time * 0.35f + phase * Mth.TWO_PI;
            level.addAlwaysVisibleParticle(ParticleTypes.END_ROD, true,
                    cx + Mth.cos(angle) * STRAND_RADIUS,
                    bottom + rise * (orbY - bottom),
                    cz + Mth.sin(angle) * STRAND_RADIUS,
                    0.0, 0.03, 0.0);
        }

        if (overloadAge > PillarBlock.RISE_TICKS) {
            // Energi meluap: percikan liar di sepanjang tiang
            for (int i = 0; i < 3; i++) {
                level.addAlwaysVisibleParticle(ParticleTypes.ELECTRIC_SPARK, true,
                        cx + (random.nextDouble() - 0.5) * 0.8,
                        bottom + random.nextDouble() * (orbY - bottom),
                        cz + (random.nextDouble() - 0.5) * 0.8,
                        (random.nextDouble() - 0.5) * 0.6, (random.nextDouble() - 0.5) * 0.6, (random.nextDouble() - 0.5) * 0.6);
            }
        } else if (power > 0f) {
            // Huruf-huruf sihir tersedot ke bola
            level.addAlwaysVisibleParticle(ParticleTypes.ENCHANT, true, cx, orbY, cz,
                    (random.nextDouble() - 0.5) * 5.0, (random.nextDouble() - 0.5) * 5.0, (random.nextDouble() - 0.5) * 5.0);
        }
    }

    /** Bola ada jauh di atas block: kotak render harus mencakup sampai ke sana, termasuk pendar & ledakannya. */
    @Override
    public AABB getRenderBoundingBox() {
        BlockPos pos = getBlockPos();
        return new AABB(pos, pos.offset(1, PillarBlock.ORB_HEIGHT + 1, 1)).inflate(10.0);
    }
}

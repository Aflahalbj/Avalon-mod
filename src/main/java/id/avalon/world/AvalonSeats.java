package id.avalon.world;

import id.avalon.core.AvalonDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Tempat duduk di dimensi Avalon: crimson slab melingkar di tepi lingkaran 19x19
 * (di bibir yang mengelilingi lantai barrier), satu slab per player terdaftar.
 */
public final class AvalonSeats {

    private AvalonSeats() {}

    /** Titik tengah lingkaran; slab dipasang di ketinggian ini (rata dengan permukaan lantai barrier). */
    public static final BlockPos CENTER = new BlockPos(-422, 192, -510);

    /**
     * Posisi kursi relatif ke {@link #CENTER} (x, z), di lingkaran radius 9, rata tiap 36 derajat.
     * Urutannya mengikuti urutan daftar player: kursi ke-1 di selatan, ke-2 di seberangnya,
     * lalu berselang-seling.
     */
    public static final int[][] OFFSETS = {
        {0, 9}, {0, -9}, {-9, 3}, {9, -3}, {-9, -3},
        {9, 3}, {-5, 7}, {5, -7}, {-5, -7}, {5, 7},
    };

    /** Posisi kursi versi lama (lingkaran 17x17); slab yang tertinggal di sana dicabut. */
    private static final int[][] OLD_OFFSETS = {
        {0, 8}, {0, -8}, {-8, 2}, {8, -2}, {-8, -2},
        {8, 2}, {-5, 6}, {5, -6}, {-5, -6}, {5, 6},
    };

    public static BlockPos pos(int index) {
        return CENTER.offset(OFFSETS[index][0], 0, OFFSETS[index][1]);
    }

    /**
     * Samakan jumlah slab dengan jumlah player terdaftar: {@code count} kursi pertama dipasang,
     * sisanya dicabut. Kursi yang dicabut hanya dihapus kalau bloknya memang crimson slab.
     */
    public static void sync(MinecraftServer server, int count) {
        ServerLevel level = server.getLevel(AvalonDimensions.AVALON);
        if (level == null) return;

        for (int[] old : OLD_OFFSETS) {
            BlockPos pos = CENTER.offset(old[0], 0, old[1]);
            if (level.getBlockState(pos).is(Blocks.CRIMSON_SLAB)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }

        BlockState slab = Blocks.CRIMSON_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        for (int i = 0; i < OFFSETS.length; i++) {
            BlockPos pos = pos(i);
            if (i < count) {
                level.setBlock(pos, slab, 3);
            } else if (level.getBlockState(pos).is(Blocks.CRIMSON_SLAB)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }
}

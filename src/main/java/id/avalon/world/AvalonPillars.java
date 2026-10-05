package id.avalon.world;

import id.avalon.AvalonMod;
import id.avalon.block.PillarBlock;
import id.avalon.core.AvalonDimensions;
import id.avalon.core.AvalonLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Pilar-pilar misi di dimensi Avalon (data/avalon/schematics/pillar.schem).
 * Ditempel otomatis sekali per world, dan ditempel ulang kalau file schematic-nya
 * atau daftar lokasinya berubah.
 */
public final class AvalonPillars {

    private AvalonPillars() {}

    private static final ResourceLocation SCHEMATIC = new ResourceLocation(AvalonMod.MOD_ID, "schematics/pillar.schem");

    /** Arah rak baterai di file schematic; pilar lain diputar dari sini. */
    private static final Direction SCHEMATIC_FACING = Direction.WEST;

    /** Letak block pilar & rak baterai relatif terhadap posisi //paste, sebelum diputar. */
    private static final BlockPos PILLAR_OFFSET = new BlockPos(0, -1, 0);
    private static final BlockPos RACK_OFFSET = new BlockPos(-2, -1, 0);

    /**
     * Satu pilar.
     *
     * @param origin posisi "berdiri saat //paste" (sama seperti WorldEdit): tengah pilar, 1 di atas dasarnya
     * @param facing arah rak baterainya menghadap
     */
    public record Site(BlockPos origin, Direction facing) {

        private Rotation rotation() {
            for (Rotation rotation : Rotation.values()) {
                if (rotation.rotate(SCHEMATIC_FACING) == facing) return rotation;
            }
            return Rotation.NONE;
        }

        /** Posisi block pilar (pemancar cahaya) di tengah dasar. */
        public BlockPos pillarPos() {
            return origin.offset(PILLAR_OFFSET);
        }

        public BlockPos rackPos() {
            return origin.offset(RACK_OFFSET.rotate(rotation()));
        }
    }

    public static final List<Site> SITES = List.of(
            new Site(new BlockPos(-364, 189, -507), Direction.WEST),
            new Site(new BlockPos(-494, 189, -502), Direction.EAST),
            new Site(new BlockPos(-425, 188, -575), Direction.SOUTH));

    private static final String DATA_NAME = "avalon_pillars";

    /** Penanda "pilar sudah ditempel di world ini" (checksum schematic + lokasi yang terakhir ditempel). */
    private static class Placed extends SavedData {
        private final long checksum;

        private Placed(long checksum) {
            this.checksum = checksum;
        }

        private static Placed load(CompoundTag tag) {
            return new Placed(tag.getLong("Checksum"));
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putLong("Checksum", checksum);
            return tag;
        }
    }

    /**
     * Tempel semua pilar kalau belum pernah ditempel di world ini,
     * atau kalau schematic / lokasinya sudah berubah sejak terakhir ditempel.
     */
    public static void ensurePlaced(MinecraftServer server) {
        ServerLevel level = server.getLevel(AvalonDimensions.AVALON);
        if (level == null) return;

        try (InputStream in = server.getResourceManager().open(SCHEMATIC)) {
            byte[] bytes = in.readAllBytes();
            CRC32 crc = new CRC32();
            crc.update(bytes);
            crc.update(SITES.toString().getBytes());
            long checksum = crc.getValue();

            Placed last = level.getDataStorage().get(Placed::load, DATA_NAME);
            if (last != null && last.checksum == checksum) return;

            SpongeSchematic schematic = SpongeSchematic.read(new ByteArrayInputStream(bytes));
            for (Site site : SITES) {
                int changed = schematic.paste(level, site.origin(), site.rotation(), AvalonPillars::unlit);
                AvalonLog.info("Pilar Avalon ditempel di " + site.origin().toShortString()
                        + " menghadap " + site.facing().getName() + " (" + changed + " blok).");
            }

            Placed placed = new Placed(checksum);
            placed.setDirty();
            level.getDataStorage().set(DATA_NAME, placed);
        } catch (Exception e) {
            AvalonLog.error("Gagal menempel pilar Avalon", e);
        }
    }

    /** Pilar di schematic tersimpan dalam keadaan menyala: tempel dalam keadaan mati. */
    private static BlockState unlit(BlockState state) {
        if (!(state.getBlock() instanceof PillarBlock)) return state;
        return state.setValue(PillarBlock.LIT, false).setValue(PillarBlock.POWERED, false);
    }
}

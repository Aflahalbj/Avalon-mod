package id.avalon.world;

import id.avalon.AvalonMod;
import id.avalon.core.AvalonDimensions;
import id.avalon.core.AvalonLog;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * Bangunan portal di dimensi Avalon (data/avalon/schematics/portal.schem) dan titik datangnya.
 * Portal ditempel otomatis sekali per world, dan ditempel ulang kalau file schematic-nya diganti.
 */
public final class AvalonPortal {

    private AvalonPortal() {}

    private static final ResourceLocation SCHEMATIC = new ResourceLocation(AvalonMod.MOD_ID, "schematics/portal.schem");

    /** Posisi "berdiri saat //paste" (sama seperti WorldEdit). */
    public static final BlockPos PASTE_ORIGIN = new BlockPos(-423, 189, -539);

    /** Titik datang di dimensi Avalon, menghadap portal (selatan, agak mendongak). */
    public static final BlockPos SPAWN = new BlockPos(-422, 191, -496);
    private static final float SPAWN_YAW = 0f;
    private static final float SPAWN_PITCH = -25f;

    /** Tengah cincin dalam portal raksasa (tempat bola kacanya), dipakai cutscene akhir game. */
    public static final Vec3 GATE_CENTER = new Vec3(-421.5, 211.0, -475.5);
    /** Titik di kaki cincin (puncak tangga) tempat player melangkah masuk portal. */
    public static final Vec3 GATE_ENTRY = new Vec3(-421.5, 199.0, -476.2);

    private static final String DATA_NAME = "avalon_portal";

    /** Penanda "schematic ini sudah ditempel di world ini" (checksum file yang terakhir ditempel). */
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
     * Tempel portal kalau belum pernah ditempel di world ini,
     * atau kalau file schematic-nya sudah berubah sejak terakhir ditempel.
     */
    public static void ensurePlaced(MinecraftServer server) {
        ServerLevel level = server.getLevel(AvalonDimensions.AVALON);
        if (level == null) return;

        try (InputStream in = server.getResourceManager().open(SCHEMATIC)) {
            byte[] bytes = in.readAllBytes();
            CRC32 crc = new CRC32();
            crc.update(bytes);
            long checksum = crc.getValue();

            Placed last = level.getDataStorage().get(Placed::load, DATA_NAME);
            if (last != null && last.checksum == checksum) return;

            int changed = SpongeSchematic.read(new ByteArrayInputStream(bytes)).paste(level, PASTE_ORIGIN);

            Placed placed = new Placed(checksum);
            placed.setDirty();
            level.getDataStorage().set(DATA_NAME, placed);
            AvalonLog.info("Portal Avalon ditempel di " + PASTE_ORIGIN.toShortString() + " (" + changed + " blok).");
        } catch (Exception e) {
            AvalonLog.error("Gagal menempel portal Avalon", e);
        }
    }

    /** Pindahkan player ke titik datang dimensi Avalon. */
    public static boolean teleportToSpawn(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        ServerLevel level = server == null ? null : server.getLevel(AvalonDimensions.AVALON);
        if (level == null) return false;

        ensurePlaced(server);

        // Naik sampai tidak di dalam blok (mis. dirt path di titik spawn)
        BlockPos.MutableBlockPos pos = SPAWN.mutable();
        for (int i = 0; i < 5 && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty(); i++) {
            pos.move(0, 1, 0);
        }

        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, SPAWN_YAW, SPAWN_PITCH);
        return true;
    }
}

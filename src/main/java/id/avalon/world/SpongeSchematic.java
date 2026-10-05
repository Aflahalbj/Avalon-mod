package id.avalon.world;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import id.avalon.core.AvalonLog;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Pembaca file .schem (Sponge Schematic v2/v3, format WorldEdit) + paste ke dunia.
 * Hanya blok; block entity dan entity diabaikan.
 */
public final class SpongeSchematic {

    /** Blok dari versi Minecraft lebih baru yang tidak ada di 1.20.1 → padanan terdekat. */
    private static final Map<String, String> REPLACEMENTS = Map.of(
        "minecraft:short_grass", "minecraft:grass",
        "minecraft:chiseled_tuff", "minecraft:tuff",
        "minecraft:polished_tuff_slab", "minecraft:polished_andesite_slab",
        "minecraft:waxed_chiseled_copper", "minecraft:waxed_cut_copper"
    );

    private final int width, height, length;
    private final int offsetX, offsetY, offsetZ;
    private final CompoundTag palette;
    private final byte[] data;

    private SpongeSchematic(int width, int height, int length, int[] offset, CompoundTag palette, byte[] data) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.offsetX = offset.length == 3 ? offset[0] : 0;
        this.offsetY = offset.length == 3 ? offset[1] : 0;
        this.offsetZ = offset.length == 3 ? offset[2] : 0;
        this.palette = palette;
        this.data = data;
    }

    public static SpongeSchematic read(InputStream in) throws IOException {
        CompoundTag root = NbtIo.readCompressed(in);
        // v3 membungkus semuanya di dalam "Schematic"; v2 langsung di root
        CompoundTag sc = root.contains("Schematic") ? root.getCompound("Schematic") : root;

        CompoundTag palette;
        byte[] data;
        if (sc.contains("Blocks")) {
            CompoundTag blocks = sc.getCompound("Blocks");
            palette = blocks.getCompound("Palette");
            data = blocks.getByteArray("Data");
        } else {
            palette = sc.getCompound("Palette");
            data = sc.getByteArray("BlockData");
        }

        // Jarak pojok minimum dari posisi //paste. Di v2 "Offset" adalah posisi asli di dunia
        // (bukan jarak relatif); jarak relatifnya disimpan WorldEdit di Metadata.WEOffset*.
        int[] offset;
        CompoundTag meta = sc.getCompound("Metadata");
        if (meta.contains("WEOffsetX")) {
            offset = new int[]{meta.getInt("WEOffsetX"), meta.getInt("WEOffsetY"), meta.getInt("WEOffsetZ")};
        } else if (sc.getInt("Version") >= 3) {
            offset = sc.getIntArray("Offset");
        } else {
            offset = new int[0];
        }

        return new SpongeSchematic(
            sc.getInt("Width"), sc.getInt("Height"), sc.getInt("Length"),
            offset, palette, data
        );
    }

    /**
     * Tempel seperti //paste WorldEdit dengan player berdiri di {@code origin}
     * (udara ikut ditempel). Mengembalikan jumlah blok yang berubah.
     */
    public int paste(ServerLevel level, BlockPos origin) {
        return paste(level, origin, Rotation.NONE, UnaryOperator.identity());
    }

    /**
     * Seperti {@link #paste(ServerLevel, BlockPos)}, tapi bangunannya diputar dulu mengelilingi
     * {@code origin} (setara //rotate lalu //paste), dan tiap blok dilewatkan ke {@code adjust}
     * sebelum ditempel.
     */
    public int paste(ServerLevel level, BlockPos origin, Rotation rotation, UnaryOperator<BlockState> adjust) {
        BlockState[] states = resolvePalette(level);
        for (int s = 0; s < states.length; s++) {
            if (states[s] != null) states[s] = adjust.apply(states[s].rotate(rotation));
        }

        // Tanpa update tetangga/bentuk: tanaman & dirt path tidak rontok saat ditempel
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int changed = 0;
        int index = 0;   // urutan sel: x, lalu z, lalu y
        int i = 0;
        int cells = width * height * length;

        while (i < data.length && index < cells) {
            // Indeks palette disimpan sebagai varint
            int id = 0, shift = 0, b;
            do {
                b = data[i++];
                id |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && i < data.length);

            BlockState state = id < states.length ? states[id] : null;
            if (state != null) {
                int x = index % width;
                int z = (index / width) % length;
                int y = index / (width * length);
                // Posisi relatif terhadap origin, diputar, baru digeser ke dunia
                BlockPos relative = new BlockPos(offsetX + x, offsetY + y, offsetZ + z).rotate(rotation);
                pos.setWithOffset(origin, relative);
                if (level.getBlockState(pos) != state && level.setBlock(pos, state, flags)) {
                    changed++;
                }
            }
            index++;
        }
        return changed;
    }

    private BlockState[] resolvePalette(ServerLevel level) {
        HolderLookup<Block> blocks = level.holderLookup(Registries.BLOCK);

        int max = -1;
        for (String key : palette.getAllKeys()) max = Math.max(max, palette.getInt(key));
        BlockState[] states = new BlockState[max + 1];

        for (String key : palette.getAllKeys()) {
            String spec = key;
            int bracket = key.indexOf('[');
            String name = bracket < 0 ? key : key.substring(0, bracket);
            String replacement = REPLACEMENTS.get(name);
            if (replacement != null) {
                spec = replacement + (bracket < 0 ? "" : key.substring(bracket));
            }
            BlockState state = parse(blocks, spec);
            if (state == null && spec.indexOf('[') >= 0) {
                // Properti dari versi lebih baru (mis. barrier[waterlogged]) → pakai state default bloknya
                state = parse(blocks, spec.substring(0, spec.indexOf('[')));
            }
            if (state == null) {
                // Blok tidak dikenal: dilewati (sel dibiarkan apa adanya)
                AvalonLog.warn("Schematic: blok tidak dikenal, dilewati: " + key);
            }
            states[palette.getInt(key)] = state;
        }
        return states;
    }

    private static BlockState parse(HolderLookup<Block> blocks, String spec) {
        try {
            return BlockStateParser.parseForBlock(blocks, spec, false).blockState();
        } catch (CommandSyntaxException e) {
            return null;
        }
    }
}

package id.avalon.world;

import id.avalon.block.BatteryRackBlock;
import id.avalon.block.BatteryRackBlockEntity;
import id.avalon.block.GateBlock;
import id.avalon.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Isi cincin portal raksasa untuk cutscene akhir game: bola kaca di tengah cincin dipecahkan,
 * lalu lubang cincin (bagian dalamnya saja) diisi blok portal yang menyebar dari tengah; rak baterai
 * di kaki cincin ikut menjadi portal. Semua perubahan dikembalikan oleh {@link #restore}.
 */
public final class AvalonGate {

    private AvalonGate() {}

    /** Bidang tempat cincin dalam berdiri (dan tempat blok portal dipasang). */
    public static final int PLANE_Z = -476;
    /** Sel tengah lubang cincin: titik awal mengisi. */
    public static final int CENTER_X = -422;
    public static final int CENTER_Y = 211;
    /**
     * Batas jarak dari tengah. Cincinnya punya celah kecil di kiri atas; tanpa batas ini
     * isinya merembes keluar lewat celah itu.
     */
    private static final double MAX_RADIUS = 13.4;

    /** Kotak yang memuat bola kaca di tengah cincin. */
    private static final BlockPos GLASS_MIN = new BlockPos(-428, 204, -482);
    private static final BlockPos GLASS_MAX = new BlockPos(-415, 219, -472);

    private static final int NO_UPDATE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** Kaca yang dipecahkan (posisi → blok aslinya), berurut dari tengah bola ke luar. */
    private static final Map<BlockPos, BlockState> shattered = new LinkedHashMap<>();
    /**
     * Rak baterai (gudang misi) yang tertimpa blok portal: raknya berdiri di kaki cincin, tepat di
     * bidang portal, jadi ikut menjadi portal dan baru dipasang lagi saat cincin dikembalikan.
     */
    private static final Map<BlockPos, BlockState> coveredRacks = new LinkedHashMap<>();
    /** Sel lubang cincin, berurut dari tengah ke tepi. */
    private static List<BlockPos> cells = List.of();
    /** Berapa sel yang saat ini terisi blok portal. */
    private static int filled = 0;

    /** Catat bola kaca & hitung sel lubang cincin. Dipanggil sekali sebelum cutscene memakainya. */
    public static void prepare(ServerLevel level) {
        if (!shattered.isEmpty() || !coveredRacks.isEmpty() || filled > 0) restore(level);

        List<BlockPos> glass = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(GLASS_MIN, GLASS_MAX)) {
            if (level.getBlockState(pos).getBlock() instanceof StainedGlassBlock) glass.add(pos.immutable());
        }
        glass.sort(Comparator.comparingDouble(AvalonGate::distance));
        for (BlockPos pos : glass) shattered.put(pos, level.getBlockState(pos));

        cells = interior(level);
        filled = 0;
    }

    private static double distance(BlockPos pos) {
        double dx = pos.getX() - CENTER_X, dy = pos.getY() - CENTER_Y, dz = pos.getZ() - PLANE_Z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Sel di dalam cincin pada bidang {@link #PLANE_Z}: menyebar dari tengah sampai terhalang blok cincin. */
    private static List<BlockPos> interior(ServerLevel level) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(new BlockPos(CENTER_X, CENTER_Y, PLANE_Z));
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (seen.contains(pos) || distance(pos) > MAX_RADIUS) continue;
            BlockState state = level.getBlockState(pos);
            // Bola kaca, rak baterai di kaki cincin & blok portal lama bukan dinding;
            // blok padat lain (cincin, tanah) adalah dinding
            boolean open = state.isAir() || state.getBlock() instanceof StainedGlassBlock
                    || state.getBlock() instanceof BatteryRackBlock
                    || state.getBlock() instanceof GateBlock || state.getCollisionShape(level, pos).isEmpty();
            if (!open) continue;
            seen.add(pos);
            queue.add(pos.east());
            queue.add(pos.west());
            queue.add(pos.above());
            queue.add(pos.below());
        }
        List<BlockPos> sorted = new ArrayList<>(seen);
        sorted.sort(Comparator.comparingDouble(AvalonGate::distance));
        return sorted;
    }

    /**
     * Pecahkan bola kaca sampai {@code fraction} (0..1) bagiannya, dari tengah ke luar,
     * dengan serpihan & bunyi kaca pecah.
     */
    public static void shatter(ServerLevel level, float fraction) {
        int target = Math.round(Math.min(1f, fraction) * shattered.size());
        int index = 0;
        for (Map.Entry<BlockPos, BlockState> entry : shattered.entrySet()) {
            if (index++ >= target) break;
            BlockPos pos = entry.getKey();
            if (level.getBlockState(pos).isAir() || level.getBlockState(pos).getBlock() instanceof GateBlock) continue;
            // Serpihan untuk sebagian blok saja, supaya suaranya tidak menumpuk
            if (index % 3 == 0) level.levelEvent(2001, pos, Block.getId(entry.getValue()));
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), NO_UPDATE);
        }
    }

    /** Isi (atau kosongkan lagi) lubang cincin sampai {@code fraction} (0..1) bagiannya, dari tengah ke tepi. */
    public static void fill(ServerLevel level, float fraction) {
        int target = Math.round(Math.max(0f, Math.min(1f, fraction)) * cells.size());
        BlockState gate = ModBlocks.GATE.get().defaultBlockState();
        while (filled < target) {
            BlockPos pos = cells.get(filled++);
            BlockState old = level.getBlockState(pos);
            if (old.getBlock() instanceof BatteryRackBlock) {
                coveredRacks.put(pos, old);
                // Jangan sampai isi raknya berjatuhan saat bloknya diganti
                if (level.getBlockEntity(pos) instanceof BatteryRackBlockEntity rack) rack.items().clear();
            }
            level.setBlock(pos, gate, NO_UPDATE);
        }
        while (filled > target) {
            level.setBlock(cells.get(--filled), Blocks.AIR.defaultBlockState(), NO_UPDATE);
        }
    }

    /** Kembalikan cincin seperti semula: blok portal dicabut, bola kaca & rak baterai dipasang lagi. */
    public static void restore(ServerLevel level) {
        for (BlockPos pos : cells) {
            if (level.getBlockState(pos).getBlock() instanceof GateBlock) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), NO_UPDATE);
            }
        }
        for (Map.Entry<BlockPos, BlockState> entry : shattered.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), NO_UPDATE);
        }
        for (Map.Entry<BlockPos, BlockState> entry : coveredRacks.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_CLIENTS);
        }
        coveredRacks.clear();
        shattered.clear();
        cells = List.of();
        filled = 0;
    }
}

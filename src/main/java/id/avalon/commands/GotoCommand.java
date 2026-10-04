package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import id.avalon.core.AvalonDimensions;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import id.avalon.world.AvalonPortal;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Collection;
import java.util.List;

/**
 * /avalon gotoavalon [player] — pindah ke titik datang dimensi Avalon (di depan portal).
 * /avalon gotoworld [player]  — pindah ke overworld (X/Z dipertahankan).
 * Tanpa argumen: pengirimnya sendiri. Argumen menerima nama player atau selector (@a, @s, @p, ...).
 */
public class GotoCommand {

    /** Radius pencarian daratan (blok) kalau kolom tujuan kosong. */
    private static final int SEARCH_RADIUS = 96;
    private static final int SEARCH_STEP = 8;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("gotoavalon")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> gotoAvalon(ctx.getSource(), self(ctx.getSource())))
            .then(Commands.argument("targets", EntityArgument.players())
                .executes(ctx -> gotoAvalon(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))));

        dispatcher.register(Commands.literal("gotoworld")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> gotoWorld(ctx.getSource(), self(ctx.getSource())))
            .then(Commands.argument("targets", EntityArgument.players())
                .executes(ctx -> gotoWorld(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))));
    }

    /** Target kalau tidak ada argumen: pengirimnya sendiri (kosong kalau pengirim bukan player). */
    private static Collection<ServerPlayer> self(CommandSourceStack sender) {
        if (sender.getEntity() instanceof ServerPlayer player) return List.of(player);
        sender.sendSystemMessage(Txt.legacy("Player only."));
        return List.of();
    }

    private static int gotoAvalon(CommandSourceStack sender, Collection<ServerPlayer> targets) {

        int moved = 0;
        for (ServerPlayer player : targets) {
            if (!AvalonPortal.teleportToSpawn(player)) {
                sender.sendSystemMessage(Txt.legacy("§cDimensi " + AvalonDimensions.AVALON_ID + " tidak ditemukan."));
                return 1;
            }
            player.sendSystemMessage(Txt.legacy("§5Masuk ke dunia Avalon."));
            moved++;
        }

        report(sender, targets, moved, "§5" + moved + " player dipindahkan ke dunia Avalon.");
        return 1;
    }

    private static int gotoWorld(CommandSourceStack sender, Collection<ServerPlayer> targets) {

        ServerLevel target = sender.getServer().overworld();

        int moved = 0;
        for (ServerPlayer player : targets) {
            if (player.level() == target) {
                player.sendSystemMessage(Txt.legacy("§cKamu sudah berada di overworld."));
                continue;
            }

            BlockPos pos = findGround(target, player.getBlockX(), player.getBlockZ());
            player.teleportTo(target, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), player.getXRot());

            player.sendSystemMessage(Txt.legacy("§aKembali ke overworld."));
            moved++;
        }

        report(sender, targets, moved, "§a" + moved + " player dikembalikan ke overworld.");
        return 1;
    }

    /** Ringkasan untuk pengirim, kecuali kalau satu-satunya target adalah dirinya (sudah dapat pesan sendiri). */
    private static void report(CommandSourceStack sender, Collection<ServerPlayer> targets, int moved, String message) {
        if (targets.isEmpty()) return;
        if (targets.size() == 1 && targets.iterator().next() == sender.getEntity()) return;
        sender.sendSystemMessage(Txt.legacy(message));
    }

    /**
     * Cari posisi berdiri di atas daratan terdekat dari (x, z).
     * Kalau tidak ada daratan dalam radius, taruh satu blok pijakan di (x, 100, z).
     */
    private static BlockPos findGround(ServerLevel level, int x, int z) {
        for (int r = 0; r <= SEARCH_RADIUS; r += SEARCH_STEP) {
            for (int dx = -r; dx <= r; dx += SEARCH_STEP) {
                for (int dz = -r; dz <= r; dz += SEARCH_STEP) {
                    // Hanya sisi terluar dari kotak radius r
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;

                    int px = x + dx, pz = z + dz;
                    // getChunk memaksa chunk di-generate dulu supaya heightmap-nya valid
                    level.getChunk(px >> 4, pz >> 4);
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz);
                    if (y > level.getMinBuildHeight()) {
                        return new BlockPos(px, y, pz);
                    }
                }
            }
        }

        level.setBlock(new BlockPos(x, 99, z), Blocks.OBSIDIAN.defaultBlockState(), 3);
        return new BlockPos(x, 100, z);
    }
}

package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import id.avalon.block.PillarBlock;
import id.avalon.core.AvalonDimensions;
import id.avalon.core.Scheduler;
import id.avalon.core.Txt;
import id.avalon.cutscene.EndingCutscene;
import id.avalon.cutscene.EndingTimeline;
import id.avalon.cutscene.PortalCutscene;
import id.avalon.cutscene.PortalTimeline;
import id.avalon.managers.GameManager;
import id.avalon.world.AvalonPillars;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CutsceneCommand {

    /** Player dalam radius ini (blok) dari pengirim ikut tersedot saat tes cutscene portal. */
    private static final double PORTAL_PLAYER_RADIUS = 32.0;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("cutscene")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> {
                ctx.getSource().sendSystemMessage(Txt.t("Usage: /avalon cutscene <on|off>", ChatFormatting.RED));
                return 1;
            })
            .then(Commands.literal("portal")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Txt.t("Usage: /avalon cutscene portal <play [animasi]|stop>", ChatFormatting.RED));
                    return 1;
                })
                .then(Commands.literal("play")
                    .executes(ctx -> playPortal(ctx.getSource(), gameManager, null))
                    .then(Commands.argument("animasi", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PortalTimeline.STYLE_NAMES, builder))
                        .executes(ctx -> playPortal(ctx.getSource(), gameManager,
                            StringArgumentType.getString(ctx, "animasi")))))
                .then(Commands.literal("stop").executes(ctx -> stopPortal(ctx.getSource(), gameManager))))
            .then(Commands.literal("ending")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Txt.t("Usage: /avalon cutscene ending <"
                        + String.join("|", EndingTimeline.NAMES) + "|stop>", ChatFormatting.RED));
                    return 1;
                })
                .then(Commands.literal("stop").executes(ctx -> stopEnding(ctx.getSource())))
                .then(Commands.argument("jenis", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(EndingTimeline.NAMES, builder))
                    .executes(ctx -> playEnding(ctx.getSource(), gameManager,
                        StringArgumentType.getString(ctx, "jenis"), -1))
                    // Untuk ending kalah: berapa pilar yang dinyalakan dulu (0-3)
                    .then(Commands.argument("pilar", IntegerArgumentType.integer(0, AvalonPillars.SITES.size()))
                        .executes(ctx -> playEnding(ctx.getSource(), gameManager,
                            StringArgumentType.getString(ctx, "jenis"),
                            IntegerArgumentType.getInteger(ctx, "pilar"))))))
            .then(Commands.argument("toggle", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("on", "off"), builder))
                .executes(ctx -> execute(ctx.getSource(), gameManager,
                    StringArgumentType.getString(ctx, "toggle")))));
    }

    private static int execute(CommandSourceStack sender, GameManager gameManager, String arg) {

        String toggle = arg.toLowerCase(Locale.ROOT);
        if (toggle.equals("on")) {
            gameManager.setCutsceneEnabled(true);
            sender.sendSystemMessage(Txt.t("✔ Cutscene diaktifkan.", ChatFormatting.GREEN));
        } else if (toggle.equals("off")) {
            gameManager.setCutsceneEnabled(false);
            sender.sendSystemMessage(Txt.t("✔ Cutscene dinonaktifkan.", ChatFormatting.YELLOW));
        } else {
            sender.sendSystemMessage(Txt.t("Usage: /avalon cutscene <on|off>", ChatFormatting.RED));
        }

        return 1;
    }

    /**
     * Tes cutscene portal: portal terbuka di arah pandang pengirim, lalu semua player di sekitarnya
     * tersedot. Karena ini tes, player tidak dipindahkan ke dimensi Avalon.
     * {@code animation} = nama gaya untuk semua player, atau null untuk gaya acak yang berbeda-beda.
     */
    private static int playPortal(CommandSourceStack sender, GameManager gameManager, String animation) {

        if (!(sender.getEntity() instanceof ServerPlayer player)) {
            sender.sendSystemMessage(Txt.legacy("Player only."));
            return 1;
        }

        int style = -1;
        if (animation != null) {
            style = PortalTimeline.STYLE_NAMES.indexOf(animation.toLowerCase(Locale.ROOT));
            if (style < 0) {
                sender.sendSystemMessage(Txt.t("Animasi tidak dikenal. Pilihan: "
                    + String.join(", ", PortalTimeline.STYLE_NAMES), ChatFormatting.RED));
                return 1;
            }
        }

        if (gameManager.isGameRunning()) {
            sender.sendSystemMessage(Txt.t("Tidak bisa tes cutscene portal saat game berjalan.", ChatFormatting.RED));
            return 1;
        }
        if (PortalCutscene.isRunning()) {
            sender.sendSystemMessage(Txt.t("Cutscene portal sedang berjalan.", ChatFormatting.RED));
            return 1;
        }

        ServerLevel level = player.serverLevel();
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (p.distanceToSqr(player) <= PORTAL_PLAYER_RADIUS * PORTAL_PLAYER_RADIUS) players.add(p);
        }
        if (players.isEmpty()) {
            sender.sendSystemMessage(Txt.t("Tidak ada player yang bisa ikut cutscene.", ChatFormatting.RED));
            return 1;
        }

        PortalCutscene.play(gameManager, level, players, player.getYRot(), style, null);
        sender.sendSystemMessage(Txt.t("✔ Cutscene portal dimulai (" + players.size() + " player).", ChatFormatting.GREEN));
        return 1;
    }

    /**
     * Tes cutscene akhir game tanpa harus bermain: semua player di dimensi Avalon ikut tampil.
     * Di luar game tidak ada peran, jadi kubunya dibagi bergantian (pengirim = kubu baik / Merlin;
     * sendirian di ending "kalah" = kubu jahat, supaya auranya terlihat).
     *
     * @param litPillars untuk ending kalah: jumlah pilar yang dinyalakan dulu (sisanya dimatikan);
     *                   -1 = pakai keadaan pilar apa adanya
     */
    private static int playEnding(CommandSourceStack sender, GameManager gameManager, String name, int litPillars) {

        if (!(sender.getEntity() instanceof ServerPlayer player)) {
            sender.sendSystemMessage(Txt.legacy("Player only."));
            return 1;
        }

        int type = EndingTimeline.NAMES.indexOf(name.toLowerCase(Locale.ROOT));
        if (type < 0) {
            sender.sendSystemMessage(Txt.t("Ending tidak dikenal. Pilihan: "
                + String.join(", ", EndingTimeline.NAMES), ChatFormatting.RED));
            return 1;
        }
        if (gameManager.isGameRunning()) {
            sender.sendSystemMessage(Txt.t("Tidak bisa tes cutscene akhir saat game berjalan.", ChatFormatting.RED));
            return 1;
        }
        if (EndingCutscene.isRunning()) {
            sender.sendSystemMessage(Txt.t("Cutscene akhir sedang berjalan.", ChatFormatting.RED));
            return 1;
        }
        ServerLevel level = player.serverLevel();
        if (level.dimension() != AvalonDimensions.AVALON) {
            sender.sendSystemMessage(Txt.t("Masuk dulu ke dimensi Avalon: /avalon gotoavalon", ChatFormatting.RED));
            return 1;
        }

        List<ServerPlayer> good = new ArrayList<>();
        List<ServerPlayer> evil = new ArrayList<>();
        List<ServerPlayer> others = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p != player && !p.isSpectator()) others.add(p);
        }
        if (others.isEmpty() && type == EndingTimeline.EVIL) {
            evil.add(player);
        } else {
            good.add(player);
            for (int i = 0; i < others.size(); i++) (i % 2 == 0 ? evil : good).add(others.get(i));
        }
        ServerPlayer merlin = good.contains(player) ? player : null;

        // Ending menang & Merlin butuh ketiga bola pilar sudah menyala; ending kalah memakai pilar
        // apa adanya, kecuali jumlahnya diminta lewat argumen
        boolean lighting = false;
        int wanted = type != EndingTimeline.EVIL ? AvalonPillars.SITES.size() : litPillars;
        for (int i = 0; wanted >= 0 && i < AvalonPillars.SITES.size(); i++) {
            BlockPos pillar = AvalonPillars.SITES.get(i).pillarPos();
            boolean lit = i < wanted;
            if (PillarBlock.isLit(level, pillar) == lit) continue;
            PillarBlock.setLit(level, pillar, lit);
            if (lit) lighting = true;
        }

        Runnable play = () -> {
            if (gameManager.isGameRunning() || EndingCutscene.isRunning()) return;
            good.removeIf(p -> !gameManager.isOnline(p));
            evil.removeIf(p -> !gameManager.isOnline(p));
            if (good.isEmpty() && evil.isEmpty()) return;
            EndingCutscene.play(gameManager, level, type, good, evil,
                merlin != null && gameManager.isOnline(merlin) ? merlin : null,
                evil.isEmpty() ? null : evil.get(0), true, null);
        };
        if (lighting) {
            sender.sendSystemMessage(Txt.t("Menyalakan pilar dulu, cutscene mulai sebentar lagi...", ChatFormatting.YELLOW));
            Scheduler.later(PillarBlock.RISE_TICKS + PillarBlock.IGNITE_TICKS + 10L, play);
        } else {
            play.run();
        }
        sender.sendSystemMessage(Txt.t("✔ Cutscene akhir \"" + EndingTimeline.NAMES.get(type) + "\": "
            + good.size() + " kubu baik, " + evil.size() + " kubu jahat.", ChatFormatting.GREEN));
        return 1;
    }

    private static int stopEnding(CommandSourceStack sender) {
        if (EndingCutscene.stop()) {
            sender.sendSystemMessage(Txt.t("✔ Cutscene akhir dihentikan.", ChatFormatting.YELLOW));
        } else {
            sender.sendSystemMessage(Txt.t("Tidak ada cutscene akhir yang berjalan.", ChatFormatting.RED));
        }
        return 1;
    }

    private static int stopPortal(CommandSourceStack sender, GameManager gameManager) {
        // Cutscene pembuka game: menghentikannya meninggalkan player di luar Avalon sementara game lanjut
        if (gameManager.isGameRunning()) {
            sender.sendSystemMessage(Txt.t("Tidak bisa menghentikan cutscene portal saat game berjalan. Pakai /avalon stopgame.", ChatFormatting.RED));
            return 1;
        }
        if (PortalCutscene.stop(gameManager)) {
            sender.sendSystemMessage(Txt.t("✔ Cutscene portal dihentikan.", ChatFormatting.YELLOW));
        } else {
            sender.sendSystemMessage(Txt.t("Tidak ada cutscene portal yang berjalan.", ChatFormatting.RED));
        }
        return 1;
    }
}

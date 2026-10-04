package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import id.avalon.core.Txt;
import id.avalon.cutscene.PortalCutscene;
import id.avalon.cutscene.PortalTimeline;
import id.avalon.managers.GameManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
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

    private static int stopPortal(CommandSourceStack sender, GameManager gameManager) {
        if (PortalCutscene.stop(gameManager)) {
            sender.sendSystemMessage(Txt.t("✔ Cutscene portal dihentikan.", ChatFormatting.YELLOW));
        } else {
            sender.sendSystemMessage(Txt.t("Tidak ada cutscene portal yang berjalan.", ChatFormatting.RED));
        }
        return 1;
    }
}

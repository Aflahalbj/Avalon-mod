package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import id.avalon.managers.GameManager.LadyMode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.List;
import java.util.Locale;

/**
 * /avalon lady <auto|on|off> — Lady of the Lake.
 * auto = hanya untuk game berisi 7 player atau lebih (default), on = selalu, off = tidak pernah.
 */
public class LadyCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("lady")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> status(ctx.getSource(), gameManager))
            .then(Commands.argument("mode", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("auto", "on", "off"), builder))
                .executes(ctx -> execute(ctx.getSource(), gameManager,
                    StringArgumentType.getString(ctx, "mode")))));
    }

    private static int status(CommandSourceStack sender, GameManager gameManager) {
        sender.sendSystemMessage(Txt.t("Usage: /avalon lady <auto|on|off>", ChatFormatting.RED));
        sender.sendSystemMessage(Txt.t("Sekarang: " + describe(gameManager.getLadyMode()), ChatFormatting.GRAY));
        return 1;
    }

    private static int execute(CommandSourceStack sender, GameManager gameManager, String arg) {
        LadyMode mode;
        switch (arg.toLowerCase(Locale.ROOT)) {
            case "auto" -> mode = LadyMode.AUTO;
            case "on" -> mode = LadyMode.ON;
            case "off" -> mode = LadyMode.OFF;
            default -> {
                return status(sender, gameManager);
            }
        }

        gameManager.setLadyMode(mode);
        // Game yang raja pertamanya belum dipilih masih memakai mode yang baru
        String scope = !gameManager.isGameRunning() ? ""
            : (gameManager.isLadyDecided() ? " Berlaku mulai game berikutnya." : " Berlaku untuk game ini.");
        sender.sendSystemMessage(Txt.t("✔ Lady of the Lake: " + describe(mode) + scope, ChatFormatting.GREEN));
        return 1;
    }

    private static String describe(LadyMode mode) {
        return switch (mode) {
            case AUTO -> "auto (hanya kalau 7 player atau lebih).";
            case ON -> "on (selalu dipakai).";
            case OFF -> "off (tidak dipakai).";
        };
    }
}

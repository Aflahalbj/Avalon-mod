package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Alat bantu tes.
 * /avalon debug alwaysking <playername|off> — player itu selalu jadi raja (raja tidak bergilir).
 */
public class DebugCommand {

    private static final String OFF = "off";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("debug")
            .requires(AvalonCommands::isAdmin)
            .then(Commands.literal("alwaysking")
                .executes(ctx -> status(ctx.getSource(), gameManager))
                .then(Commands.argument("playername", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        List<String> names = new ArrayList<>(gameManager.getRegisteredPlayers());
                        names.add(OFF);
                        return SharedSuggestionProvider.suggest(names, builder);
                    })
                    .executes(ctx -> alwaysKing(ctx.getSource(), gameManager,
                        StringArgumentType.getString(ctx, "playername"))))));
    }

    private static int status(CommandSourceStack sender, GameManager gameManager) {
        String name = gameManager.getAlwaysKing();
        sender.sendSystemMessage(Txt.t("Usage: /avalon debug alwaysking <playername|" + OFF + ">", ChatFormatting.RED));
        sender.sendSystemMessage(name == null
            ? Txt.t("Sekarang: mati (raja bergilir seperti biasa).", ChatFormatting.GRAY)
            : Txt.t("Sekarang: " + name + " selalu jadi raja.", ChatFormatting.YELLOW));
        return 1;
    }

    private static int alwaysKing(CommandSourceStack sender, GameManager gameManager, String playerName) {

        if (playerName.equalsIgnoreCase(OFF)) {
            gameManager.setAlwaysKing(null);
            sender.sendSystemMessage(Txt.t("✔ Raja bergilir seperti biasa lagi.", ChatFormatting.YELLOW));
            return 1;
        }

        if (!gameManager.getRegisteredPlayers().contains(playerName)) {
            sender.sendSystemMessage(Txt.t(playerName + " belum terdaftar. Daftarkan dulu: /avalon regis "
                + playerName, ChatFormatting.RED));
            return 1;
        }

        gameManager.setAlwaysKing(playerName);
        sender.sendSystemMessage(Txt.t("✔ " + playerName + " akan selalu jadi raja"
            + (gameManager.isGameRunning() ? " mulai pergantian raja berikutnya." : "."), ChatFormatting.GREEN));
        return 1;
    }
}

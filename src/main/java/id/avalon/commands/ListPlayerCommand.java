package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.List;

public class ListPlayerCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("listplayer")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> execute(ctx.getSource(), gameManager)));
    }

    private static int execute(CommandSourceStack sender, GameManager gameManager) {

        List<String> players = gameManager.getRegisteredPlayers();

        if (players.isEmpty()) {
            sender.sendSystemMessage(Txt.t("Belum ada player terdaftar.", ChatFormatting.RED));
            return 1;
        }

        sender.sendSystemMessage(Txt.t("=== List Players ===", ChatFormatting.GOLD));

        for (int i = 0; i < players.size(); i++) {
            sender.sendSystemMessage(
                Txt.t((i + 1) + ". ", ChatFormatting.YELLOW)
                    .append(Txt.t(players.get(i), ChatFormatting.WHITE))
            );
        }

        sender.sendSystemMessage(
            Txt.t("Total: ", ChatFormatting.GRAY)
                .append(Txt.t(String.valueOf(players.size()), ChatFormatting.WHITE))
        );

        return 1;
    }
}

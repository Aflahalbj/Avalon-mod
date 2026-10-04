package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

public class QueenCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("queen")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> execute(ctx.getSource(), gameManager, null))
            .then(Commands.argument("action", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("spawn", "delete"), builder))
                .executes(ctx -> execute(ctx.getSource(), gameManager,
                    StringArgumentType.getString(ctx, "action")))));
    }

    private static int execute(CommandSourceStack sender, GameManager gameManager, String arg) {

        if (!(sender.getEntity() instanceof ServerPlayer player)) {
            sender.sendSystemMessage(Txt.legacy("Player only."));
            return 1;
        }

        if (arg == null) {
            player.sendSystemMessage(Txt.legacy("§e/avalon queen spawn"));
            player.sendSystemMessage(Txt.legacy("§e/avalon queen delete"));
            return 1;
        }

        switch (arg.toLowerCase(Locale.ROOT)) {

            case "spawn" -> {

                if (gameManager.hasQueen(player.serverLevel())) {
                    player.sendSystemMessage(Txt.legacy("§cQueen sudah ada di server."));
                    return 1;
                }
                gameManager.spawnMannequin(player.serverLevel());

                player.sendSystemMessage(Txt.legacy("§aQueen berhasil di-spawn."));
            }

            case "delete" -> {

                gameManager.removeQueen(player.serverLevel());

                player.sendSystemMessage(Txt.legacy("§cQueen berhasil dihapus."));
            }

            default -> {

                player.sendSystemMessage(Txt.legacy("§cGunakan:"));
                player.sendSystemMessage(Txt.legacy("§e/avalon queen spawn"));
                player.sendSystemMessage(Txt.legacy("§e/avalon queen delete"));

            }

        }

        return 1;
    }
}

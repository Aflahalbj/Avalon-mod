package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public class StartGameCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("startgame")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> {
                CommandSourceStack sender = ctx.getSource();
                if (!(sender.getEntity() instanceof ServerPlayer player)) {
                    sender.sendSystemMessage(Txt.t("Harus dijalankan oleh player!", ChatFormatting.RED));
                    return 1;
                }

                gameManager.startGame(player);
                return 1;
            }));
    }
}

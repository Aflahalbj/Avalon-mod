package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public class StopGameCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("stopgame")
            .requires(AvalonCommands::isAdmin)
            // Boleh dari console: game yang macet tetap bisa dihentikan walau tidak ada OP yang online
            .executes(ctx -> {
                CommandSourceStack sender = ctx.getSource();
                if (gameManager.stopGame()) {
                    sender.sendSystemMessage(Txt.t("Game berhasil dihentikan. Player masih terdaftar.", ChatFormatting.GREEN));
                } else {
                    sender.sendSystemMessage(Txt.t("Tidak ada game yang berjalan!", ChatFormatting.RED));
                }
                return 1;
            }));
    }
}

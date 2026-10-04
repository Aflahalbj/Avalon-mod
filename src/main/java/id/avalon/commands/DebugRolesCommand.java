package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import id.avalon.models.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public class DebugRolesCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("debugroles")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> execute(ctx.getSource(), gameManager)));
    }

    private static int execute(CommandSourceStack sender, GameManager gameManager) {

        sender.sendSystemMessage(Txt.t("=== ROLE DEBUG ===", ChatFormatting.GOLD));

        for (ServerPlayer player : sender.getServer().getPlayerList().getPlayers()) {

            Role role = gameManager.getRole(player);

            if (role == null)
                continue;

            sender.sendSystemMessage(
                Txt.t(player.getGameProfile().getName(), ChatFormatting.YELLOW)
                    .append(Txt.t(" -> ", ChatFormatting.GRAY))
                    .append(Txt.t(role.name(), ChatFormatting.WHITE))
            );
        }

        return 1;
    }
}

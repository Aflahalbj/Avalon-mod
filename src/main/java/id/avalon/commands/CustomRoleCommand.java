package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import id.avalon.AvalonMod;
import id.avalon.core.Txt;
import id.avalon.gui.RoleEditorSession;
import id.avalon.managers.GameManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;

public class CustomRoleCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("customrole")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> execute(ctx.getSource(), gameManager)));
    }

    private static int execute(CommandSourceStack sender, GameManager gameManager) {

        if (!(sender.getEntity() instanceof ServerPlayer player))
            return 1;

        int playerCount = gameManager.getRegisteredPlayers().size();

        if (playerCount < 5 || playerCount > 10) {
            player.sendSystemMessage(Txt.t("Jumlah player harus 5-10.", ChatFormatting.RED));
            return 1;
        }

        RoleEditorSession session =
                new RoleEditorSession(
                        playerCount,
                        new ArrayList<>(
                                gameManager.getCustomRoles(
                                        playerCount
                                )
                        )
                );

        AvalonMod.getInstance().getCustomRoleListener().open(player, session);

        return 1;
    }
}

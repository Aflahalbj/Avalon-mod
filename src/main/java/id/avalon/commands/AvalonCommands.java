package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import id.avalon.managers.GameManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Registrasi semua command Avalon (pengganti plugin.yml + getCommand().setExecutor()).
 * Permission "avalon.admin" (default op) → permission level 2.
 */
public final class AvalonCommands {

    /** Setara permission avalon.admin (default: op). */
    public static final int ADMIN_LEVEL = 2;

    /** Semua command ada di bawah /avalon <command>. */
    public static final String ROOT = "avalon";

    private AvalonCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        // Tiap command didaftarkan ke dispatcher sementara, lalu semuanya digantung di bawah /avalon.
        CommandDispatcher<CommandSourceStack> sub = new CommandDispatcher<>();

        RegisCommand.register(sub, gameManager);
        UnregisCommand.register(sub, gameManager);
        ListPlayerCommand.register(sub, gameManager);
        CustomRoleCommand.register(sub, gameManager);
        CutsceneCommand.register(sub, gameManager);
        RoleInfoCommand.register(sub, gameManager);
        QueenCommand.register(sub, gameManager);
        GotoCommand.register(sub, gameManager);
        StartGameCommand.register(sub, gameManager);
        DebugRolesCommand.register(sub, gameManager);
        SetRoleCommand.register(sub, gameManager);
        DebugCommand.register(sub, gameManager);
        StopGameCommand.register(sub, gameManager);
        SetTimerCommand.register(sub, gameManager);

        // Root tanpa requires: /avalon roleinfo boleh untuk semua player, sisanya dicek per subcommand.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT);
        sub.getRoot().getChildren().forEach(root::then);
        dispatcher.register(root);
    }

    static boolean isAdmin(CommandSourceStack source) {
        return source.hasPermission(ADMIN_LEVEL);
    }
}

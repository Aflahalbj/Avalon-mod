package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import id.avalon.world.AvalonSeats;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;

public class RegisCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("regis")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> {
                ctx.getSource().sendSystemMessage(Txt.t("Usage: /avalon regis <player|@a|...>", ChatFormatting.RED));
                return 1;
            })
            // Nama player online atau selector (@a, @p, @s, ...)
            .then(Commands.argument("targets", EntityArgument.players())
                .executes(ctx -> execute(ctx.getSource(), gameManager,
                    EntityArgument.getPlayers(ctx, "targets")))));
    }

    private static int execute(CommandSourceStack sender, GameManager gameManager, Collection<ServerPlayer> targets) {

        if (gameManager.isGameRunning()) {
            sender.sendSystemMessage(Txt.t("Tidak bisa register saat game sedang berjalan!", ChatFormatting.RED));
            return 1;
        }

        for (ServerPlayer target : targets) {

            if (gameManager.getRegisteredPlayers().size() >= gameManager.getMaxPlayers()) {
                sender.sendSystemMessage(Txt.t(
                        "Arena Avalon sudah penuh! (maksimal "
                        + gameManager.getMaxPlayers()
                        + " player)", ChatFormatting.RED
                ));
                break;
            }

            // Pakai nama asli player (case sesuai akun)
            String name = target.getGameProfile().getName();

            if (gameManager.registerPlayer(name)) {
                sender.sendSystemMessage(Txt.t("✔ " + name + " berhasil didaftarkan ke Avalon!", ChatFormatting.GREEN));
            } else {
                sender.sendSystemMessage(Txt.t(name + " sudah terdaftar!", ChatFormatting.YELLOW));
            }
        }

        AvalonSeats.sync(sender.getServer(), gameManager.getRegisteredPlayers().size());
        sender.sendSystemMessage(
            Txt.t("Total player: ", ChatFormatting.GRAY)
                .append(Txt.t(String.valueOf(gameManager.getRegisteredPlayers().size()), ChatFormatting.WHITE))
        );

        return 1;
    }
}

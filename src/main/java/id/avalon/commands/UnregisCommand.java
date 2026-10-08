package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import id.avalon.world.AvalonSeats;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public class UnregisCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("unregis")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> {
                ctx.getSource().sendSystemMessage(Txt.t("Usage: /avalon unregis <playername|@a|...>", ChatFormatting.RED));
                return 1;
            })
            // Teks bebas (bukan EntityArgument) supaya nama player yang sedang offline tetap bisa di-unregister
            .then(Commands.argument("playername", StringArgumentType.greedyString())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                    gameManager.getRegisteredPlayers(), builder))
                .executes(ctx -> execute(ctx.getSource(), gameManager,
                    StringArgumentType.getString(ctx, "playername")))));
    }

    private static int execute(CommandSourceStack sender, GameManager gameManager, String target)
            throws CommandSyntaxException {

        // Nomor kursi mengikuti urutan daftar: mengubahnya di tengah game menggeser kursi semua orang
        if (gameManager.isGameRunning()) {
            sender.sendSystemMessage(Txt.t("Tidak bisa unregister saat game sedang berjalan!", ChatFormatting.RED));
            return 1;
        }

        List<String> names = new ArrayList<>();
        if (target.equals("@a")) {
            // Semua yang terdaftar, termasuk yang sedang offline
            names.addAll(gameManager.getRegisteredPlayers());
        } else if (target.startsWith("@")) {
            for (ServerPlayer p : new EntitySelectorParser(new StringReader(target)).parse().findPlayers(sender)) {
                names.add(p.getGameProfile().getName());
            }
        } else {
            names.add(target);
        }

        if (names.isEmpty()) {
            sender.sendSystemMessage(Txt.t("Tidak ada player yang cocok.", ChatFormatting.YELLOW));
            return 1;
        }

        for (String playerName : names) {
            if (gameManager.unregisterPlayer(playerName)) {
                sender.sendSystemMessage(Txt.t("✔ " + playerName + " berhasil di-unregister dari Avalon.", ChatFormatting.GREEN));
            } else {
                sender.sendSystemMessage(Txt.t(playerName + " tidak ditemukan di daftar.", ChatFormatting.YELLOW));
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

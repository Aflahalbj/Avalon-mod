package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.Arrays;
import java.util.Locale;

public class SetTimerCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("settimer")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> usage(ctx.getSource()))
            .then(Commands.argument("type", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                    Arrays.asList("reveal", "voting", "discuss", "evildiscuss"), builder))
                .executes(ctx -> usage(ctx.getSource()))
                .then(Commands.argument("seconds", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                        Arrays.asList("10", "20", "30", "60", "120", "300", "600"), builder))
                    .executes(ctx -> execute(ctx.getSource(), gameManager,
                        StringArgumentType.getString(ctx, "type"),
                        StringArgumentType.getString(ctx, "seconds"))))));
    }

    private static int usage(CommandSourceStack sender) {
        sender.sendSystemMessage(Txt.legacy("§cPenggunaan:"));
        sender.sendSystemMessage(Txt.legacy("§7/avalon settimer reveal <detik>"));
        sender.sendSystemMessage(Txt.legacy("§7/avalon settimer voting <detik>"));
        sender.sendSystemMessage(Txt.legacy("§7/avalon settimer discuss <detik>"));
        sender.sendSystemMessage(Txt.legacy("§7/avalon settimer evildiscuss <detik>"));
        return 1;
    }

    private static int execute(CommandSourceStack sender, GameManager gameManager, String type, String secondsArg) {

        int seconds;

        try {
            seconds = Integer.parseInt(secondsArg);
        } catch (NumberFormatException e) {
            sender.sendSystemMessage(Txt.legacy("§cDetik harus berupa angka."));
            return 1;
        }

        if (seconds <= 0) {
            sender.sendSystemMessage(Txt.legacy("§cDetik harus lebih dari 0."));
            return 1;
        }

        switch (type.toLowerCase(Locale.ROOT)) {

            case "reveal" -> {
                gameManager.setRevealSeconds(seconds);
                sender.sendSystemMessage(Txt.legacy("§aReveal timer diubah menjadi §e" + seconds + "§a detik."));
            }

            case "voting" -> {
                gameManager.setVotingSeconds(seconds);
                sender.sendSystemMessage(Txt.legacy("§aVoting timer diubah menjadi §e" + seconds + "§a detik."));
            }

            case "discuss" -> {
                gameManager.setDiscussionSeconds(seconds);
                sender.sendSystemMessage(Txt.legacy("§aDiscussion timer diubah menjadi §e" + seconds + "§a detik."));
            }

            case "evildiscuss" -> {
                gameManager.setEvilDiscussionSeconds(seconds);
                sender.sendSystemMessage(Txt.legacy("§aEvil discussion timer diubah menjadi §e" + seconds + "§a detik."));
            }

            default -> {
                sender.sendSystemMessage(Txt.legacy("§cTimer tidak dikenal."));
                return 1;
            }
        }

        return 1;
    }
}

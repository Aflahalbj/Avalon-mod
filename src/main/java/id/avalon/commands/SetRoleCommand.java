package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import id.avalon.models.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * /avalon setrole <player> <role|acak> — pastikan player mendapat role tertentu di game berikutnya.
 * Hanya role yang aktif untuk jumlah player terdaftar (default atau hasil /avalon customrole),
 * dan tidak bisa dobel kecuali role yang memang ada beberapa di susunan (Loyal Servant, Minion).
 */
public class SetRoleCommand {

    /** Kata untuk mengembalikan player ke pembagian acak. */
    private static final String RANDOM = "acak";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("setrole")
            .requires(AvalonCommands::isAdmin)
            .executes(ctx -> list(ctx.getSource(), gameManager))
            .then(Commands.argument("playername", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                    gameManager.getRegisteredPlayers(), builder))
                .then(Commands.argument("role", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(choices(gameManager), builder))
                    .executes(ctx -> execute(ctx.getSource(), gameManager,
                        StringArgumentType.getString(ctx, "playername"),
                        StringArgumentType.getString(ctx, "role"))))));
    }

    /** Role yang bisa dipilih sekarang (huruf kecil), plus "acak". */
    private static List<String> choices(GameManager gameManager) {
        Set<String> names = new LinkedHashSet<>();
        for (Role role : gameManager.getActiveRoles()) names.add(role.name().toLowerCase(Locale.ROOT));
        names.add(RANDOM);
        return new ArrayList<>(names);
    }

    /** Tanpa argumen: tampilkan cara pakai dan role yang sudah diatur. */
    private static int list(CommandSourceStack sender, GameManager gameManager) {
        sender.sendSystemMessage(Txt.t("Usage: /avalon setrole <playername> <role|" + RANDOM + ">", ChatFormatting.RED));
        Map<String, Role> forced = gameManager.getForcedRoles();
        if (forced.isEmpty()) {
            sender.sendSystemMessage(Txt.t("Belum ada role yang diatur; semua diacak.", ChatFormatting.GRAY));
        }
        for (Map.Entry<String, Role> entry : forced.entrySet()) {
            sender.sendSystemMessage(
                Txt.t(entry.getKey(), ChatFormatting.YELLOW)
                    .append(Txt.t(" -> ", ChatFormatting.GRAY))
                    .append(Txt.t(entry.getValue().name(), ChatFormatting.WHITE))
            );
        }
        return 1;
    }

    private static int execute(CommandSourceStack sender, GameManager gameManager, String playerName, String roleName) {

        Role role = null;
        if (!roleName.equalsIgnoreCase(RANDOM)) {
            try {
                role = Role.valueOf(roleName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                sender.sendSystemMessage(Txt.t("Role tidak dikenal. Pilihan: "
                    + String.join(", ", choices(gameManager)), ChatFormatting.RED));
                return 1;
            }
        }

        switch (gameManager.setForcedRole(playerName, role)) {
            case OK -> {
                if (role == null) {
                    sender.sendSystemMessage(Txt.t("✔ Role " + playerName + " kembali diacak.", ChatFormatting.YELLOW));
                } else {
                    sender.sendSystemMessage(Txt.t("✔ " + playerName + " akan menjadi " + role.name()
                        + " di game berikutnya.", ChatFormatting.GREEN));
                }
            }
            case GAME_RUNNING ->
                sender.sendSystemMessage(Txt.t("Tidak bisa mengatur role saat game sedang berjalan!", ChatFormatting.RED));
            case NOT_REGISTERED ->
                sender.sendSystemMessage(Txt.t(playerName + " belum terdaftar. Daftarkan dulu: /avalon regis "
                    + playerName, ChatFormatting.RED));
            case BAD_PLAYER_COUNT ->
                sender.sendSystemMessage(Txt.t("Jumlah player terdaftar harus 5-10 dulu (sekarang "
                    + gameManager.getRegisteredPlayers().size() + "), karena role yang aktif tergantung jumlahnya.",
                    ChatFormatting.RED));
            case NOT_ACTIVE ->
                sender.sendSystemMessage(Txt.t(role.name() + " tidak aktif untuk "
                    + gameManager.getRegisteredPlayers().size() + " player. Yang aktif: "
                    + String.join(", ", choices(gameManager)), ChatFormatting.RED));
            case TAKEN ->
                sender.sendSystemMessage(Txt.t(role.name() + " sudah diatur untuk player lain.", ChatFormatting.RED));
        }
        return 1;
    }
}

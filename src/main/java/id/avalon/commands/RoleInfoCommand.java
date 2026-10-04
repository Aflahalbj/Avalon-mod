package id.avalon.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import id.avalon.models.Role;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RoleInfoCommand {

    /** Setara RoleInfoTabCompleter. */
    private static final List<String> ROLES = List.of(
        "merlin",
        "percival",
        "loyal",
        "assassin",
        "morgana",
        "mordred",
        "oberon",
        "minion"
    );

    private static final Map<String, String> ROLE_INFO = Map.ofEntries(

        Map.entry("merlin",
            """
            §6═══════════════════════

              §b§lMERLIN

              §rMerlin adalah kunci dari penyembuhan ratu amaryn.

              §rHanya Merlin yang dapat membaca mantra penyembuhan.

              §aKemampuan:
               §r• Melihat semua kubu jahat
               §r• Tidak dapat melihat Mordred

              §cPenting:
               §e• Jaga identitas Merlin.
               §e• Jangan sampai kubu jahat mengetahui siapa Merlin.
            """
        ),

        Map.entry("percival",
            """
            §6═══════════════════════

              §b§lPERCIVAL

              §rMengetahui siapa Merlin dan Morgana, tetapi tidak tahu mana Merlin yang asli.

              §aTugas:
               §r• Melindungi Merlin
               §r• Membingungkan kubu jahat
               §r• Bisa berpura-pura menjadi Merlin
            """
        ),

        Map.entry("loyal",
            """
            §6═══════════════════════

              §b§lLOYAL SERVANT OF ARTHUR

              §rTidak memiliki kemampuan khusus.

              §aTugas:
              §r• Menggunakan logika
              §r• Mengidentifikasi pemain jahat
              §r• Membantu menyelesaikan misi
            """
        ),

        Map.entry("assassin",
            """
            §6═══════════════════════
              §c§lASSASSIN

              §rMengetahui semua pemain jahat (kecuali Oberon).

              §aKemampuan:
              §r• Sabotase misi
              §r• Membunuh Merlin di akhir permainan

              §eJika kubu jahat gagal menyabotase misi, Assassin dapat menebak siapa Merlin.
              §eJika benar, kubu jahat tetap menang.
            """
        ),

        Map.entry("morgana",
            """
            §6═══════════════════════
              §c§lMORGANA

              §rMengetahui semua pemain jahat (kecuali Oberon).

              §aKemampuan:
              §r• Sabotase misi
              §r• Menipu Percival

              §eMorgana akan terlihat sebagai Merlin di mata Percival.
            """
        ),

        Map.entry("mordred",
            """
            §6═══════════════════════
              §4§lMORDRED

              §rMengetahui semua kubu jahat (kecuali Oberon).

              §aKemampuan:
              §r• Sabotase misi
              §r• Tidak terlihat oleh Merlin

              §eMerlin tidak mengetahui identitas Mordred.
            """
        ),

        Map.entry("oberon",
            """
            §6═══════════════════════
              §c§lOBERON

              §rOberon tidak mengetahui siapa teman-teman jahatnya.

              §rKubu jahat lainnya juga tidak tahu bahwa Oberon adalah rekan mereka.

              §aKemampuan:
              §r• Sabotase misi
            """
        ),

        Map.entry("minion",
            """
            §6═══════════════════════

              §c§lMINIONS OF MORDRED

              §rMengetahui semua pemain jahat (kecuali Oberon).

              §aKemampuan:
              §r• Sabotase misi

              §eTidak memiliki kekuatan khusus lainnya.

            """
        )
    );

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, GameManager gameManager) {
        dispatcher.register(Commands.literal("roleinfo")
            .executes(ctx -> execute(ctx.getSource(), gameManager, null))
            .then(Commands.argument("role", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ROLES, builder))
                .executes(ctx -> execute(ctx.getSource(), gameManager,
                    StringArgumentType.getString(ctx, "role")))));
    }

    private static int execute(CommandSourceStack sender, GameManager gameManager, String arg) {

        if (arg == null) {

            if (!(sender.getEntity() instanceof ServerPlayer player)) {
                sender.sendSystemMessage(Txt.legacy("§cHanya player yang bisa melihat role sendiri."));
                return 1;
            }

            Role role = gameManager.getRole(player);

            if (role == null) {
                sender.sendSystemMessage(Txt.legacy("§cKamu tidak sedang memiliki role."));
                return 1;
            }

            String key = switch (role) {
                case MERLIN -> "merlin";
                case PERCIVAL -> "percival";
                case LOYAL_SERVANT -> "loyal";
                case ASSASSIN -> "assassin";
                case MORGANA -> "morgana";
                case MORDRED -> "mordred";
                case OBERON -> "oberon";
                case MINION_OF_MORDRED -> "minion";
            };

            sender.sendSystemMessage(Txt.legacy(ROLE_INFO.get(key)));
            return 1;
        }

        String role = arg.toLowerCase(Locale.ROOT);

        String info = ROLE_INFO.get(role);

        if (info == null) {
            sender.sendSystemMessage(Txt.legacy("§cRole tidak ditemukan."));
            return 1;
        }

        sender.sendSystemMessage(Txt.legacy(info));
        return 1;
    }
}

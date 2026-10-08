package id.avalon.listeners;

import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;
import java.util.Set;

public class CommandBlockListener {

    private final GameManager gm;

    private static final Set<String> BLOCKED = Set.of(
            "msg",
            "tell",
            "w",
            "whisper"
    );

    public CommandBlockListener(GameManager gm) {
        this.gm = gm;
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {

        // Hanya command dari player (setara PlayerCommandPreprocessEvent)
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // Hanya pemain game selama game berjalan; player lain di server bebas berkirim pesan
        if (!gm.isOneSlot(player)) {
            return;
        }

        String cmd = event.getParseResults().getReader().getString().toLowerCase(Locale.ROOT);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);

        // Nama command-nya saja, tanpa namespace (/minecraft:msg sama dengan /msg)
        String name = cmd.split(" ", 2)[0];
        name = name.substring(name.indexOf(':') + 1);

        if (BLOCKED.contains(name)) {
            event.setCanceled(true);
            player.sendSystemMessage(
                    Txt.t("Ciee mau kirim pesan ke siapa tuh.", ChatFormatting.RED)
            );
        }
    }
}

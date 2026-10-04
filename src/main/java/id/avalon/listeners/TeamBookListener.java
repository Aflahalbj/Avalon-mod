package id.avalon.listeners;

import id.avalon.AvalonMod;
import id.avalon.core.Fx;
import id.avalon.core.Txt;
import id.avalon.gui.TeamSelectionGUI;
import id.avalon.managers.GameManager;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Menangani klik kanan pada item "Buku Pemilihan Tim".
 * Membuka GUI pemilihan tim, hanya untuk Raja Aktif saat game berjalan.
 */
public class TeamBookListener implements ItemGuard.InventoryClickRule {

    private final GameManager gameManager;

    public TeamBookListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @SubscribeEvent
    public void onDrop(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer)) return;

        ItemStack item = event.getEntity().getItem();

        if (!GameManager.isTeamBook(item))
            return;

        ItemGuard.cancelToss(event);
    }

    @Override
    public boolean onInventoryClick(ItemGuard.ClickContext ctx) {
        // onInventoryClick: item yang diklik adalah buku
        if (GameManager.isTeamBook(ctx.current())) return true;

        // onDrag: item yang di-drag adalah buku
        return ctx.isDrag() && GameManager.isTeamBook(ctx.cursor());
    }

    @SubscribeEvent
    public void onInteractItem(PlayerInteractEvent.RightClickItem event) {
        handleInteract(event);
    }

    @SubscribeEvent
    public void onInteractBlock(PlayerInteractEvent.RightClickBlock event) {
        handleInteract(event);
    }

    private void handleInteract(PlayerInteractEvent event) {

        ItemStack item = event.getItemStack();

        if (!GameManager.isTeamBook(item)) return;

        ItemGuard.cancelInteract(event);

        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ItemGuard.firstInteract(player)) return;

        if (!gameManager.isGameRunning()) {
            player.sendSystemMessage(Txt.t("Game belum berjalan!", ChatFormatting.RED));
            return;
        }

        if (!gameManager.isKing(player)) {
            player.sendSystemMessage(
                Txt.t("Hanya ", ChatFormatting.RED)
                    .append(Txt.t("Raja Aktif", ChatFormatting.GOLD))
                    .append(Txt.t(" yang bisa membuka menu ini!", ChatFormatting.RED))
            );
            return;
        }

        int missionNumber = gameManager.getCurrentMission();
        int playerCount   = gameManager.getRegisteredPlayers().size();
        int teamSize      = TeamSelectionGUI.getTeamSize(playerCount, missionNumber);

        List<String> registered    = new ArrayList<>(gameManager.getRegisteredPlayers());
        List<String> alreadyPicked = gameManager.getTeamSelectionSession(player);
        List<String> available     = new ArrayList<>(registered);
        available.removeAll(alreadyPicked);

        AvalonMod.getInstance().getTeamSelectionListener().open(player, teamSize, available, alreadyPicked);
        Fx.sound(player, SoundEvents.BOOK_PAGE_TURN, 1.0f, 1.0f);
    }
}

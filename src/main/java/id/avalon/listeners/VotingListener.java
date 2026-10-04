package id.avalon.listeners;

import id.avalon.managers.GameManager;
import id.avalon.managers.VotingManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Menangani interaksi player dengan item voting (Setuju / Tolak).
 */
public class VotingListener implements ItemGuard.InventoryClickRule {

    private final GameManager  gameManager;
    private final VotingManager votingManager;

    public VotingListener(GameManager gameManager, VotingManager votingManager) {
        this.gameManager  = gameManager;
        this.votingManager = votingManager;
    }

    /** Cegah drop item voting. */
    @SubscribeEvent
    public void onDrop(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer)) return;
        if (votingManager.isVoteItem(event.getEntity().getItem())) {
            ItemGuard.cancelToss(event);
        }
    }

    /** Cegah klik di inventory untuk item voting + cegah drag item voting. */
    @Override
    public boolean onInventoryClick(ItemGuard.ClickContext ctx) {
        if (votingManager.isVoteItem(ctx.current())) return true;
        return ctx.isDrag() && votingManager.isVoteItem(ctx.cursor());
    }

    /** Klik kanan item voting → catat suara. */
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
        if (!votingManager.isVoteItem(item)) return;

        ItemGuard.cancelInteract(event);

        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ItemGuard.firstInteract(player)) return;
        if (!gameManager.isGameRunning() || !votingManager.isVotingActive()) return;

        String voteType = votingManager.getVoteType(item);
        if (voteType == null) return;

        votingManager.castVote(player, voteType);
    }
}

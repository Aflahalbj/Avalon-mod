package id.avalon.listeners;

import id.avalon.managers.GameManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingSwapItemsEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Inventory 1 slot selama game: player hanya punya slot hotbar pertama.
 * Di sini semua jalan keluar-masuk item ditutup (drop, tukar tangan, pungut, klik inventory);
 * slot yang dipegang dipaksa tetap slot pertama di GameManager#tick, dan tampilannya di client
 * (lihat OneSlotHud).
 */
public class OneSlotListener implements ItemGuard.InventoryClickRule {

    private final GameManager gameManager;

    public OneSlotListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    // LOWEST: listener lain yang sudah mengurus item-nya sendiri jalan lebih dulu
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDrop(ItemTossEvent event) {
        if (event.isCanceled()) return;
        if (event.getPlayer() instanceof ServerPlayer player && gameManager.isOneSlot(player)) {
            ItemGuard.cancelToss(event);
        }
    }

    @SubscribeEvent
    public void onSwapHand(LivingSwapItemsEvent.Hands event) {
        if (event.getEntity() instanceof ServerPlayer player && gameManager.isOneSlot(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPickup(EntityItemPickupEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && gameManager.isOneSlot(player)) {
            event.setCanceled(true);
        }
    }

    /** Inventory player sendiri tidak bisa diutak-atik; menu Avalon (pilih tim, dll.) tetap jalan. */
    @Override
    public boolean onInventoryClick(ItemGuard.ClickContext ctx) {
        return ctx.menu() instanceof InventoryMenu && gameManager.isOneSlot(ctx.player());
    }
}

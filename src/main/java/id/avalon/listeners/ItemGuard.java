package id.avalon.listeners;

import id.avalon.AvalonMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Utilitas bersama untuk listener:
 *  - Konteks klik inventory (pengganti InventoryClickEvent / InventoryDragEvent)
 *  - Cancel drop item (pengganti PlayerDropItemEvent#setCancelled)
 *  - Cancel interaksi klik kanan (pengganti PlayerInteractEvent#setCancelled)
 */
public final class ItemGuard {

    private ItemGuard() {}

    /** Data klik inventory (setara InventoryClickEvent). */
    public record ClickContext(AbstractContainerMenu menu, int slotId, int button, ClickType clickType,
                               ServerPlayer player, ItemStack current, ItemStack cursor) {

        /** Setara ClickType#isKeyboardClick() di Bukkit: NUMBER_KEY, DROP, CONTROL_DROP, SWAP_OFFHAND. */
        public boolean isKeyboardClick() {
            return clickType == ClickType.SWAP || clickType == ClickType.THROW;
        }

        /** Setara InventoryDragEvent. */
        public boolean isDrag() {
            return clickType == ClickType.QUICK_CRAFT;
        }
    }

    /** Aturan listener yang bisa membatalkan klik inventory. */
    public interface InventoryClickRule {
        /** @return true kalau klik harus dibatalkan. */
        boolean onInventoryClick(ClickContext ctx);
    }

    /**
     * Dipanggil dari mixin AbstractContainerMenu#clicked (server).
     * @return true kalau klik dibatalkan.
     */
    public static boolean shouldCancelClick(AbstractContainerMenu menu, int slotId, int button,
                                            ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer sp)) return false;
        AvalonMod mod = AvalonMod.getInstance();
        if (mod == null) return false;

        ItemStack current = (slotId >= 0 && slotId < menu.slots.size())
                ? menu.getSlot(slotId).getItem()
                : ItemStack.EMPTY;
        ItemStack cursor = menu.getCarried();

        ClickContext ctx = new ClickContext(menu, slotId, button, clickType, sp, current, cursor);
        for (InventoryClickRule rule : mod.getInventoryClickRules()) {
            if (rule.onInventoryClick(ctx)) return true;
        }
        return false;
    }

    /** Batalkan drop item dan kembalikan item ke inventory player. */
    public static void cancelToss(ItemTossEvent event) {
        event.setCanceled(true);
        Player player = event.getPlayer();
        ItemStack stack = event.getEntity().getItem().copy();
        event.getEntity().discard();
        if (stack.isEmpty()) return;

        Inventory inv = player.getInventory();
        if (inv.getSelected().isEmpty()) {
            inv.setItem(inv.selected, stack);
        } else {
            inv.add(stack);
        }
        player.containerMenu.broadcastChanges();
    }

    /** Batalkan interaksi klik kanan (item / block) dengan hasil SUCCESS. */
    public static void cancelInteract(PlayerInteractEvent event) {
        if (event.isCancelable()) event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    // ── Dedupe klik kanan (RightClickBlock + RightClickItem dari satu klik) ──

    private static final Map<UUID, Long> lastHandled = new HashMap<>();

    /**
     * @return true kalau klik kanan player ini belum diproses dalam 2 tick terakhir.
     */
    public static boolean firstInteract(ServerPlayer player) {
        long now = player.level().getGameTime();
        Long last = lastHandled.get(player.getUUID());
        if (last != null && now - last < 3) return false;
        lastHandled.put(player.getUUID(), now);
        return true;
    }

    public static void forget(UUID uuid) {
        lastHandled.remove(uuid);
    }
}

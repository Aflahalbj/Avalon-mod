package id.avalon.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Chest GUI 54 slot yang semua kliknya dibatalkan lalu diteruskan ke handler
 * (setara Bukkit.createInventory + InventoryClickEvent yang di-cancel).
 */
public class AvalonMenu extends ChestMenu {

    /** Setara listener InventoryClickEvent untuk GUI ini. rawSlot sama seperti Bukkit. */
    public interface ClickHandler {
        void onClick(AvalonMenu menu, ServerPlayer player, int rawSlot, ClickType clickType, int button);
    }

    public static final int SIZE = 54;

    private final ClickHandler handler;

    public AvalonMenu(int containerId, Inventory playerInventory, Container container, ClickHandler handler) {
        super(MenuType.GENERIC_9x6, containerId, playerInventory, container, 6);
        this.handler = handler;
    }

    /** Buka GUI untuk player (setara player.openInventory(inv)). */
    public static void open(ServerPlayer player, Component title, SimpleContainer container, ClickHandler handler) {
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new AvalonMenu(id, inv, container, handler),
                title
        ));
    }

    public static SimpleContainer newContainer() {
        return new SimpleContainer(SIZE);
    }

    public Container inv() {
        return this.getContainer();
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // Semua aksi dibatalkan (event.setCancelled(true)). Double click & drag diabaikan.
        if (clickType == ClickType.PICKUP_ALL || clickType == ClickType.QUICK_CRAFT) return;
        if (handler != null && player instanceof ServerPlayer sp) {
            handler.onClick(this, sp, slotId, clickType, button);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return false;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}

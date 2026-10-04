package id.avalon.mixin;

import id.avalon.listeners.ItemGuard;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pengganti InventoryClickEvent / InventoryDragEvent Bukkit:
 * klik di inventory apa pun bisa dibatalkan oleh listener Avalon.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void avalon$onClicked(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        if (ItemGuard.shouldCancelClick((AbstractContainerMenu) (Object) this, slotId, button, clickType, player)) {
            ci.cancel();
        }
    }
}

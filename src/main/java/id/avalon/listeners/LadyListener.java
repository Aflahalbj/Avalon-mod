package id.avalon.listeners;

import id.avalon.core.Fx;
import id.avalon.core.Txt;
import id.avalon.gui.AvalonMenu;
import id.avalon.gui.LadyGUI;
import id.avalon.managers.GameManager;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Item & GUI Lady of the Lake: klik kanan item membuka GUI (hanya pemegang Lady, hanya di fasenya),
 * klik kepala pemain memilih / membatalkan, tombol konfirmasi memulai pemeriksaan.
 *
 * Pilihan yang belum dikonfirmasi disimpan di GameManager: ia bertahan kalau GUI-nya ditutup, dan
 * dipakai kalau waktu memilihnya habis.
 */
public class LadyListener implements ItemGuard.InventoryClickRule, AvalonMenu.ClickHandler {

    private final GameManager gameManager;

    public LadyListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @SubscribeEvent
    public void onDrop(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer)) return;
        if (!GameManager.isLadyItem(event.getEntity().getItem())) return;
        ItemGuard.cancelToss(event);
    }

    @Override
    public boolean onInventoryClick(ItemGuard.ClickContext ctx) {
        if (GameManager.isLadyItem(ctx.current())) return true;
        return ctx.isDrag() && GameManager.isLadyItem(ctx.cursor());
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
        if (!GameManager.isLadyItem(item)) return;

        ItemGuard.cancelInteract(event);

        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ItemGuard.firstInteract(player)) return;

        // Item yang tertinggal di tangan di luar fasenya tidak boleh memulai pemeriksaan
        if (!gameManager.isLadyActive() || !gameManager.isLadyHolder(player)) {
            player.sendSystemMessage(Txt.t("Belum waktunya memakai Lady of the Lake.", ChatFormatting.RED));
            return;
        }

        AvalonMenu.open(player, LadyGUI.title(),
            new LadyGUI().create(gameManager.getLadyCandidates(), gameManager.getLadyPastHolders(),
                gameManager.getLadySelection()), this);
        Fx.sound(player, SoundEvents.CONDUIT_AMBIENT_SHORT, 1.0f, 1.2f);
    }

    @Override
    public void onClick(AvalonMenu menu, ServerPlayer player, int slot, ClickType clickType, int button) {
        if (slot < 0 || slot >= AvalonMenu.SIZE) return;

        // GUI yang masih terbuka setelah fasenya lewat (waktu habis, game dihentikan)
        if (!gameManager.isLadyActive() || !gameManager.isLadyHolder(player)) {
            player.closeContainer();
            return;
        }

        String selected = gameManager.getLadySelection();

        if (slot == LadyGUI.CONFIRM_SLOT) {
            if (selected == null) {
                player.sendSystemMessage(Txt.t("Pilih satu pemain terlebih dahulu!", ChatFormatting.RED));
                Fx.sound(player, SoundEvents.VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
            player.closeContainer();
            gameManager.confirmLadyTarget(player, selected);
            return;
        }

        // Kepala pemain yang bisa diperiksa: di pool = pilih, di slot pilihan = batalkan
        String name = LadyGUI.getCandidate(menu.inv().getItem(slot));
        if (name == null || !gameManager.getLadyCandidates().contains(name)) return;

        if (slot == LadyGUI.TARGET_SLOT) {
            selected = null;
            Fx.sound(player, SoundEvents.NOTE_BLOCK_BELL, 1.0f, 1.0f);
        } else {
            selected = name;
            Fx.sound(player, SoundEvents.UI_BUTTON_CLICK, 1.0f, 1.0f);
        }
        gameManager.setLadySelection(selected);
        new LadyGUI().render(menu.inv(), gameManager.getLadyCandidates(), gameManager.getLadyPastHolders(), selected);
    }
}

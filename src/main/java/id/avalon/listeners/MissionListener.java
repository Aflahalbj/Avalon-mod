package id.avalon.listeners;

import id.avalon.block.ModBlocks;
import id.avalon.managers.GameManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Menangani mekanik misi:
 *  1. Klik kanan sambil memegang baterai → ganti mode sabotase (kubu jahat)
 *  2. Klik kanan item skip diskusi
 *  3. Cegah drop/pindah item skip diskusi
 *  4. Cegah player game menghancurkan block (anggota tim misi ada di mode Survival)
 *  5. Cancel fall damage selama game berjalan
 *
 * Mengambil & memasang baterai (klik kiri di rak) diatur BatteryMission lewat BatteryRackBlock.
 */
public class MissionListener implements ItemGuard.InventoryClickRule {

    private final GameManager gameManager;

    public MissionListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    // ── Cegah drop item skip diskusi ──────────────────────────────────────────

    @SubscribeEvent
    public void onDrop(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer)) return;
        if (gameManager.isDiscussionSkipItem(event.getEntity().getItem())) {
            ItemGuard.cancelToss(event);
        }
    }

    // ── Cegah pindah item skip diskusi di inventory + semua keyboard click ────

    @Override
    public boolean onInventoryClick(ItemGuard.ClickContext ctx) {
        if (gameManager.isDiscussionSkipItem(ctx.current())
                || gameManager.isDiscussionSkipItem(ctx.cursor())) {
            return true;
        }

        // onPickup: semua klik keyboard (angka hotbar, Q, F) dibatalkan
        return ctx.isKeyboardClick();
    }

    // ── Klik kanan baterai (mode sabotase) / item skip diskusi ────────────────

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onInteractItem(PlayerInteractEvent.RightClickItem event) {
        handleInteract(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onInteractBlock(PlayerInteractEvent.RightClickBlock event) {
        handleInteract(event);
    }

    private void handleInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItemStack();

        // ── Skip diskusi ───────────────────────────────────────────────────────
        if (gameManager.isDiscussionSkipItem(item)) {
            ItemGuard.cancelInteract(event);
            if (event.getEntity() instanceof ServerPlayer player && ItemGuard.firstInteract(player)) {
                gameManager.handleDiscussionSkip(player);
            }
            return;
        }

        // ── Mode sabotase ──────────────────────────────────────────────────────
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!item.is(ModBlocks.BATTERY.get())) return;
        if (!gameManager.isGameRunning() || !gameManager.isMissionActive()) return;
        if (!ItemGuard.firstInteract(player)) return;

        // Kubu baik: kliknya tidak melakukan apa-apa (dan dari luar terlihat sama)
        gameManager.getBatteryMission().toggleSabotage(player);
    }

    // ── Cegah menghancurkan block ─────────────────────────────────────────────

    /** Anggota tim misi ada di mode Survival supaya bisa berinteraksi bebas, tapi dunianya jangan dirusak. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (player.getAbilities().instabuild) return;
        if (gameManager.isOneSlot(player)) event.setCanceled(true);
    }

    // ── Cancel fall damage selama game berjalan ───────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onFallDamage(LivingAttackEvent event) {
        if (!event.getSource().is(DamageTypeTags.IS_FALL)) return;
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getEntity().level().isClientSide) return;
        if (!gameManager.isGameRunning()) return;
        event.setCanceled(true);
    }
}

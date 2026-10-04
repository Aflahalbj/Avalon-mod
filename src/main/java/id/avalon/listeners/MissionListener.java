package id.avalon.listeners;

import id.avalon.managers.GameManager;
import id.avalon.models.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingSwapItemsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Menangani mekanik misi:
 *  1. Klik kanan "Sabotase" → triggerSabotage (kubu jahat)
 *  2. Block break tanaman misi dengan shears → izinkan (finishMission sukses)
 *  3. Cegah drop/pindah shears misi, tanaman misi, item skip diskusi
 *  4. Cancel fall damage selama game berjalan
 */
public class MissionListener implements ItemGuard.InventoryClickRule {

    private final GameManager gameManager;

    public MissionListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    // ── Cegah drop shears misi ────────────────────────────────────────────────

    @SubscribeEvent
    public void onDrop(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer)) return;
        ItemStack item = event.getEntity().getItem();
        if (gameManager.isMissionShears(item)
                || gameManager.isMissionPlant(item)
                || gameManager.isDiscussionSkipItem(item)) {
            ItemGuard.cancelToss(event);
        }
    }

    // ── Cegah pindah shears misi di inventory + semua keyboard click ──────────

    @Override
    public boolean onInventoryClick(ItemGuard.ClickContext ctx) {
        if (gameManager.isMissionShears(ctx.current())
                || gameManager.isMissionShears(ctx.cursor())
                || gameManager.isMissionPlant(ctx.current())
                || gameManager.isMissionPlant(ctx.cursor())
                || gameManager.isDiscussionSkipItem(ctx.current())
                || gameManager.isDiscussionSkipItem(ctx.cursor())) {
            return true;
        }

        // onPickup: semua klik keyboard (angka hotbar, Q, F) dibatalkan
        return ctx.isKeyboardClick();
    }

    // ── Cegah swap tangan (F) untuk tanaman & shears misi ─────────────────────

    @SubscribeEvent
    public void onSwapHand(LivingSwapItemsEvent.Hands event) {
        if (!(event.getEntity() instanceof Player)) return;
        ItemStack main = event.getItemSwappedToOffHand();
        ItemStack off  = event.getItemSwappedToMainHand();
        if (gameManager.isMissionShears(main)
                || gameManager.isMissionShears(off)
                || gameManager.isMissionPlant(main)
                || gameManager.isMissionPlant(off)) {

            event.setCanceled(true);
        }
    }

    // ── Klik kanan shears "Sabotase" / item skip diskusi ─────────────────────

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

        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        if (!gameManager.isSabotaseShears(item)) return;
        if (!gameManager.isGameRunning()) return;
        if (!gameManager.isMissionActive()) return;

        ItemGuard.cancelInteract(event);
        if (!ItemGuard.firstInteract(player)) return;

        // Hanya kubu jahat yang bisa sabotase
        Role role = gameManager.getRole(player);
        if (role == null || !role.isEvil()) return;

        gameManager.triggerSabotage(player);
    }

    // ── Block break tanaman misi dengan shears ───────────────────────────────

    /**
     * Anggota tim misi (mode Survival) hanya boleh menghancurkan blok tanaman misi
     * di salah satu koordinat PLANT_LOCATIONS, dan hanya saat membawa shears misi.
     * Tanaman tidak di-drop, tapi langsung masuk inventory.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!gameManager.isGameRunning()) return;
        if (!gameManager.isMissionActive()) return;

        // Hanya proses jika player membawa shears misi
        ItemStack item = player.getMainHandItem();
        if (!gameManager.isMissionShears(item)) return;

        BlockPos pos = event.getPos();
        int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();

        // Cek apakah blok berada di salah satu lokasi tanaman misi
        boolean isMissionPlantPos = false;
        for (int i = 0; i < GameManager.PLANT_LOCATIONS.length; i++) {
            int[] loc = GameManager.PLANT_LOCATIONS[i];
            if (bx == loc[0] && bz == loc[2]
                    && (by == loc[1] || by == loc[1] + 1)) {
                isMissionPlantPos = true;
                break;
            }
        }

        // Selalu cancel event bawaan; blok yang valid dihancurkan manual tanpa drop
        event.setCanceled(true);

        if (!isMissionPlantPos) {
            return;
        }

        // Cek bahwa blok ini memang tanaman misi yang valid
        BlockState state = event.getState();
        boolean isValidPlant = false;
        for (Block mat : GameManager.PLANT_MATERIALS) {
            if (state.is(mat)) { isValidPlant = true; break; }
        }

        if (isValidPlant && event.getLevel() instanceof ServerLevel level) {
            // Hancurkan blok tanpa drop, beri item tanaman ke player
            level.destroyBlock(pos, false, player);
            player.getInventory().add(new ItemStack(state.getBlock().asItem()));
        }
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

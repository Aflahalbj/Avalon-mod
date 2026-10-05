package id.avalon.listeners;

import id.avalon.managers.GameManager;
import id.avalon.models.Role;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.ArrowNockEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Menangani mekanik fase assassination:
 *  1. Klik kanan item skip assassination → handleAssassinationSkip
 *  2. Arrow assassin kena entity → handleAssassinArrowHit
 *  3. Arrow assassin meleset (jatuh ke tanah) → handleAssassinArrowMiss
 *  4. Cegah drop / pindah item assassination
 */
public class AssassinationListener implements ItemGuard.InventoryClickRule {

    private final GameManager gameManager;

    public AssassinationListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    // ── Klik kanan item skip ──────────────────────────────────────────────────

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
        if (!gameManager.isAssassinationSkipItem(item)) return;

        ItemGuard.cancelInteract(event);
        if (event.getEntity() instanceof ServerPlayer player && ItemGuard.firstInteract(player)) {
            gameManager.handleAssassinationSkip(player);
        }
    }

    // ── Arrow kena entity / meleset ───────────────────────────────────────────

    /**
     * Arrow assassin kena entity: batalkan damage (efek visual saja, petir di GameManager).
     * Arrow assassin kena blok: dianggap meleset.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;
        if (arrow.level().isClientSide) return;
        if (!isAssassinArrow(arrow)) return;

        HitResult hit = event.getRayTraceResult();
        if (hit instanceof EntityHitResult entityHit) {
            // Cancel damage — assassination hanya efek visual
            event.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);

            Entity target = entityHit.getEntity();
            gameManager.handleAssassinArrowHit(arrow, target);
        } else if (hit.getType() == HitResult.Type.BLOCK) {
            // Hanya proses jika kena blok (bukan entity)
            gameManager.handleAssassinArrowMiss();
        }
    }

    // ── Bow tanpa arrow ───────────────────────────────────────────────────────

    /**
     * Inventory cuma 1 slot, jadi assassin tidak membawa arrow. Bow-nya ber-Infinity (menembak tanpa
     * arrow); di sini bow dibuat tetap bisa ditarik walau tidak ada arrow sama sekali.
     * Jalan di client dan server.
     */
    @SubscribeEvent
    public void onArrowNock(ArrowNockEvent event) {
        if (event.hasAmmo() || !gameManager.isAssassinBowItem(event.getBow())) return;
        event.getEntity().startUsingItem(event.getHand());
        event.setAction(InteractionResultHolder.consume(event.getBow()));
    }

    // ── Cegah drop bow / skip assassination ───────────────────────────────────

    @SubscribeEvent
    public void onDrop(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer)) return;
        ItemStack item = event.getEntity().getItem();
        if (gameManager.isAssassinBowItem(item) || gameManager.isAssassinationSkipItem(item)) {
            ItemGuard.cancelToss(event);
        }
    }

    // ── Cegah pindah item di inventory ───────────────────────────────────────

    @Override
    public boolean onInventoryClick(ItemGuard.ClickContext ctx) {
        ItemStack cur    = ctx.current();
        ItemStack cursor = ctx.cursor();
        return gameManager.isAssassinBowItem(cur) || gameManager.isAssassinBowItem(cursor)
                || gameManager.isAssassinationSkipItem(cur)
                || gameManager.isAssassinationSkipItem(cursor);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Cek apakah arrow ditembak oleh assassin. */
    private boolean isAssassinArrow(AbstractArrow arrow) {
        if (!(arrow.getOwner() instanceof Player shooter)) return false;
        return gameManager.getRole(shooter) == Role.ASSASSIN;
    }
}

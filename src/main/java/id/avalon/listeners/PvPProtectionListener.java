package id.avalon.listeners;

import id.avalon.managers.GameManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Selama game, pemain game tidak bisa melukai maupun dilukai player lain.
 * Player di luar game (dan semua orang saat tidak ada game) mengikuti setelan PvP server.
 */
public class PvPProtectionListener {

    private final GameManager gameManager;

    public PvPProtectionListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @SubscribeEvent
    public void onDamage(LivingAttackEvent event) {

        if (!(event.getEntity() instanceof Player victim))
            return;

        if (victim.level().isClientSide)
            return;

        Entity damager = event.getSource().getDirectEntity();
        if (damager == null)
            return;

        // Arrow assassin boleh lewat (damage-nya sendiri dibatalkan AssassinationListener)
        if (damager instanceof AbstractArrow arrow) {
            if (gameManager.isAssassinArrow(arrow)) {
                return;
            }
        }

        Entity attacker = damager instanceof Projectile projectile ? projectile.getOwner() : damager;
        if (!(attacker instanceof Player attackingPlayer))
            return;

        if (gameManager.isOneSlot(victim) || gameManager.isOneSlot(attackingPlayer)) {
            event.setCanceled(true);
        }
    }
}

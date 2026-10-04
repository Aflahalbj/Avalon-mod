package id.avalon.listeners;

import id.avalon.managers.GameManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class PvPProtectionListener {

    private final GameManager gameManager;

    public PvPProtectionListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @SubscribeEvent
    public void onDamage(LivingAttackEvent event) {

        if (!(event.getEntity() instanceof Player))
            return;

        if (event.getEntity().level().isClientSide)
            return;

        Entity damager = event.getSource().getDirectEntity();
        if (damager == null)
            return;

        // Arrow assassin boleh lewat
        if (damager instanceof AbstractArrow arrow) {
            if (gameManager.isAssassinArrow(arrow)) {
                return;
            }
        }

        // Semua pukulan player diblok
        if (damager instanceof Player) {
            event.setCanceled(true);
            return;
        }

        // Semua projectile player diblok
        if (damager instanceof Projectile projectile) {
            if (projectile.getOwner() instanceof Player) {
                event.setCanceled(true);
            }
        }
    }
}

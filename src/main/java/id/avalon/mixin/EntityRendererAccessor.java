package id.avalon.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Akses ke radius bayangan entity, supaya bayangan player yang sedang tersedot portal
 * tidak tertinggal di tanah.
 */
@Mixin(EntityRenderer.class)
public interface EntityRendererAccessor {

    @Accessor("shadowRadius")
    float avalon$getShadowRadius();

    @Accessor("shadowRadius")
    void avalon$setShadowRadius(float radius);
}

package id.avalon.mixin;

import id.avalon.core.AvalonDimensions;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dimensi Avalon selalu memakai seed tetap, bukan seed world,
 * jadi terrain-nya sama persis di semua world dan server.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void avalon$fixedSeed(CallbackInfoReturnable<Long> cir) {
        if (((ServerLevel) (Object) this).dimension() == AvalonDimensions.AVALON) {
            cir.setReturnValue(AvalonDimensions.AVALON_SEED);
        }
    }
}

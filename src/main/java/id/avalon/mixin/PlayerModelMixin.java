package id.avalon.mixin;

import id.avalon.client.EndingClient;
import id.avalon.client.PortalCutsceneClient;
import id.avalon.client.RevealClient;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pose player saat cutscene portal (tangan/kaki), fase perkenalan (menunduk), dan cutscene akhir:
 * ditimpa setelah animasi vanilla selesai dihitung.
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void avalon$onSetupAnim(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                    float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        PlayerModel<?> model = (PlayerModel<?>) (Object) this;
        PortalCutsceneClient.poseModel(model, entity);
        RevealClient.poseModel(model, entity);
        EndingClient.poseModel(model, entity);
    }
}

package id.avalon.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Akses ke posisi/rotasi kamera, supaya cutscene bisa menaruh kamera lepas dari player.
 */
@Mixin(Camera.class)
public interface CameraAccessor {

    @Invoker("setPosition")
    void avalon$setPosition(double x, double y, double z);

    @Invoker("setRotation")
    void avalon$setRotation(float yRot, float xRot);

    @Accessor("detached")
    void avalon$setDetached(boolean detached);
}

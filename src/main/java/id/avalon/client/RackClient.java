package id.avalon.client;

import id.avalon.block.BatteryRackBlock;
import id.avalon.network.AvalonNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Klik kiri di lubang rak baterai = ambil / pasang baterai. Server tidak tahu titik persis yang
 * diklik kiri, jadi client yang mengirimkannya (AvalonNetwork.RackClick) dan klik kiri biasanya
 * (memukul / menghancurkan block) dibatalkan.
 */
final class RackClient {

    private RackClient() {}

    /** Tombol serang masih ditahan sejak klik rak terakhir: jangan kirim berulang tiap tick. */
    private static boolean held;

    static void tick() {
        if (held && !Minecraft.getInstance().options.keyAttack.isDown()) held = false;
    }

    /** @return true kalau klik kiri ini dipakai rak (klik kiri biasanya harus dibatalkan). */
    static boolean tryClick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;
        BlockState state = mc.level.getBlockState(hit.getBlockPos());
        if (!(state.getBlock() instanceof BatteryRackBlock)) return false;
        // Creative sambil jongkok: klik kiri biasa, supaya raknya tetap bisa dihancurkan
        if (mc.player.getAbilities().instabuild && mc.player.isShiftKeyDown()) return false;
        if (!BatteryRackBlock.wantsLeftClick(state, hit.getBlockPos(), hit.getDirection(), hit.getLocation(),
                mc.player.getMainHandItem())) {
            return false;
        }

        if (!held) {
            held = true;
            AvalonNetwork.CHANNEL.sendToServer(new AvalonNetwork.RackClick(
                    hit.getBlockPos(), hit.getDirection(), hit.getLocation()));
        }
        return true;
    }
}

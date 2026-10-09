package id.avalon.client;

import id.avalon.network.AvalonNetwork;
import id.avalon.network.ClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Handler paket di sisi client. Hanya dipanggil lewat DistExecutor (Dist.CLIENT).
 */
public final class ClientPacketHandler {

    private ClientPacketHandler() {}

    public static void cameraLock(boolean locked, float yaw, float pitch) {
        if (locked) {
            ClientState.lockedYaw = yaw;
            ClientState.lockedPitch = pitch;
            applyRotation(yaw, pitch);
        } else {
            ClientState.lockedYaw = null;
            ClientState.lockedPitch = null;
        }
    }

    public static void movementLock(boolean locked) {
        ClientState.movementLocked = locked;
    }

    public static void oneSlot(boolean active) {
        ClientState.oneSlot = active;
    }

    public static void pillarCutscene(AvalonNetwork.PillarCutscene msg) {
        if (msg.active()) {
            PillarCutsceneClient.start(msg.pos(), msg.faceX(), msg.faceZ(), msg.duration());
        } else {
            PillarCutsceneClient.stop();
        }
    }

    public static void rotate(float yaw, float pitch) {
        applyRotation(yaw, pitch);
    }

    public static void scale(int entityId, float scale) {
        if (Math.abs(scale - 1.0f) < 1.0e-4f) {
            ClientState.scales.remove(entityId);
        } else {
            ClientState.scales.put(entityId, scale);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Entity entity = mc.level.getEntity(entityId);
            if (entity != null) entity.refreshDimensions();
        }
    }

    public static void portalStart(AvalonNetwork.PortalStart msg) {
        PortalCutsceneClient.start(msg);
    }

    public static void portalStop(boolean arrived) {
        if (arrived) {
            PortalCutsceneClient.release();
        } else {
            PortalCutsceneClient.stop();
        }
    }

    public static void eyeOpen() {
        EyeOpenOverlay.start();
    }

    public static void roleShuffle(int seats, int ownSeat, boolean evil) {
        RoleShuffleClient.start(seats, ownSeat, evil);
    }

    public static void reveal(AvalonNetwork.Reveal msg) {
        RevealClient.apply(msg);
    }

    public static void kingRoulette(AvalonNetwork.KingRoulette msg) {
        KingRouletteClient.start(msg.targetSeat(), msg.seats(), msg.king());
    }

    public static void crown(AvalonNetwork.Crown msg) {
        CrownClient.set(msg.king(), msg.seat(), msg.animate());
    }

    public static void lady(AvalonNetwork.Lady msg) {
        LadyClient.set(msg.holder(), msg.seat(), msg.animate());
    }

    public static void ladyInspect(AvalonNetwork.LadyInspect msg) {
        LadyClient.inspect(msg.target(), msg.targetSeat(), msg.holder(), msg.holderSeat(), msg.result());
    }

    public static void endingStart(AvalonNetwork.EndingStart msg) {
        EndingClient.start(msg);
    }

    public static void endingStop() {
        EndingClient.stop();
    }

    static void applyRotation(float yaw, float pitch) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.yHeadRot = yaw;
        player.yHeadRotO = yaw;
        player.yBodyRot = yaw;
        player.yBodyRotO = yaw;
    }
}

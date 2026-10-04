package id.avalon.network;

import java.util.HashMap;
import java.util.Map;

/**
 * State yang diterima client dari server. Tidak menyentuh kelas client-only,
 * jadi aman dirujuk dari kode common (mis. handler EntityEvent.Size).
 */
public final class ClientState {

    private ClientState() {}

    /** null = kamera tidak dikunci. */
    public static Float lockedYaw = null;
    public static Float lockedPitch = null;

    public static boolean movementLocked = false;

    /** entityId → scale (pengganti Attribute.SCALE). */
    public static final Map<Integer, Float> scales = new HashMap<>();

    public static float getScale(int entityId) {
        return scales.getOrDefault(entityId, 1.0f);
    }

    public static void reset() {
        lockedYaw = null;
        lockedPitch = null;
        movementLocked = false;
        scales.clear();
    }
}

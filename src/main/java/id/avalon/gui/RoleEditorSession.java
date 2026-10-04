package id.avalon.gui;

import id.avalon.models.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Session editor berbasis fixed-slot.
 *
 * goodSlots[0..5]  → GOOD_ACTIVE slot 0,1,2,9,10,11
 * evilSlots[0..5]  → EVIL_ACTIVE slot 8,7,6,17,16,15
 *
 * null di array berarti slot kosong (question mark).
 * Posisi tidak bergeser saat role dihapus.
 */
public class RoleEditorSession {

    // Jumlah slot aktif untuk masing-masing kubu
    private static final int GOOD_SLOT_COUNT = 6;
    private static final int EVIL_SLOT_COUNT = 6;

    private final int playerCount;

    // Array fixed-slot; null = kosong
    private final Role[] goodSlots = new Role[GOOD_SLOT_COUNT];
    private final Role[] evilSlots = new Role[EVIL_SLOT_COUNT];

    // Slot yang sedang dipilih player (untuk diisi dari pool).
    // Format: "good:0", "evil:2", atau null kalau belum pilih.
    private String pendingSlot = null;

    public RoleEditorSession(int playerCount, List<Role> initialRoles) {
        this.playerCount = playerCount;
        loadFromList(initialRoles);
    }

    // ── Inisialisasi ────────────────────────────────────────────────────────

    /**
     * Isi goodSlots / evilSlots dari list role (urutan good dulu, lalu evil).
     * Good diisi dari index 0 ke atas; evil dari index 0 ke atas.
     */
    private void loadFromList(List<Role> roles) {
        int gi = 0, ei = 0;
        for (Role r : roles) {
            if (r == null) continue;
            if (r.isGood()) {
                if (gi < GOOD_SLOT_COUNT) goodSlots[gi++] = r;
            } else {
                if (ei < EVIL_SLOT_COUNT) evilSlots[ei++] = r;
            }
        }
    }

    // ── Getter dasar ─────────────────────────────────────────────────────────

    public int getPlayerCount() { return playerCount; }

    public Role getGoodSlot(int index) { return goodSlots[index]; }
    public Role getEvilSlot(int index) { return evilSlots[index]; }

    /** Berapa banyak good slot yang terpakai (tidak null). */
    public int getGoodCount() {
        int c = 0;
        for (Role r : goodSlots) if (r != null) c++;
        return c;
    }

    /** Berapa banyak evil slot yang terpakai (tidak null). */
    public int getEvilCount() {
        int c = 0;
        for (Role r : evilSlots) if (r != null) c++;
        return c;
    }

    // ── Pending slot ─────────────────────────────────────────────────────────

    /** Tandai good slot [index] sebagai yang sedang menunggu diisi. */
    public void selectGoodSlot(int index) { pendingSlot = "good:" + index; }

    /** Tandai evil slot [index] sebagai yang sedang menunggu diisi. */
    public void selectEvilSlot(int index) { pendingSlot = "evil:" + index; }

    public void clearPendingSlot() { pendingSlot = null; }

    public boolean hasPendingSlot() { return pendingSlot != null; }

    public boolean isPendingGood() {
        return pendingSlot != null && pendingSlot.startsWith("good:");
    }

    public int getPendingIndex() {
        if (pendingSlot == null) return -1;
        return Integer.parseInt(pendingSlot.split(":")[1]);
    }

    // ── Operasi slot ─────────────────────────────────────────────────────────

    /**
     * Hapus good slot [index] → jadi null.
     * Posisi lain TIDAK bergeser.
     */
    public void removeGoodSlot(int index) { goodSlots[index] = null; }

    /** Hapus evil slot [index] → jadi null. */
    public void removeEvilSlot(int index) { evilSlots[index] = null; }

    /**
     * Isi pending slot dengan role dari pool.
     * Otomatis clear pendingSlot setelah diisi.
     */
    public void fillPendingSlot(Role role) {
        if (pendingSlot == null) return;
        int idx = getPendingIndex();
        if (isPendingGood()) {
            goodSlots[idx] = role;
        } else {
            evilSlots[idx] = role;
        }
        pendingSlot = null;
    }

    // ── Validasi & konversi ──────────────────────────────────────────────────

    /** True jika semua slot aktif (good + evil) sudah terisi. */
    public boolean isComplete() {
        int neededGood = getNeededGoodCount();
        int neededEvil = getNeededEvilCount();

        for (int i = 0; i < neededGood; i++) {
            if (goodSlots[i] == null) return false;
        }
        for (int i = 0; i < neededEvil; i++) {
            if (evilSlots[i] == null) return false;
        }
        return true;
    }

    /**
     * Berapa good slot yang dipakai sesuai playerCount.
     * Sama dengan panjang good roles di default config.
     */
    public int getNeededGoodCount() {
        return switch (playerCount) {
            case 5  -> 3;
            case 6  -> 4;
            case 7  -> 4;
            case 8  -> 5;
            case 9  -> 6;
            case 10 -> 6;
            default -> 3;
        };
    }

    public int getNeededEvilCount() {
        return playerCount - getNeededGoodCount();
    }

    /**
     * Kumpulkan semua role (good lalu evil) ke List untuk disimpan ke GameManager.
     */
    public List<Role> toRoleList() {
        List<Role> list = new ArrayList<>();
        int ng = getNeededGoodCount();
        int ne = getNeededEvilCount();
        for (int i = 0; i < ng; i++) list.add(goodSlots[i]);
        for (int i = 0; i < ne; i++) list.add(evilSlots[i]);
        return list;
    }

    // ── Pool builder (single source of truth) ───────────────────────────────

    /**
     * Bangun good pool: role yang tersedia untuk dipilih dari pool.
     * Merlin & Percival hanya muncul kalau belum aktif.
     * LOYAL_SERVANT selalu tersedia.
     */
    public List<Role> buildGoodPool() {
        List<Role> pool = new ArrayList<>();
        if (!isUniqueRoleActive(Role.MERLIN))   pool.add(Role.MERLIN);
        if (!isUniqueRoleActive(Role.PERCIVAL)) pool.add(Role.PERCIVAL);
        pool.add(Role.LOYAL_SERVANT);
        return pool;
    }

    /**
     * Bangun evil pool: role yang tersedia untuk dipilih dari pool.
     * Role unik hanya muncul kalau belum aktif.
     * MINION_OF_MORDRED selalu tersedia.
     */
    public List<Role> buildEvilPool() {
        List<Role> pool = new ArrayList<>();
        if (!isUniqueRoleActive(Role.ASSASSIN))       pool.add(Role.ASSASSIN);
        if (!isUniqueRoleActive(Role.MORGANA))         pool.add(Role.MORGANA);
        if (!isUniqueRoleActive(Role.MORDRED))         pool.add(Role.MORDRED);
        if (!isUniqueRoleActive(Role.OBERON))          pool.add(Role.OBERON);
        pool.add(Role.MINION_OF_MORDRED);
        return pool;
    }

    // ── Cek duplikat role unik ───────────────────────────────────────────────

    /** Apakah role unik ini sudah ada di slot aktif mana pun. */
    public boolean isUniqueRoleActive(Role role) {
        if (!isUnique(role)) return false;
        for (Role r : goodSlots) if (r == role) return true;
        for (Role r : evilSlots) if (r == role) return true;
        return false;
    }

    public static boolean isUnique(Role role) {
        return switch (role) {
            case LOYAL_SERVANT, MINION_OF_MORDRED -> false;
            default -> true;
        };
    }
}

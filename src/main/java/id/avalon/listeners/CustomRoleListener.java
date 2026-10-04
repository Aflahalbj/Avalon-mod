package id.avalon.listeners;

import id.avalon.core.Fx;
import id.avalon.core.Txt;
import id.avalon.gui.AvalonMenu;
import id.avalon.gui.CustomRoleGUI;
import id.avalon.gui.RoleEditorSession;
import id.avalon.managers.GameManager;
import id.avalon.models.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ClickType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CustomRoleListener implements AvalonMenu.ClickHandler {

    private final GameManager gameManager;
    private final CustomRoleGUI gui;

    // Session per player
    private static final Map<UUID, RoleEditorSession> sessions = new HashMap<>();

    public CustomRoleListener(GameManager gameManager) {
        this.gameManager = gameManager;
        this.gui = new CustomRoleGUI(gameManager);
    }

    public static Map<UUID, RoleEditorSession> getSessions() {
        return sessions;
    }

    /** Buka GUI custom role untuk player (dipanggil dari /customrole). */
    public void open(ServerPlayer player, RoleEditorSession session) {
        sessions.put(player.getUUID(), session);
        SimpleContainer inv = gui.create(session);
        AvalonMenu.open(player, CustomRoleGUI.title(session), inv, this);
    }

    // ── Slot set untuk deteksi cepat ─────────────────────────────────────────

    private static final Set<Integer> GOOD_ACTIVE_SET = toSet(CustomRoleGUI.GOOD_ACTIVE);
    private static final Set<Integer> EVIL_ACTIVE_SET = toSet(CustomRoleGUI.EVIL_ACTIVE);
    private static final Set<Integer> GOOD_POOL_SET   = toSet(CustomRoleGUI.GOOD_POOL);
    private static final Set<Integer> EVIL_POOL_SET   = toSet(CustomRoleGUI.EVIL_POOL);

    private static Set<Integer> toSet(int[] arr) {
        Set<Integer> s = new HashSet<>();
        for (int v : arr) s.add(v);
        return s;
    }

    // ── Click handler (semua klik sudah di-cancel oleh AvalonMenu) ────────────

    @Override
    public void onClick(AvalonMenu menu, ServerPlayer player, int slot, ClickType clickType, int button) {

        // Klik di luar GUI 54 slot atau slot kosong diabaikan
        if (slot < 0 || slot >= AvalonMenu.SIZE) return;
        if (menu.inv().getItem(slot).isEmpty()) return;

        RoleEditorSession session = sessions.get(player.getUUID());
        if (session == null) return;

        // ── SAVE ────────────────────────────────────────────────────────────
        if (slot == CustomRoleGUI.SAVE_SLOT) {
            handleSave(player, session);
            return;
        }

        // ── GOOD ACTIVE ──────────────────────────────────────────────────────
        if (GOOD_ACTIVE_SET.contains(slot)) {
            handleGoodActiveClick(menu, player, session, slot);
            return;
        }

        // ── EVIL ACTIVE ──────────────────────────────────────────────────────
        if (EVIL_ACTIVE_SET.contains(slot)) {
            handleEvilActiveClick(menu, player, session, slot);
            return;
        }

        // ── GOOD POOL ────────────────────────────────────────────────────────
        if (GOOD_POOL_SET.contains(slot)) {
            handlePoolClick(menu, player, session, slot, true);
            return;
        }

        // ── EVIL POOL ────────────────────────────────────────────────────────
        if (EVIL_POOL_SET.contains(slot)) {
            handlePoolClick(menu, player, session, slot, false);
        }
    }

    // ── Handler: klik good active ─────────────────────────────────────────────

    private void handleGoodActiveClick(AvalonMenu menu, ServerPlayer player, RoleEditorSession session, int slot) {

        int index = slotIndex(CustomRoleGUI.GOOD_ACTIVE, slot);
        if (index < 0 || index >= session.getNeededGoodCount()) return;

        Role role = session.getGoodSlot(index);

        // Slot kosong (question mark) → tandai sebagai pending
        if (role == null) {
            session.selectGoodSlot(index);
            Fx.sound(player, SoundEvents.UI_BUTTON_CLICK, 1.0f, 1.0f);
            gui.render(menu.inv(), session);
            return;
        }

        // Merlin tidak bisa dihapus
        if (role == Role.MERLIN) {
            player.sendSystemMessage(Txt.t("Merlin tidak bisa dihapus.", ChatFormatting.RED));
            Fx.sound(player, SoundEvents.VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        // Hapus role, jadikan null (posisi slot tidak bergeser)
        session.removeGoodSlot(index);
        session.clearPendingSlot();
        Fx.sound(player, SoundEvents.NOTE_BLOCK_BELL, 1.0f, 1.0f);
        gui.render(menu.inv(), session);
    }

    // ── Handler: klik evil active ─────────────────────────────────────────────

    private void handleEvilActiveClick(AvalonMenu menu, ServerPlayer player, RoleEditorSession session, int slot) {

        int index = slotIndex(CustomRoleGUI.EVIL_ACTIVE, slot);
        if (index < 0 || index >= session.getNeededEvilCount()) return;

        Role role = session.getEvilSlot(index);

        // Slot kosong → tandai sebagai pending
        if (role == null) {
            session.selectEvilSlot(index);
            Fx.sound(player, SoundEvents.UI_BUTTON_CLICK, 1.0f, 1.0f);
            gui.render(menu.inv(), session);
            return;
        }

        // Hapus role
        session.removeEvilSlot(index);
        session.clearPendingSlot();
        Fx.sound(player, SoundEvents.NOTE_BLOCK_BELL, 1.0f, 1.0f);
        gui.render(menu.inv(), session);
    }

    // ── Handler: klik pool ────────────────────────────────────────────────────

    private void handlePoolClick(AvalonMenu menu, ServerPlayer player, RoleEditorSession session, int slot, boolean isGoodPool) {

        // Harus ada pending slot dulu
        if (!session.hasPendingSlot()) return;

        // Pastikan pool yang diklik sesuai kubu pending
        if (isGoodPool != session.isPendingGood()) return;

        // Identifikasi role dari posisi di pool — pakai session sebagai single source of truth
        Role role = resolvePoolRole(session, slot, isGoodPool);
        if (role == null) return;

        // Cek: role unik yang sudah aktif tidak bisa dipilih lagi
        if (RoleEditorSession.isUnique(role) && session.isUniqueRoleActive(role)) return;

        // Isi pending slot
        session.fillPendingSlot(role);
        Fx.sound(player, SoundEvents.NOTE_BLOCK_PLING, 1.0f, 1.0f);
        gui.render(menu.inv(), session);
    }

    // ── Handler: save ────────────────────────────────────────────────────────

    private void handleSave(ServerPlayer player, RoleEditorSession session) {

        if (!session.isComplete()) {
            Fx.sound(player, SoundEvents.VILLAGER_NO, 1.0f, 1.0f);
            player.sendSystemMessage(Txt.t("Masih ada role kosong.", ChatFormatting.RED));
            return;
        }

        gameManager.setCustomRoles(session.getPlayerCount(), session.toRoleList());
        Fx.sound(player, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f);
        player.sendSystemMessage(Txt.t("Custom role berhasil disimpan.", ChatFormatting.GREEN));
        sessions.remove(player.getUUID());
        player.closeContainer();
    }

    // ── Util: cari role dari slot pool ────────────────────────────────────────

    /**
     * Tentukan Role berdasarkan slot yang diklik di pool.
     * Menggunakan session.buildGoodPool() / buildEvilPool() sebagai single source of truth.
     */
    private Role resolvePoolRole(RoleEditorSession session, int slot, boolean isGood) {

        if (isGood) {
            List<Role> pool = session.buildGoodPool();
            int poolSlotIndex = slotIndex(CustomRoleGUI.GOOD_POOL, slot);
            if (poolSlotIndex < 0 || poolSlotIndex >= pool.size()) return null;
            return pool.get(poolSlotIndex);

        } else {
            List<Role> pool = session.buildEvilPool();
            // Evil pool rata kanan: evilStart = EVIL_POOL.length - pool.size()
            int evilStart = CustomRoleGUI.EVIL_POOL.length - pool.size();
            int poolSlotIndex = slotIndex(CustomRoleGUI.EVIL_POOL, slot) - evilStart;
            if (poolSlotIndex < 0 || poolSlotIndex >= pool.size()) return null;
            return pool.get(poolSlotIndex);
        }
    }

    // ── Util ─────────────────────────────────────────────────────────────────

    /** Cari index dari nilai dalam array; -1 kalau tidak ketemu. */
    private int slotIndex(int[] arr, int value) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == value) return i;
        }
        return -1;
    }
}

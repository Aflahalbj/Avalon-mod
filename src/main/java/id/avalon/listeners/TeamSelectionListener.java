package id.avalon.listeners;

import id.avalon.core.Fx;
import id.avalon.core.Task;
import id.avalon.core.Txt;
import id.avalon.gui.AvalonMenu;
import id.avalon.gui.TeamSelectionGUI;
import id.avalon.managers.GameManager;
import id.avalon.world.AvalonSeats;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Menangani interaksi klik di dalam GUI pemilihan tim.
 *
 * Logika:
 *  - Klik di TARGET_SLOTS (berisi player head): kembalikan ke pool rata kiri
 *  - Klik di POOL_SLOTS (berisi player head): pindahkan ke target slot ? pertama
 *  - Klik di CONFIRM_SLOT: konfirmasi jika semua slot terisi
 *  - Lainnya (divider, question mark, null): diabaikan
 *
 * Juga menampilkan kepala anggota tim yang dipilih melayang di depan Raja.
 */
public class TeamSelectionListener implements AvalonMenu.ClickHandler {

    private final GameManager gameManager;
    private final Map<UUID, List<ArmorStand>> floatingHeads = new HashMap<>();
    private final Map<ArmorStand, Vec3> headBaseLocations = new HashMap<>();
    private final Map<ArmorStand, Double> headPhases = new HashMap<>();
    private Task animationTask;

    public TeamSelectionListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    /** Mulai animasi kepala melayang (dipanggil saat server start). */
    public void startAnimation() {
        if (animationTask != null) animationTask.cancel();

        animationTask = new Task() {

            double tick = 0;

            @Override
            public void run() {

                tick += 0.25;

                for (Map.Entry<ArmorStand, Vec3> entry : new ArrayList<>(headBaseLocations.entrySet())) {

                    ArmorStand stand = entry.getKey();

                    if (stand == null || !stand.isAlive())
                        continue;

                    Vec3 base = entry.getValue();

                    double phase = headPhases.getOrDefault(stand, 0.0);

                    double offsetY = Math.sin(tick + phase) * 0.03;

                    float yaw = Mth.wrapDegrees(stand.getYRot() + 1.5f);

                    GameManager.moveStand(stand, (ServerLevel) stand.level(),
                            base.x, base.y + offsetY, base.z, yaw);
                }
            }

        }.runTimer(0L, 1L);
    }

    /** Buka GUI pemilihan tim untuk Raja (dipanggil TeamBookListener). */
    public void open(ServerPlayer king, int teamSize, List<String> available, List<String> alreadyPicked) {
        SimpleContainer inv = new TeamSelectionGUI().create(teamSize, available, alreadyPicked);
        AvalonMenu.open(king, TeamSelectionGUI.title(), inv, this);
    }

    @Override
    public void onClick(AvalonMenu menu, ServerPlayer player, int slot, ClickType clickType, int button) {

        Container inv = menu.inv();

        // Abaikan klik di luar 54 slot GUI (mis. inventory pemain di bawah)
        if (slot < 0 || slot >= AvalonMenu.SIZE) return;

        ItemStack clicked = inv.getItem(slot);

        // Abaikan slot kosong dan divider
        if (clicked.isEmpty()) return;
        if (TeamSelectionGUI.isDivider(clicked)) return;

        // Abaikan question mark (klik di slot kosong target)
        if (TeamSelectionGUI.isQuestionMark(clicked)) return;

        // Hitung teamSize dari target slot yang aktif (berisi item apapun)
        int teamSize = countActiveTargetSlots(inv);

        // ── Klik CONFIRM ────────────────────────────────────────────────────────
        if (slot == TeamSelectionGUI.CONFIRM_SLOT) {
            if (!TeamSelectionGUI.isConfirmButton(clicked)) return;

            int emptySlot = findFirstQuestionMarkSlot(inv, teamSize);
            if (emptySlot != -1) {
                player.sendSystemMessage(Txt.t("Isi semua slot terlebih dahulu!", ChatFormatting.RED));
                Fx.sound(player, SoundEvents.VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            List<String> selectedTeam = collectSelectedPlayers(inv, teamSize);
            if (selectedTeam.size() < teamSize) {
                player.sendSystemMessage(Txt.t("Error: slot tidak valid!", ChatFormatting.RED));
                return;
            }

            // Ada yang keluar (atau masuk) sejak dipilih: jatah player offline dihitung ulang
            if (gameManager.countOffline(selectedTeam) > gameManager.offlinePicksAllowed()) {
                player.sendSystemMessage(Txt.t("Tim berisi player yang sedang offline. Ganti dengan yang online.", ChatFormatting.RED));
                Fx.sound(player, SoundEvents.VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            player.closeContainer();
            gameManager.confirmTeamSelection(player, selectedTeam);
            return;
        }

        // ── Klik TARGET SLOT (baris 1) yang berisi player head ─────────────────
        if (isInTargetRange(slot, teamSize)) {
            String playerName = TeamSelectionGUI.getPlayerNameFromItem(clicked);
            if (playerName == null) return;

            // Reset slot ini ke question mark
            TeamSelectionGUI gui = new TeamSelectionGUI();
            inv.setItem(slot, gui.makeQuestionMark());

            // Kembalikan player ke pool (rata kiri)
            addBackToPool(inv, playerName, gui);

            // Update confirm button
            updateConfirmButton(inv, teamSize, gui);
            syncSession(player, inv, teamSize);
            updateFloatingHeads(player, inv, teamSize);
            Fx.sound(player, SoundEvents.NOTE_BLOCK_BELL, 1.0f, 1.0f);
            return;
        }

        // ── Klik POOL SLOT (baris 4-5) yang berisi player head ─────────────────
        if (isPoolSlot(slot)) {
            String playerName = TeamSelectionGUI.getPlayerNameFromItem(clicked);
            if (playerName == null) return;

            // Cari question mark pertama di target slots
            int targetSlot = findFirstQuestionMarkSlot(inv, teamSize);
            if (targetSlot == -1) {
                player.sendSystemMessage(Txt.t("Semua slot sudah terisi!", ChatFormatting.RED));
                Fx.sound(player, SoundEvents.VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            // Player offline hanya untuk menutup kekurangan: sebanyak slot yang tidak bisa diisi yang online
            if (gameManager.getPlayerExact(playerName) == null
                    && gameManager.countOffline(collectSelectedPlayers(inv, teamSize)) >= gameManager.offlinePicksAllowed()) {
                player.sendSystemMessage(Txt.t(playerName + " sedang offline. Pilih player yang online dulu.", ChatFormatting.RED));
                Fx.sound(player, SoundEvents.VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            TeamSelectionGUI gui = new TeamSelectionGUI();

            // Pindahkan ke target slot
            inv.setItem(targetSlot, gui.makePlayerHead(playerName));

            // Hapus dari pool dan geser rata kiri
            removeFromPoolAndShift(inv, slot, gui);

            // Update confirm button
            updateConfirmButton(inv, teamSize, gui);
            syncSession(player, inv, teamSize);
            updateFloatingHeads(player, inv, teamSize);
            Fx.sound(player, SoundEvents.UI_BUTTON_CLICK, 1.0f, 1.0f);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Hitung berapa target slot aktif (slot yang berisi item — baik player head
     * maupun question mark). Slot aktif selalu berurutan dari kiri (index 0..n-1).
     */
    private int countActiveTargetSlots(Container inv) {
        int count = 0;
        for (int s : TeamSelectionGUI.TARGET_SLOTS) {
            ItemStack item = inv.getItem(s);
            if (!item.isEmpty()) count++;
            else break;
        }
        return Math.max(count, 1);
    }

    /** Cek apakah slot ada di dalam target range yang aktif. */
    private boolean isInTargetRange(int slot, int teamSize) {
        for (int i = 0; i < teamSize; i++) {
            if (TeamSelectionGUI.TARGET_SLOTS[i] == slot) return true;
        }
        return false;
    }

    /** Cek apakah slot adalah pool slot. */
    private boolean isPoolSlot(int slot) {
        for (int s : TeamSelectionGUI.POOL_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    /**
     * Cari slot target pertama yang berisi question mark.
     * Return -1 jika semua terisi player head.
     */
    private int findFirstQuestionMarkSlot(Container inv, int teamSize) {
        for (int i = 0; i < teamSize; i++) {
            int s = TeamSelectionGUI.TARGET_SLOTS[i];
            ItemStack item = inv.getItem(s);
            if (item.isEmpty() || TeamSelectionGUI.isQuestionMark(item)) {
                return s;
            }
        }
        return -1;
    }

    /**
     * Kembalikan playerName ke pool — tambahkan di akhir, render rata kiri.
     */
    private void addBackToPool(Container inv, String playerName, TeamSelectionGUI gui) {
        List<String> current = readPool(inv);
        current.add(playerName);
        renderPool(inv, current, gui);
    }

    /**
     * Hapus item di removedSlot dari pool, geser sisa ke kiri.
     */
    private void removeFromPoolAndShift(Container inv, int removedSlot, TeamSelectionGUI gui) {
        List<String> remaining = new ArrayList<>();
        for (int s : TeamSelectionGUI.POOL_SLOTS) {
            if (s == removedSlot) continue;
            String name = TeamSelectionGUI.getPlayerNameFromItem(inv.getItem(s));
            if (name != null) remaining.add(name);
        }
        renderPool(inv, remaining, gui);
    }

    /** Baca semua nama player di pool (dari kiri ke kanan, skip null). */
    private List<String> readPool(Container inv) {
        List<String> names = new ArrayList<>();
        for (int s : TeamSelectionGUI.POOL_SLOTS) {
            String name = TeamSelectionGUI.getPlayerNameFromItem(inv.getItem(s));
            if (name != null) names.add(name);
        }
        return names;
    }

    /** Hapus semua pool slot lalu render ulang daftar nama rata kiri. */
    private void renderPool(Container inv, List<String> names, TeamSelectionGUI gui) {
        for (int s : TeamSelectionGUI.POOL_SLOTS) inv.setItem(s, ItemStack.EMPTY);
        for (int i = 0; i < names.size() && i < TeamSelectionGUI.POOL_SLOTS.length; i++) {
            inv.setItem(TeamSelectionGUI.POOL_SLOTS[i], gui.makePlayerHead(names.get(i)));
        }
    }

    /** Update tombol konfirmasi berdasarkan apakah semua slot terisi. */
    private void updateConfirmButton(Container inv, int teamSize, TeamSelectionGUI gui) {
        boolean allFilled = (findFirstQuestionMarkSlot(inv, teamSize) == -1);
        inv.setItem(TeamSelectionGUI.CONFIRM_SLOT, gui.makeConfirmButton(allFilled));
    }

    /** Kumpulkan nama player dari target slots yang terisi. */
    private List<String> collectSelectedPlayers(Container inv, int teamSize) {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < teamSize; i++) {
            String name = TeamSelectionGUI.getPlayerNameFromItem(
                inv.getItem(TeamSelectionGUI.TARGET_SLOTS[i]));
            if (name != null) selected.add(name);
        }
        return selected;
    }

    /** Sinkronkan state target slots ke session GameManager. */
    private void syncSession(ServerPlayer player, Container inv, int teamSize) {
        List<String> selected = collectSelectedPlayers(inv, teamSize);
        gameManager.setTeamSelectionSession(player, selected);
    }

    private void updateFloatingHeads(ServerPlayer king, Container inv, int teamSize) {

        List<ArmorStand> old = floatingHeads.remove(king.getUUID());

        if (old != null) {
            for (ArmorStand stand : old) {
                headBaseLocations.remove(stand);
                headPhases.remove(stand);
                stand.discard();
            }
        }

        List<String> selected = collectSelectedPlayers(inv, teamSize);

        if (selected.isEmpty())
            return;

        ServerLevel world = king.serverLevel();

        // Di atas mahkota raja
        Vec3 center = king.position().add(0, GameManager.KING_HEAD_HEIGHT, 0);

        // Tengah lingkaran kursi
        Vec3 base = new Vec3(
                AvalonSeats.CENTER.getX() + 0.5,
                AvalonSeats.CENTER.getY(),
                AvalonSeats.CENTER.getZ() + 0.5
        );

        Vec3 forward = new Vec3(base.x - king.getX(), 0, base.z - king.getZ());
        if (forward.lengthSqr() < 1.0e-6) forward = new Vec3(0, 0, 1);
        forward = forward.normalize();

        Vec3 right = new Vec3(
                -forward.z,
                0,
                forward.x
        );

        // Yaw dari arah forward (setara Location#setDirection)
        float standYaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));

        double spacing = 0.75;

        List<ArmorStand> spawned = new ArrayList<>();

        double start = -(selected.size() - 1) / 2.0;

        for (int i = 0; i < selected.size(); i++) {

            String playerName = selected.get(i);

            Vec3 pos = center.add(right.scale((start + i) * spacing));

            ArmorStand stand = GameManager.spawnStand(world, pos.x, pos.y, pos.z, standYaw, true, true, false);
            stand.addTag("avalon_team_head");

            ItemStack skull = new TeamSelectionGUI().makePlayerHead(playerName);

            stand.setItemSlot(EquipmentSlot.HEAD, skull);

            spawned.add(stand);

            headBaseLocations.put(stand, stand.position());

            double phase = (i % 2 == 0)
                    ? 0
                    : Math.PI;

            headPhases.put(stand, phase);
        }

        floatingHeads.put(
                king.getUUID(),
                spawned
        );
    }

    public void clearAllFloatingHeads() {

        for (List<ArmorStand> stands : floatingHeads.values()) {

            for (ArmorStand stand : stands) {

                if (stand != null && stand.isAlive()) {
                    stand.discard();
                }
            }
        }

        floatingHeads.clear();
        headBaseLocations.clear();
        headPhases.clear();
    }
}

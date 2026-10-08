package id.avalon.gui;

import id.avalon.core.AvalonItems;
import id.avalon.core.Txt;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;

/**
 * GUI untuk Raja memilih anggota tim yang akan menjalankan misi.
 *
 * Layout (54 slot):
 *   Baris 1 (slot 0-8):   Target slots (aktif sesuai kuota misi)
 *   Baris 2 (slot 9-17):  kosong
 *   Baris 3 (slot 18-26): black stained glass pane (divider)
 *   Baris 4 (slot 27-35): daftar player tersedia (rata kiri)
 *   Baris 5 (slot 36-44): daftar player tersedia (lanjutan, rata kiri)
 *   Slot 53:              tombol konfirmasi (checkmark)
 */
public class TeamSelectionGUI {

    // ── Texture URLs ───────────────────────────────────────────────────────────
    private static final String TEXTURE_QUESTION_MARK =
        "http://textures.minecraft.net/texture/6958a4a7a53d343bf672215a49fdc9d7cc444f65166d162cd60872eb58710";
    private static final String TEXTURE_CHECKMARK =
        "http://textures.minecraft.net/texture/d9980c1d211809a9b6565088f56a38f2ef49115c1054fa66245122e9eeedecc2";

    // ── GUI Title ──────────────────────────────────────────────────────────────
    public static final String GUI_TITLE = "Pilih Tim Misi";

    // ── Slot ranges ────────────────────────────────────────────────────────────
    public static final int[] TARGET_SLOTS  = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    public static final int[] DIVIDER_SLOTS = {18, 19, 20, 21, 22, 23, 24, 25, 26};
    public static final int[] POOL_SLOTS    = {27, 28, 29, 30, 31, 32, 33, 34, 35,
                                                36, 37, 38, 39, 40, 41, 42, 43};
    public static final int   CONFIRM_SLOT  = 53;

    // ── Item tag keys (pengganti PersistentDataContainer) ─────────────────────
    public  static final String PDC_KEY_TYPE  = "gui_slot_type";
    public  static final String PDC_TYPE_QUESTION = "question_mark";
    public  static final String PDC_TYPE_CONFIRM  = "confirm_button";
    public  static final String PDC_TYPE_DIVIDER  = "divider";
    public  static final String PDC_KEY_PLAYER_NAME = "player_name";

    // ── Tabel komposisi tim ────────────────────────────────────────────────────
    private static final int[][] MISSION_TEAM_SIZE = {
        // 5P:  M1 M2 M3 M4 M5
        {2, 3, 2, 3, 3},
        // 6P:
        {2, 3, 4, 3, 4},
        // 7P:
        {2, 3, 3, 4, 4},
        // 8P:
        {3, 4, 4, 5, 5},
        // 9P:
        {3, 4, 4, 5, 5},
        // 10P:
        {3, 4, 4, 5, 5},
    };

    public TeamSelectionGUI() {
    }

    public static Component title() {
        return Txt.t(GUI_TITLE, ChatFormatting.DARK_AQUA, ChatFormatting.BOLD);
    }

    // ── Entry point ────────────────────────────────────────────────────────────

    public SimpleContainer create(int teamSize, List<String> availablePlayers, List<String> selectedPlayers) {
        SimpleContainer inv = AvalonMenu.newContainer();

        // Divider baris 3
        for (int slot : DIVIDER_SLOTS) inv.setItem(slot, makeDivider());

        // Target slots baris 1
        for (int i = 0; i < TARGET_SLOTS.length; i++) {
            if (i < teamSize) {
                if (i < selectedPlayers.size()) {
                    inv.setItem(TARGET_SLOTS[i], makePlayerHead(selectedPlayers.get(i)));
                } else {
                    inv.setItem(TARGET_SLOTS[i], makeQuestionMark());
                }
            }
        }

        // Pool player baris 4-5 (rata kiri)
        for (int i = 0; i < availablePlayers.size() && i < POOL_SLOTS.length; i++) {
            inv.setItem(POOL_SLOTS[i], makePlayerHead(availablePlayers.get(i)));
        }

        // Tombol konfirmasi
        boolean allFilled = (selectedPlayers.size() >= teamSize);
        inv.setItem(CONFIRM_SLOT, makeConfirmButton(allFilled));

        return inv;
    }

    // ── Item builders ──────────────────────────────────────────────────────────

    private ItemStack makeDivider() {
        ItemStack item = AvalonItems.named(Items.BLACK_STAINED_GLASS_PANE, Component.empty(), null);
        AvalonItems.setTag(item, PDC_KEY_TYPE, PDC_TYPE_DIVIDER);
        return item;
    }

    /** Question mark skull dengan texture kustom. Tag menandai sebagai question mark. */
    public ItemStack makeQuestionMark() {
        ItemStack skull = AvalonItems.texturedHead(
            TEXTURE_QUESTION_MARK,
            Txt.t("[ Slot Kosong ]", ChatFormatting.YELLOW, ChatFormatting.BOLD),
            List.of(Txt.t("Pilih player dari bawah", ChatFormatting.GRAY))
        );
        AvalonItems.setTag(skull, PDC_KEY_TYPE, PDC_TYPE_QUESTION);
        return skull;
    }

    /**
     * Kepala player — nama disimpan di tag dan di display name. Player yang sedang offline diberi
     * tanda: ia hanya bisa dipilih kalau player yang online tidak cukup (lihat TeamSelectionListener).
     */
    public ItemStack makePlayerHead(String playerName) {
        ItemStack skull = AvalonItems.playerHead(playerName);
        if (isOffline(playerName)) {
            AvalonItems.setName(skull, Txt.t(playerName, ChatFormatting.GRAY, ChatFormatting.BOLD)
                .append(Txt.t(" (OFFLINE)", ChatFormatting.RED)));
            AvalonItems.setLore(skull, List.of(
                Txt.t("Sedang offline.", ChatFormatting.RED),
                Txt.t("Hanya bisa dipilih kalau player yang", ChatFormatting.GRAY),
                Txt.t("online tidak cukup untuk mengisi tim.", ChatFormatting.GRAY)
            ));
        } else {
            AvalonItems.setName(skull, Txt.t(playerName, ChatFormatting.AQUA, ChatFormatting.BOLD));
            AvalonItems.setLore(skull, List.of(
                Txt.t("Klik untuk memilih / mengembalikan", ChatFormatting.GRAY)
            ));
        }
        AvalonItems.setTag(skull, PDC_KEY_PLAYER_NAME, playerName);
        return skull;
    }

    private static boolean isOffline(String playerName) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.getPlayerList().getPlayerByName(playerName) == null;
    }

    /** Checkmark skull dengan texture kustom. */
    public ItemStack makeConfirmButton(boolean ready) {
        Component displayName = ready
            ? Txt.t("✔ KONFIRMASI TIM", ChatFormatting.GREEN, ChatFormatting.BOLD)
            : Txt.t("✔ KONFIRMASI TIM", ChatFormatting.DARK_GRAY, ChatFormatting.BOLD);

        List<Component> lore = ready
            ? List.of(
                Txt.t("Apakah kamu yakin? ini tidak bisa diubah!", ChatFormatting.YELLOW),
                Txt.t("Klik untuk mengonfirmasi tim.", ChatFormatting.WHITE))
            : List.of(
                Txt.t("Isi semua slot terlebih dahulu.", ChatFormatting.RED));

        ItemStack skull = AvalonItems.texturedHead(TEXTURE_CHECKMARK, displayName, lore);
        AvalonItems.setTag(skull, PDC_KEY_TYPE, PDC_TYPE_CONFIRM);
        return skull;
    }

    // ── Static utility ─────────────────────────────────────────────────────────

    /** Ambil nama player dari item head (dari tag player_name). */
    public static String getPlayerNameFromItem(ItemStack item) {
        if (item == null || item.isEmpty() || !item.is(Items.PLAYER_HEAD)) return null;
        if (isQuestionMark(item)) return null;
        String name = AvalonItems.getTag(item, PDC_KEY_PLAYER_NAME);
        if (name != null) return name;

        // Fallback: baca dari display name
        if (!item.hasCustomHoverName()) return null;
        String plain = item.getHoverName().getString().trim();
        if (plain.isEmpty()) return null;
        return plain;
    }

    /** Cek apakah item adalah question mark. */
    public static boolean isQuestionMark(ItemStack item) {
        if (item == null || item.isEmpty() || !item.is(Items.PLAYER_HEAD)) return false;
        return PDC_TYPE_QUESTION.equals(AvalonItems.getTag(item, PDC_KEY_TYPE));
    }

    /** Cek apakah item adalah confirm button. */
    public static boolean isConfirmButton(ItemStack item) {
        if (item == null || item.isEmpty() || !item.is(Items.PLAYER_HEAD)) return false;
        return PDC_TYPE_CONFIRM.equals(AvalonItems.getTag(item, PDC_KEY_TYPE));
    }

    /** Cek apakah item adalah divider. */
    public static boolean isDivider(ItemStack item) {
        return item != null && item.is(Items.BLACK_STAINED_GLASS_PANE);
    }

    // ── Tabel komposisi ────────────────────────────────────────────────────────

    public static int getTeamSize(int playerCount, int missionNumber) {
        if (playerCount < 5 || playerCount > 10) return 2;
        if (missionNumber < 1 || missionNumber > 5) return 2;
        return MISSION_TEAM_SIZE[playerCount - 5][missionNumber - 1];
    }

    public static boolean requiresTwoFails(int playerCount, int missionNumber) {
        return missionNumber == 4 && playerCount >= 7;
    }
}

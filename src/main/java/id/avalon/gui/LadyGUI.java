package id.avalon.gui;

import id.avalon.core.AvalonItems;
import id.avalon.core.Txt;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;

/**
 * GUI untuk pemegang Lady of the Lake memilih satu pemain yang kesetiaannya diperiksa.
 *
 * Layout (54 slot):
 *   Slot 4:               keterangan
 *   Slot 13:              pemain yang dipilih (question mark kalau belum)
 *   Baris 3 (slot 18-26): black stained glass pane (divider)
 *   Baris 4-5:            pemain yang bisa diperiksa, lalu yang pernah memegang Lady (tidak bisa)
 *   Slot 53:              tombol konfirmasi (checkmark)
 */
public class LadyGUI {

    private static final String TEXTURE_QUESTION_MARK =
        "http://textures.minecraft.net/texture/6958a4a7a53d343bf672215a49fdc9d7cc444f65166d162cd60872eb58710";
    private static final String TEXTURE_CHECKMARK =
        "http://textures.minecraft.net/texture/d9980c1d211809a9b6565088f56a38f2ef49115c1054fa66245122e9eeedecc2";

    public static final String GUI_TITLE = "Lady of the Lake";

    public static final int   INFO_SLOT     = 4;
    public static final int   TARGET_SLOT   = 13;
    public static final int[] DIVIDER_SLOTS = {18, 19, 20, 21, 22, 23, 24, 25, 26};
    public static final int[] POOL_SLOTS    = {27, 28, 29, 30, 31, 32, 33, 34, 35,
                                                36, 37, 38, 39, 40, 41, 42, 43};
    public static final int   CONFIRM_SLOT  = 53;

    /** Tag di kepala pemain: nama pemain yang bisa diperiksa. */
    public static final String KEY_CANDIDATE = "lady_candidate";

    public static Component title() {
        return Txt.t(GUI_TITLE, ChatFormatting.DARK_AQUA, ChatFormatting.BOLD);
    }

    public SimpleContainer create(List<String> candidates, List<String> pastHolders, String selected) {
        SimpleContainer inv = AvalonMenu.newContainer();
        render(inv, candidates, pastHolders, selected);
        return inv;
    }

    /** Render ulang isi GUI; {@code selected} = pemain yang sedang dipilih, atau null. */
    public void render(Container inv, List<String> candidates, List<String> pastHolders, String selected) {
        inv.clearContent();

        inv.setItem(INFO_SLOT, info());
        inv.setItem(TARGET_SLOT, selected != null ? candidateHead(selected, true) : questionMark());
        for (int slot : DIVIDER_SLOTS) inv.setItem(slot, divider());

        int i = 0;
        for (String name : candidates) {
            if (i >= POOL_SLOTS.length) break;
            if (name.equals(selected)) continue;
            inv.setItem(POOL_SLOTS[i++], candidateHead(name, false));
        }
        for (String name : pastHolders) {
            if (i >= POOL_SLOTS.length) break;
            inv.setItem(POOL_SLOTS[i++], pastHolderHead(name));
        }

        inv.setItem(CONFIRM_SLOT, confirmButton(selected != null));
    }

    // ── Item builders ──────────────────────────────────────────────────────────

    private ItemStack info() {
        return AvalonItems.named(Items.HEART_OF_THE_SEA,
            Txt.t("Lady of the Lake", ChatFormatting.AQUA, ChatFormatting.BOLD),
            List.of(
                Txt.t("Pilih satu pemain untuk kamu lihat kubunya.", ChatFormatting.GRAY),
                Txt.t("Hasilnya hanya kamu yang tahu.", ChatFormatting.GRAY),
                Txt.t("Setelah itu Lady berpindah ke pemain tersebut.", ChatFormatting.GRAY)
            ));
    }

    private ItemStack divider() {
        return AvalonItems.named(Items.BLACK_STAINED_GLASS_PANE, Component.empty(), null);
    }

    private ItemStack questionMark() {
        return AvalonItems.texturedHead(
            TEXTURE_QUESTION_MARK,
            Txt.t("[ Belum Memilih ]", ChatFormatting.YELLOW, ChatFormatting.BOLD),
            List.of(Txt.t("Pilih pemain dari bawah", ChatFormatting.GRAY))
        );
    }

    private ItemStack candidateHead(String playerName, boolean selected) {
        ItemStack skull = AvalonItems.playerHead(playerName);
        if (isOffline(playerName)) {
            AvalonItems.setName(skull, Txt.t(playerName, ChatFormatting.GRAY, ChatFormatting.BOLD)
                .append(Txt.t(" (OFFLINE)", ChatFormatting.RED)));
        } else {
            AvalonItems.setName(skull, Txt.t(playerName, ChatFormatting.AQUA, ChatFormatting.BOLD));
        }
        AvalonItems.setLore(skull, List.of(selected
            ? Txt.t("Klik untuk membatalkan pilihan", ChatFormatting.GRAY)
            : Txt.t("Klik untuk memilih", ChatFormatting.GRAY)));
        AvalonItems.setTag(skull, KEY_CANDIDATE, playerName);
        return skull;
    }

    /** Pemain yang pernah memegang Lady: ditampilkan, tapi tidak bisa diperiksa. */
    private ItemStack pastHolderHead(String playerName) {
        ItemStack skull = AvalonItems.playerHead(playerName);
        AvalonItems.setName(skull, Txt.t(playerName, ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH));
        AvalonItems.setLore(skull, List.of(
            Txt.t("Pernah memegang Lady of the Lake.", ChatFormatting.RED),
            Txt.t("Tidak bisa diperiksa.", ChatFormatting.GRAY)
        ));
        return skull;
    }

    private ItemStack confirmButton(boolean ready) {
        Component displayName = Txt.t("✔ PERIKSA KESETIAAN",
            ready ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY, ChatFormatting.BOLD);
        List<Component> lore = ready
            ? List.of(
                Txt.t("Apakah kamu yakin? ini tidak bisa diubah!", ChatFormatting.YELLOW),
                Txt.t("Klik untuk memeriksa pemain ini.", ChatFormatting.WHITE))
            : List.of(
                Txt.t("Pilih satu pemain terlebih dahulu.", ChatFormatting.RED));
        return AvalonItems.texturedHead(TEXTURE_CHECKMARK, displayName, lore);
    }

    private static boolean isOffline(String playerName) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.getPlayerList().getPlayerByName(playerName) == null;
    }

    /** Nama pemain yang bisa diperiksa di item ini, atau null. */
    public static String getCandidate(ItemStack item) {
        return AvalonItems.getTag(item, KEY_CANDIDATE);
    }
}

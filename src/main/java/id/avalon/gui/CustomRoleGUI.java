package id.avalon.gui;

import id.avalon.core.AvalonItems;
import id.avalon.core.Txt;
import id.avalon.managers.GameManager;
import id.avalon.models.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

public class CustomRoleGUI {

    private final GameManager gameManager;

    // ── Mapping slot ──────────────────────────────────────────────────────────
    public static final int[] GOOD_ACTIVE  = { 0, 1, 2, 9, 10, 11 };
    public static final int[] EVIL_ACTIVE  = { 8, 7, 6, 17, 16, 15 };
    public static final int[] GOOD_POOL    = { 27, 28, 29 };
    public static final int[] EVIL_POOL    = { 31, 32, 33, 34, 35 };
    private static final int[] SEPARATOR   = { 18, 19, 20, 21, 22, 23, 24, 25, 26 };
    public  static final int   SAVE_SLOT   = 53;

    public CustomRoleGUI(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static Component title(RoleEditorSession session) {
        return Component.literal(session.getPlayerCount() + " Player Custom Role");
    }

    public SimpleContainer create(RoleEditorSession session) {
        SimpleContainer inv = AvalonMenu.newContainer();
        render(inv, session);
        return inv;
    }

    /** Render ulang isi GUI dari session (pengganti membuka inventory baru). */
    public void render(Container inv, RoleEditorSession session) {
        inv.clearContent();

        for (int slot : SEPARATOR) inv.setItem(slot, filler());

        int neededGood = session.getNeededGoodCount();
        for (int i = 0; i < neededGood; i++) {
            Role role = session.getGoodSlot(i);
            inv.setItem(GOOD_ACTIVE[i], role != null ? roleItem(role) : goodQuestionMark());
        }

        int neededEvil = session.getNeededEvilCount();
        for (int i = 0; i < neededEvil; i++) {
            Role role = session.getEvilSlot(i);
            inv.setItem(EVIL_ACTIVE[i], role != null ? roleItem(role) : evilQuestionMark());
        }

        List<Role> goodPool = session.buildGoodPool();
        for (int i = 0; i < goodPool.size() && i < GOOD_POOL.length; i++) {
            inv.setItem(GOOD_POOL[i], roleItem(goodPool.get(i)));
        }

        List<Role> evilPool = session.buildEvilPool();
        int evilStart = EVIL_POOL.length - evilPool.size();
        for (int i = 0; i < evilPool.size(); i++) {
            inv.setItem(EVIL_POOL[evilStart + i], roleItem(evilPool.get(i)));
        }

        inv.setItem(SAVE_SLOT, saveButton());
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    public ItemStack roleItem(Role role) {
        return switch (role) {
            case MERLIN -> customHead(
                    "http://textures.minecraft.net/texture/4fa89cc8985a8fafaf6d30c17d630434886c24b07496c6be6b018b0cdc94ddc",
                    Txt.t("Merlin", ChatFormatting.AQUA)
            );
            case PERCIVAL -> customHead(
                    "http://textures.minecraft.net/texture/f48ae5206da8fd83839103644752bd26ddd7f428047dcb3b551db51df7ea3b10",
                    Txt.t("Percival", ChatFormatting.AQUA)
            );
            case LOYAL_SERVANT -> customHead(
                    "http://textures.minecraft.net/texture/f4087b40471f8bfb007dd1d9ab995b5cfe2bbbe9d5f1fa8d35447de6754c6bc4",
                    Txt.t("Loyal Servant", ChatFormatting.AQUA)
            );
            case ASSASSIN -> customHead(
                    "http://textures.minecraft.net/texture/ea9e84680e5c31ba029f43d4a6cd6969e5ea846a204c5432a0e6d3ba250b1411",
                    Txt.t("Assassin", ChatFormatting.RED)
            );
            case MORGANA -> customHead(
                    "http://textures.minecraft.net/texture/555858616c2609c9a293da98f503fdb006b1854b058fe674c3c6f7d143a01e21",
                    Txt.t("Morgana", ChatFormatting.RED)
            );
            case MORDRED -> customHead(
                    "http://textures.minecraft.net/texture/312567a65043770ee35a7749b361d6c020105ffef2bc8401747815eddf61e96e",
                    Txt.t("Mordred", ChatFormatting.RED)
            );
            case OBERON -> customHead(
                    "http://textures.minecraft.net/texture/bab8f8e72fdaee2caf7220c438056a7fb7363edac791750a221bdb914d625cb6",
                    Txt.t("Oberon", ChatFormatting.RED)
            );
            case MINION_OF_MORDRED -> customHead(
                    "http://textures.minecraft.net/texture/4635c747938e21e25fe3da0aaaf7c5ecb14c3e3b6aba7d2ee6e397ed59255b0",
                    Txt.t("Minion Of Mordred", ChatFormatting.RED)
            );
        };
    }

    public ItemStack goodQuestionMark() {
        return customHead(
                "http://textures.minecraft.net/texture/c35ba393b8610b63ebee4c13c8358bc6c94a9dedc8e4d7d36b922257e65e8",
                Txt.t("Kosong", ChatFormatting.GRAY)
        );
    }

    public ItemStack evilQuestionMark() {
        return customHead(
                "http://textures.minecraft.net/texture/4fd5bde994e0a647af1823681a613c2bfc3d9736f889dbf8c3bbba5a13f8ed",
                Txt.t("Kosong", ChatFormatting.GRAY)
        );
    }

    private ItemStack saveButton() {
        return customHead(
                "http://textures.minecraft.net/texture/d9980c1d211809a9b6565088f56a38f2ef49115c1054fa66245122e9eeedecc2",
                Txt.t("SIMPAN", ChatFormatting.GREEN, ChatFormatting.BOLD)
        );
    }

    private ItemStack filler() {
        return AvalonItems.named(Items.BLACK_STAINED_GLASS_PANE, Component.literal(" "), null);
    }

    private ItemStack customHead(String textureUrl, Component displayName) {
        return AvalonItems.texturedHead(textureUrl, displayName, null);
    }
}

package id.avalon.core;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Helper item: nama/lore, tag data (pengganti PersistentDataContainer),
 * dan player head bertekstur (pengganti SkullMeta + PlayerProfile).
 */
public final class AvalonItems {

    /** Compound tag root untuk semua data Avalon di item (setara namespace PDC "avalon"). */
    public static final String ROOT = "Avalon";

    private AvalonItems() {}

    // ── Nama & lore ───────────────────────────────────────────────────────────

    public static ItemStack named(Item item, Component name, List<? extends Component> lore) {
        ItemStack stack = new ItemStack(item);
        setName(stack, name);
        if (lore != null) setLore(stack, lore);
        return stack;
    }

    public static void setName(ItemStack stack, Component name) {
        stack.setHoverName(name);
    }

    public static void setLore(ItemStack stack, List<? extends Component> lore) {
        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag list = new ListTag();
        for (Component line : lore) {
            list.add(StringTag.valueOf(Component.Serializer.toJson(line)));
        }
        display.put("Lore", list);
    }

    // ── Tag data (PDC) ────────────────────────────────────────────────────────

    public static void setTag(ItemStack stack, String key, String value) {
        stack.getOrCreateTagElement(ROOT).putString(key, value);
    }

    public static String getTag(ItemStack stack, String key) {
        if (stack == null || stack.isEmpty()) return null;
        CompoundTag root = stack.getTagElement(ROOT);
        if (root == null || !root.contains(key)) return null;
        return root.getString(key);
    }

    public static boolean hasTag(ItemStack stack, String key) {
        return getTag(stack, key) != null;
    }

    // ── Player head ───────────────────────────────────────────────────────────

    /**
     * Player head dengan tekstur dari URL textures.minecraft.net
     * (setara PlayerProfile#getTextures().setSkin(url)).
     */
    public static ItemStack texturedHead(String textureUrl, Component name, List<? extends Component> lore) {
        ItemStack skull = new ItemStack(Items.PLAYER_HEAD);

        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + textureUrl + "\"}}}";
        String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        UUID id = UUID.nameUUIDFromBytes(textureUrl.getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(id, null);
        profile.getProperties().put("textures", new Property("textures", value));

        skull.getOrCreateTag().put("SkullOwner", NbtUtils.writeGameProfile(new CompoundTag(), profile));

        if (name != null) setName(skull, name);
        if (lore != null) setLore(skull, lore);
        return skull;
    }

    /**
     * Player head dengan skin player (setara SkullMeta#setOwningPlayer).
     * Player online memakai profile lengkap; player offline di-resolve lewat profile cache.
     */
    public static ItemStack playerHead(String playerName) {
        ItemStack skull = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag tag = skull.getOrCreateTag();

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayer online = server != null ? server.getPlayerList().getPlayerByName(playerName) : null;

        if (online != null) {
            tag.put("SkullOwner", NbtUtils.writeGameProfile(new CompoundTag(), online.getGameProfile()));
        } else {
            GameProfile base = new GameProfile(null, playerName);
            if (server != null && server.getProfileCache() != null) {
                Optional<GameProfile> cached = server.getProfileCache().get(playerName);
                if (cached.isPresent()) base = cached.get();
            }
            tag.put("SkullOwner", NbtUtils.writeGameProfile(new CompoundTag(), base));
            SkullBlockEntity.updateGameprofile(base, resolved -> {
                if (resolved != null) {
                    tag.put("SkullOwner", NbtUtils.writeGameProfile(new CompoundTag(), resolved));
                }
            });
        }
        return skull;
    }
}

package id.avalon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import id.avalon.AvalonMod;
import id.avalon.network.AvalonNetwork;
import id.avalon.network.ClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * Inventory 1 slot di sisi client (selama game): hotbar vanilla diganti satu slot berbingkai,
 * inventory tidak bisa dibuka, dan slot yang dipegang selalu slot pertama.
 * Server tetap yang memaksa aturannya (lihat OneSlotListener); ini hanya tampilan & rasa.
 */
final class OneSlotHud {

    private OneSlotHud() {}

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(AvalonMod.MOD_ID, "textures/gui/one_slot.png");
    private static final int WIDTH = 68;
    private static final int HEIGHT = 24;
    /** Jarak tepi bingkai ke item 16x16 di tengahnya. */
    private static final int ITEM_INSET = 4;

    static boolean active() {
        return ClientState.oneSlot;
    }

    /** Gambar slot di tempat hotbar vanilla (yang gambarnya dibatalkan). */
    static void render(GuiGraphics graphics, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) return;
        // Penonton tidak membawa apa-apa
        if (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR) return;

        int x = screenWidth / 2 - WIDTH / 2;
        int y = screenHeight - HEIGHT;
        RenderSystem.enableBlend();
        graphics.blit(TEXTURE, x, y, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
        RenderSystem.disableBlend();

        ItemStack stack = player.getInventory().getItem(0);
        if (stack.isEmpty()) return;
        int itemX = screenWidth / 2 - 8;
        int itemY = y + ITEM_INSET;
        graphics.renderItem(player, stack, itemX, itemY, 0);
        graphics.renderItemDecorations(mc.font, stack, itemX, itemY);
    }

    static void tick() {
        if (!active()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.getInventory().selected = 0;
    }

    /** Layar inventory (survival maupun creative) tidak bisa dibuka selama game. */
    static boolean blocks(Screen screen) {
        return active() && (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen);
    }

    /** Beri tahu server ada klik kiri: item voting memakainya untuk "Tolak". */
    static void onAttackKey() {
        if (active()) AvalonNetwork.CHANNEL.sendToServer(new AvalonNetwork.LeftClick());
    }
}

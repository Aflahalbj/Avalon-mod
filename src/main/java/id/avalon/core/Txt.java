package id.avalon.core;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Helper pembuat teks, pengganti Adventure Component.text(...).
 */
public final class Txt {

    private Txt() {}

    /** Setara Component.text(text, color, decorations...). */
    public static MutableComponent t(String text, ChatFormatting... formats) {
        return Component.literal(text).withStyle(formats);
    }

    /** Setara Component.text(int, color). */
    public static MutableComponent t(int number, ChatFormatting... formats) {
        return t(String.valueOf(number), formats);
    }

    /** Baris kosong (Component.text(" ")). */
    public static MutableComponent blank() {
        return Component.literal(" ");
    }

    /** Teks polos (bisa berisi kode warna legacy §). */
    public static MutableComponent legacy(String text) {
        return Component.literal(text);
    }

    /** Setara Component.empty(). */
    public static MutableComponent empty() {
        return Component.empty();
    }
}

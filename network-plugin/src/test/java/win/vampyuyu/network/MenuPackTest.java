package win.vampyuyu.network;

import static org.junit.Assert.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.Test;

public class MenuPackTest {
    private static final String SHA = "0691dae7af81aea015365bf57a1c6b3de2997a8a";

    @Test
    public void urlContainsTheContentHashAndTrimsTheBase() {
        assertEquals("https://panel.vampyuyu.win/pack/" + SHA + ".zip",
                MenuPack.packUrl(" https://panel.vampyuyu.win// ", SHA));
    }

    @Test
    public void rejectsMalformedHashesAndNonHttpBases() {
        assertThrows(IllegalArgumentException.class, () -> MenuPack.parseSha1("ABC"));
        assertThrows(IllegalArgumentException.class, () -> MenuPack.parseSha1(SHA.toUpperCase()));
        assertThrows(IllegalArgumentException.class, () -> MenuPack.packUrl("file:///x", SHA));
    }

    @Test
    public void requiredIsOptInAndCaseInsensitive() {
        assertTrue(MenuPack.required("true"));
        assertTrue(MenuPack.required(" TRUE "));
        assertFalse(MenuPack.required(null));
        assertFalse(MenuPack.required("yes"));
        assertFalse(MenuPack.required(""));
    }

    @Test
    public void packIdIsStablePerRevision() {
        assertEquals(MenuPack.packId(SHA), MenuPack.packId(SHA));
        assertNotEquals(MenuPack.packId(SHA), MenuPack.packId("1".repeat(40)));
        assertEquals(20, MenuPack.parseSha1(SHA).length);
    }

    @Test
    public void titleDrawsEveryTileOnTheCustomFontAndKeepsTheTextOnTheDefaultFont() {
        Component title = MenuPack.title(MenuPack.LIST, MenuPack.ROWS_54, Component.text("Warps · 1"));
        TextComponent root = (TextComponent) title;
        assertEquals("", root.content());
        assertNull(root.font());
        TextComponent tiles = (TextComponent) root.children().get(0);
        assertEquals(MenuPack.FONT, tiles.font());
        String s = tiles.content();
        assertEquals(MenuPack.SHIFT_BACK, s.charAt(0));
        assertEquals(MenuPack.SHIFT_FORWARD, s.charAt(s.length() - 1));
        long drawn = s.chars().filter(ch -> ch >= 0xE300 && ch < 0xE400).count();
        assertEquals(MenuPack.COLS * MenuPack.ROWS_54, drawn);
        assertEquals(MenuPack.ROWS_54 - 1, s.chars().filter(ch -> ch == MenuPack.ROW_BACK).count());
        assertEquals((MenuPack.COLS - 1) * MenuPack.ROWS_54, s.chars().filter(ch -> ch == MenuPack.SEAM).count());
        TextComponent text = (TextComponent) root.children().get(1);
        assertEquals("Warps · 1", text.content());
        assertNull("dynamic text must not inherit the glyph font", text.font());
    }

    @Test
    public void paintedLobbyRoutesWholeArches() {
        assertEquals("creative", WorldSelector.destination(true, 10));
        assertEquals("survival", WorldSelector.destination(true, 24));
        assertEquals("lobby", WorldSelector.destination(true, 22));
        assertNull(WorldSelector.destination(true, 13));
        assertEquals("creative", WorldSelector.destination(false, 11));
        assertNull(WorldSelector.destination(false, 10));
    }
}

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
    public void packIdIsStablePerRevision() {
        assertEquals(MenuPack.packId(SHA), MenuPack.packId(SHA));
        assertNotEquals(MenuPack.packId(SHA), MenuPack.packId("1".repeat(40)));
        assertEquals(20, MenuPack.parseSha1(SHA).length);
    }

    @Test
    public void titleShiftsToTheWindowOriginAndBackBeforeTheRealText() {
        Component title = MenuPack.title(MenuPack.LOBBY, Component.text("Hollow"));
        TextComponent head = (TextComponent) title;
        assertEquals("", head.content());
        assertEquals(MenuPack.FONT, head.font());
        assertEquals(1, head.children().size());
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

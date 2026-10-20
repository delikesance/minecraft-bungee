package win.vampyuyu.arena;

import static org.junit.Assert.*;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Material;
import org.junit.Test;

public class MenuSkinTest {
  private static final String SHA = "0691dae7af81aea015365bf57a1c6b3de2997a8a";

  @Test
  public void packIdMatchesTheNetworkPluginDerivation() {
    // network-plugin MenuPack.packId uses the identical namespace string; both must agree
    // so a single client-side pack satisfies both plugins.
    assertEquals(
        UUID.nameUUIDFromBytes(("vampyuyu-menus:" + SHA).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        MenuSkin.packId(SHA));
    assertThrows(IllegalArgumentException.class, () -> MenuSkin.packId("nope"));
  }

  @Test
  public void titleDrawsEveryTileOnTheCustomFontAndKeepsTheTextOnTheDefaultFont() {
    var root = (TextComponent) MenuSkin.title(MenuSkin.SETUP, MenuSkin.ROWS_54, Component.text("Hollow"));
    assertEquals("", root.content());
    assertNull(root.font());
    var tiles = (TextComponent) root.children().get(0);
    assertEquals(MenuSkin.FONT, tiles.font());
    long drawn = tiles.content().chars().filter(ch -> ch >= 0xE200 && ch < 0xE300).count();
    assertEquals(MenuSkin.COLS * MenuSkin.ROWS_54, drawn);
    var text = (TextComponent) root.children().get(1);
    assertEquals("Hollow", text.content());
    assertNull("dynamic text must not inherit the glyph font", text.font());
  }

  @Test
  public void iconsFollowTheMaterialAndArrowDirection() {
    assertEquals("bed", MenuSkin.iconFor(Material.RED_BED, "Houses"));
    assertEquals("next", MenuSkin.iconFor(Material.ARROW, "Next"));
    assertEquals("back", MenuSkin.iconFor(Material.ARROW, "Previous"));
    assertEquals("back", MenuSkin.iconFor(Material.ARROW, "Setup"));
    assertEquals("trash", MenuSkin.iconFor(Material.BARRIER, "Delete arena"));
    assertEquals("tag", MenuSkin.iconFor(Material.DIAMOND, "anything else"));
  }

  @Test
  public void paintedCompassPlacesHeadsOnTheRingThenInside() {
    assertEquals(3, CompassGUI.headSlot(true, 0));
    assertEquals(41, CompassGUI.headSlot(true, 15));
    java.util.Set<Integer> used = new java.util.HashSet<>();
    for (int i = 0; i < 45; i++) {
      int slot = CompassGUI.headSlot(true, i);
      assertTrue("slot " + slot, slot >= 0 && slot < 45);
      assertTrue("duplicate " + slot, used.add(slot));
    }
    assertEquals(-1, CompassGUI.headSlot(true, 45));
    assertEquals(7, CompassGUI.headSlot(false, 7));
  }
}

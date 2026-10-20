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
  public void titleStartsWithTheShiftedWhiteGlyphThenTheDynamicName() {
    var title = (TextComponent) MenuSkin.title(MenuSkin.SETUP, Component.text("Hollow"));
    assertEquals("", title.content());
    assertEquals(MenuSkin.FONT, title.font());
    assertEquals(1, title.children().size());
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
}

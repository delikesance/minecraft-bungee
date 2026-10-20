package win.vampyuyu.arena;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Tracks which players loaded the optional Vampyuyu menu pack (offered by the network plugin) so
 * admin menus can use the painted layout for them and the plain glass-pane layout for everyone else.
 */
final class MenuSkin implements Listener {
  static final Key FONT = Key.key("vampyuyu", "menus");
  /** First tile of each background (see resource-pack/layout.py); each has COLS x rows tiles. */
  static final char SETUP = '\uE200';
  static final char LIST = '\uE300';
  static final char DETAIL = '\uE400';
  static final char CONFIRM = '\uE500';
  static final int COLS = 4;
  static final int ROWS_27 = 4;
  static final int ROWS_54 = 6;
  private static final char SHIFT_BACK = '\uE000';
  private static final char SHIFT_FORWARD = '\uE001';
  private static final char ROW_BACK = '\uE002';
  private static final char SEAM = '\uE003';

  private final UUID id;
  private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();

  MenuSkin(String sha1) {
    id = packId(sha1);
  }

  /** Same derivation as the network plugin, so both plugins react to the one pack the client holds. */
  static UUID packId(String sha1) {
    if (sha1 == null || !sha1.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("invalid SHA-1");
    return UUID.nameUUIDFromBytes(("vampyuyu-menus:" + sha1).getBytes(StandardCharsets.UTF_8));
  }

  static MenuSkin load(JavaPlugin plugin) {
    Path file =
        Path.of(System.getenv().getOrDefault("MENU_PACK_SHA1_FILE", "/opt/vampyuyu-menus.sha1"));
    if (!Files.isRegularFile(file)) return null;
    try {
      MenuSkin skin = new MenuSkin(Files.readString(file, StandardCharsets.UTF_8).trim());
      plugin.getServer().getPluginManager().registerEvents(skin, plugin);
      return skin;
    } catch (IOException | IllegalArgumentException e) {
      plugin.getLogger().warning("Menu skin disabled: " + e.getMessage());
      return null;
    }
  }

  boolean active(Player p) {
    return loaded.contains(p.getUniqueId());
  }

  @EventHandler
  public void status(PlayerResourcePackStatusEvent e) {
    if (!id.equals(e.getID())) return;
    UUID player = e.getPlayer().getUniqueId();
    switch (e.getStatus()) {
      case SUCCESSFULLY_LOADED -> loaded.add(player);
      case ACCEPTED, DOWNLOADED -> {}
      default -> loaded.remove(player);
    }
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    loaded.remove(e.getPlayer().getUniqueId());
  }

  /**
   * Full-window background drawn from font tiles, then the dynamic title text. The tiles carry the
   * custom font on their own component; the text stays a sibling so it keeps the default font.
   */
  static Component title(char base, int rows, Component after) {
    StringBuilder tiles = new StringBuilder().append(SHIFT_BACK);
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < COLS; c++) {
        tiles.append((char) (base + r * COLS + c));
        if (c < COLS - 1) tiles.append(SEAM);
      }
      if (r < rows - 1) tiles.append(ROW_BACK);
    }
    tiles.append(SHIFT_FORWARD);
    return Component.text()
        .append(Component.text(tiles.toString(), NamedTextColor.WHITE).font(FONT))
        .append(after)
        .build();
  }

  static ItemStack icon(ItemStack item, String name, boolean glow) {
    ItemMeta meta = item.getItemMeta();
    meta.setItemModel(new NamespacedKey("vampyuyu", "icon/" + name));
    if (glow) meta.setEnchantmentGlintOverride(true);
    item.setItemMeta(meta);
    return item;
  }

  /** Themed icon for the Bukkit material a menu entry was written with. */
  static String iconFor(Material material, String title) {
    String t = title.toLowerCase(java.util.Locale.ROOT);
    return switch (material) {
      case MAP -> "map";
      case COMPASS -> "compass";
      case WRITABLE_BOOK, BOOK -> "book";
      case OAK_DOOR, IRON_DOOR -> "door";
      case RED_BED -> "bed";
      case BONE -> "bone";
      case SUNFLOWER -> "sun";
      case CLOCK -> "clock";
      case LEVER -> "lever";
      case EMERALD -> "plus";
      case LIME_WOOL -> "play";
      case RED_WOOL -> "stop";
      case BARRIER -> "trash";
      case NOTE_BLOCK -> "note";
      case GOLDEN_AXE -> "axe";
      case ENDER_PEARL -> "pearl";
      case GRASS_BLOCK -> "sword";
      case ARROW -> t.startsWith("next") ? "next" : "back";
      default -> "tag";
    };
  }
}

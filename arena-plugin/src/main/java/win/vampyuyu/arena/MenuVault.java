package win.vampyuyu.arena;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lends a player's inventory to a menu: the real contents are saved (memory and disk), the menu
 * puts its buttons in the player's slots, and everything is handed back when the menu closes, the
 * player quits, the plugin stops, or - after a crash - the player next joins.
 */
final class MenuVault implements Listener {
  /** Marker for every chest menu that may use the bottom half of the window. */
  interface Menu extends InventoryHolder {}

  private static final int SLOTS = 36;
  private final JavaPlugin plugin;
  private final Path dir;
  private final Map<UUID, ItemStack[]> held = new ConcurrentHashMap<>();
  private final NamespacedKey button;
  /** Players whose previous menu is being replaced by the one we are opening right now. */
  private final Set<UUID> opening = ConcurrentHashMap.newKeySet();

  MenuVault(JavaPlugin plugin) {
    this.plugin = plugin;
    button = new NamespacedKey(plugin, "vault-button");
    dir = plugin.getDataFolder().toPath().resolve("vault");
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot create " + dir, e);
    }
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
  }

  /** Player-inventory index behind a raw slot below a chest window of {@code top} slots. */
  static int inventoryIndex(int top, int raw) {
    int n = raw - top;
    return n < 27 ? n + 9 : n - 27;
  }

  /** Raw slot of hotbar button {@code i} (0-8) in a chest window of {@code top} slots. */
  static int hotbar(int top, int i) {
    return top + 27 + i;
  }

  private Path file(UUID id) {
    return dir.resolve(id + ".bin");
  }

  /** Saves the inventory once, clears it, then opens the menu and fills the lower half. */
  void show(Player p, Inventory menu, Map<Integer, ItemStack> lower) {
    take(p);
    opening.add(p.getUniqueId());
    try {
      p.openInventory(menu);
    } finally {
      opening.remove(p.getUniqueId());
    }
    lower.forEach(
        (raw, item) -> {
          ItemMeta meta = item.getItemMeta();
          meta.getPersistentDataContainer().set(button, PersistentDataType.BYTE, (byte) 1);
          item.setItemMeta(meta);
          p.getInventory().setItem(inventoryIndex(menu.getSize(), raw), item);
        });
  }

  private boolean isButton(ItemStack item) {
    return item != null
        && item.hasItemMeta()
        && item.getItemMeta().getPersistentDataContainer().has(button, PersistentDataType.BYTE);
  }

  /** Gives the inventory back and closes a vault menu left open; used before other plugins read it. */
  void release(Player p) {
    if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
    give(p);
  }

  private void take(Player p) {
    UUID id = p.getUniqueId();
    PlayerInventory inv = p.getInventory();
    if (!held.containsKey(id)) {
      ItemStack[] saved = new ItemStack[SLOTS];
      for (int i = 0; i < SLOTS; i++) {
        ItemStack item = inv.getItem(i);
        saved[i] = item == null || item.getType().isAir() ? null : item.clone();
      }
      try {
        Files.write(file(id), ItemStack.serializeItemsAsBytes(saved));
      } catch (IOException e) {
        throw new IllegalStateException("Your inventory could not be saved, menu cancelled", e);
      }
      held.put(id, saved);
    }
    for (int i = 0; i < SLOTS; i++) inv.setItem(i, null);
  }

  /** Hands the real inventory back; safe to call when nothing is held. */
  void give(Player p) {
    UUID id = p.getUniqueId();
    ItemStack[] saved = held.remove(id);
    try {
      if (saved == null && Files.exists(file(id)))
        saved = ItemStack.deserializeItemsFromBytes(Files.readAllBytes(file(id)));
      if (saved == null) return;
      for (int i = 0; i < SLOTS; i++)
        p.getInventory().setItem(i, i < saved.length && !isButton(saved[i]) ? saved[i] : null);
      Files.deleteIfExists(file(id));
    } catch (IOException | RuntimeException e) {
      plugin.getLogger().warning("Inventory restore for " + id + " failed, file kept: " + e);
    }
  }

  void giveAll() {
    for (Player p : plugin.getServer().getOnlinePlayers()) give(p);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void close(InventoryCloseEvent e) {
    if (!(e.getPlayer() instanceof Player p) || !(e.getInventory().getHolder() instanceof Menu))
      return;
    if (opening.contains(p.getUniqueId())) return; // navigating to another menu of ours
    if (e.getReason() != InventoryCloseEvent.Reason.OPEN_NEW) give(p);
    else p.getScheduler().runDelayed(plugin, task -> recheck(p), null, 1L);
  }

  /** Something else opened over a menu: if no menu is on top a tick later, hand the items back. */
  private void recheck(Player p) {
    if (p.isOnline() && !(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu))
      give(p);
  }

  /** Buttons never drop as loot; the saved real items drop (or are kept) as they normally would. */
  @EventHandler(priority = EventPriority.HIGH)
  public void death(PlayerDeathEvent e) {
    Player p = e.getEntity();
    ItemStack[] saved = held.get(p.getUniqueId());
    if (saved == null) return;
    e.getDrops().removeIf(this::isButton);
    if (e.getKeepInventory()) {
      give(p);
      return;
    }
    for (ItemStack item : saved) if (item != null) e.getDrops().add(item);
    held.remove(p.getUniqueId());
    try {
      Files.deleteIfExists(file(p.getUniqueId()));
    } catch (IOException ex) {
      plugin.getLogger().warning("Vault file kept for " + p.getUniqueId() + ": " + ex);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void quit(PlayerQuitEvent e) {
    give(e.getPlayer());
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    Player p = e.getPlayer();
    if (Files.exists(file(p.getUniqueId())) && !held.containsKey(p.getUniqueId()))
      p.getScheduler().runDelayed(plugin, task -> give(p), null, 2L);
  }

  @EventHandler
  public void pickup(PlayerAttemptPickupItemEvent e) {
    if (held.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }
}

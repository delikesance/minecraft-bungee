package win.vampyuyu.arena;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

public final class CompassGUI implements Listener {
  private final NexusArenaEngine plugin;
  private final NamespacedKey key;
  private final Map<UUID, org.bukkit.profile.PlayerProfile> profiles =
      new java.util.concurrent.ConcurrentHashMap<>();

  void forget(UUID id) {
    profiles.remove(id);
  }

  CompassGUI(NexusArenaEngine plugin) {
    this.plugin = plugin;
    key = new NamespacedKey(plugin, "arena-compass");
  }

  static Component text(String text, NamedTextColor color) {
    return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
  }

  static ItemStack item(Material mat, String title, String... lore) {
    ItemStack item = new ItemStack(mat);
    var meta = item.getItemMeta();
    meta.displayName(text(title, NamedTextColor.GOLD));
    meta.lore(
        Arrays.stream(lore)
            .flatMap(line -> Arrays.stream(line.split("(?<=\\G.{60})")))
            .map(l -> text(l, NamedTextColor.GRAY))
            .toList());
    item.setItemMeta(meta);
    return item;
  }

  boolean compass(ItemStack item) {
    return item != null
        && item.hasItemMeta()
        && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
  }

  void giveCompass(Player p) {
    var profile = p.getPlayerProfile();
    if (profile.getProperties().stream()
        .anyMatch(property -> property.getName().equals("textures")))
      profiles.put(p.getUniqueId(), profile.clone());
    ItemStack item =
        item(
            Material.COMPASS,
            "Arena compass",
            "Your role, the current phase and living players",
            "Right-click to open · /arena leave to return");
    var meta = item.getItemMeta();
    meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    item.setItemMeta(meta);
    p.getInventory().setItem(8, item);
  }

  public void open(Player p) {
    ArenaSession s = plugin.arenas.session(p.getUniqueId());
    if (s == null) return;
    CompassMenuHolder holder = new CompassMenuHolder(p.getUniqueId(), s.config.id());
    render(holder, p, s);
    p.openInventory(holder.getInventory());
  }

  void refresh(Player p) {
    ArenaSession active = plugin.arenas.session(p.getUniqueId());
    if (active != null && active.game instanceof FreeplayGame game)
      p.sendActionBar(
          text(
              active.config.freeplay().objective()
                  + " · "
                  + game.scores().getOrDefault(p.getUniqueId(), 0)
                  + " / "
                  + game.goal()
                  + " points",
              NamedTextColor.GOLD));
    if (p.getOpenInventory().getTopInventory().getHolder() instanceof CompassMenuHolder holder) {
      ArenaSession s = plugin.arenas.session(p.getUniqueId());
      if (s == null) p.closeInventory();
      else render(holder, p, s);
    }
  }

  private static String friendly(String text, ArenaSession s) {
    for (var entry : s.players.entrySet())
      text = text.replace(entry.getKey().toString(), entry.getValue());
    return text;
  }

  private void render(CompassMenuHolder holder, Player p, ArenaSession s) {
    Inventory inv = holder.getInventory();
    holder.targets.clear();
    holder.abilities.clear();
    inv.clear();
    GamePreset game = s.game;
    Set<UUID> alive = game == null ? Set.copyOf(s.players.keySet()) : game.alive();
    Map<UUID, Integer> totals = game == null ? Map.of() : game.votes(p.getUniqueId());
    int slot = 0;
    for (var entry :
        s.players.entrySet().stream()
            .filter(e -> alive.contains(e.getKey()))
            .sorted(Map.Entry.comparingByValue())
            .toList()) {
      if (slot >= 45) break;
      ItemStack head =
          item(
              Material.PLAYER_HEAD,
              entry.getValue(),
              game == null ? "Waiting for the game to start" : game.phase(),
              game instanceof FreeplayGame
                  ? "Score: " + game.scores().getOrDefault(entry.getKey(), 0)
                  : "Votes: " + totals.getOrDefault(entry.getKey(), 0),
              game instanceof FreeplayGame
                  ? "Follow the objective to earn points"
                  : "Click to vote or use your current role action");
      SkullMeta skull = (SkullMeta) head.getItemMeta();
      var profile = profiles.get(entry.getKey());
      if (profile != null) skull.setOwnerProfile(profile.clone());
      head.setItemMeta(skull);
      holder.targets.put(slot, entry.getKey());
      inv.setItem(slot++, head);
    }
    for (int i = 45; i < 54; i++) inv.setItem(i, item(Material.RED_STAINED_GLASS_PANE, " "));
    String role = game == null ? "Not assigned yet" : game.role(p.getUniqueId());
    inv.setItem(
        45,
        item(
            Material.NETHER_STAR,
            "Your secret role: " + role,
            game == null
                ? "The game starts when an administrator is ready."
                : friendly(game.help(p.getUniqueId()), s),
            "Role information is visible only to you."));
    inv.setItem(
        48,
        item(
            Material.BOOK,
            s.config.name(),
            "Phase: " + s.phase(),
            "PvP: " + (s.config.allowPvP() ? "allowed" : "disabled"),
            "Stay within the arena. Your original items are stored.",
            game instanceof FreeplayGame
                ? friendly(game.help(p.getUniqueId()), s)
                : "Day ties and night ties eliminate nobody."));
    inv.setItem(
        50,
        item(
            Material.CLOCK,
            "Phase timer",
            s.game == null
                ? "Waiting for an administrator to start"
                : Math.max(0, (s.phaseEndsAt - System.currentTimeMillis() + 999) / 1000)
                    + " seconds remaining"));
    if (game != null) {
      int i = 0;
      int[] slots = {46, 47, 49, 51, 52};
      for (var ability : game.abilities(p.getUniqueId()).entrySet()) {
        if (i >= slots.length) break;
        int position = slots[i++];
        holder.abilities.put(position, ability.getKey());
        inv.setItem(
            position,
            item(Material.POTION, friendly(ability.getValue(), s), "Click to use this ability."));
      }
    }
    inv.setItem(
        53,
        item(Material.RED_WOOL, "Leave arena", "Restore your original items, stats and position."));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void interact(PlayerInteractEvent e) {
    if (compass(e.getItem()) && plugin.arenas.locked(e.getPlayer().getUniqueId())) {
      e.setCancelled(true);
      if (e.getAction().isRightClick()) open(e.getPlayer());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void click(InventoryClickEvent e) {
    if (!(e.getWhoClicked() instanceof Player p)) return;
    if (plugin.arenas.transitioning(p.getUniqueId())) {
      e.setCancelled(true);
      return;
    }
    if (e.getView().getTopInventory().getHolder() instanceof CompassMenuHolder holder) {
      e.setCancelled(true);
      ArenaSession s = plugin.arenas.session(p.getUniqueId());
      if (s == null
          || !holder.viewer.equals(p.getUniqueId())
          || !holder.arenaId.equals(s.config.id())) return;
      if (e.getRawSlot() == 53) {
        plugin.onPlayer(
            p,
            () -> {
              p.closeInventory();
              plugin.handle(p, plugin.arenas.leaveArena(p));
              return null;
            });
        return;
      }
      String ability = holder.abilities.get(e.getRawSlot());
      if (ability != null && s.game != null) {
        try {
          p.sendMessage(
              text(friendly(s.game.use(p.getUniqueId(), ability), s), NamedTextColor.GOLD));
          refresh(p);
        } catch (IllegalArgumentException ex) {
          p.sendMessage(text(ex.getMessage(), NamedTextColor.RED));
        }
        return;
      }
      UUID target = holder.targets.get(e.getRawSlot());
      if (target != null && s.game != null)
        try {
          if (s.game instanceof FreeplayGame) {
            p.sendMessage(text(s.game.help(p.getUniqueId()), NamedTextColor.GOLD));
            return;
          }
          String response =
              s.game
                  .select(p.getUniqueId(), target)
                  .replace(target.toString(), s.players.getOrDefault(target, "Player"));
          p.sendMessage(text(response, NamedTextColor.GOLD));
          refresh(p);
        } catch (IllegalArgumentException ex) {
          p.sendMessage(text(ex.getMessage(), NamedTextColor.RED));
        }
      return;
    }
    if (plugin.arenas.locked(p.getUniqueId())
        && (compass(e.getCurrentItem())
            || compass(e.getCursor())
            || e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() == 8
            || e.getClickedInventory() == p.getInventory() && e.getSlot() == 8))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void drag(InventoryDragEvent e) {
    if (!(e.getWhoClicked() instanceof Player p)) return;
    if (plugin.arenas.transitioning(p.getUniqueId())
        || e.getView().getTopInventory().getHolder() instanceof CompassMenuHolder
        || plugin.arenas.locked(p.getUniqueId())
            && (compass(e.getOldCursor())
                || e.getRawSlots().contains(e.getView().getTopInventory().getSize() + 35)))
      e.setCancelled(true);
  }
}

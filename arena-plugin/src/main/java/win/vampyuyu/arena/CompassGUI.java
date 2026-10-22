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

  static NamedTextColor roleColor(String role) {
    return switch (role) {
      case "WEREWOLF" -> NamedTextColor.RED;
      case "SEER" -> NamedTextColor.LIGHT_PURPLE;
      case "GUARD" -> NamedTextColor.AQUA;
      case "WITCH" -> NamedTextColor.DARK_PURPLE;
      case "CUPID" -> NamedTextColor.LIGHT_PURPLE;
      case "HUNTER" -> NamedTextColor.DARK_GREEN;
      default -> NamedTextColor.WHITE;
    };
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

  /**
   * Menus open by themselves during a village game, so nobody keeps the compass: an item in hand
   * that only some roles carried would give them away.
   */
  void disarm(Player p) {
    p.getInventory().setItem(8, null);
  }

  public void open(Player p) {
    ArenaSession s = plugin.arenas.session(p.getUniqueId());
    if (s == null) return;
    boolean voting =
        s.game != null
            && (s.game.phase().equals("DAY_VOTE")
                || s.game.phase().equals("NIGHT") && s.game.role(p.getUniqueId()).equals("WEREWOLF"));
    boolean brewing =
        s.game != null
            && s.game.phase().equals("WITCH")
            && s.game.role(p.getUniqueId()).equals("WITCH");
    String name = voting ? "Voting" : brewing ? "Alembic" : "Arena compass";
    boolean skinned = plugin.skin != null && plugin.skin.active(p);
    CompassMenuHolder holder =
        new CompassMenuHolder(
            p.getUniqueId(),
            s.config.id(),
            skinned,
            skinned
                ? MenuSkin.title(
                    brewing ? MenuSkin.ALEMBIC : MenuSkin.COMPASS,
                    MenuSkin.ROWS_54,
                    Component.text(name, GOLD))
                : Component.text("Vampyuyu · " + name));
    p.openInventory(holder.getInventory());
    render(holder, p, s);
  }

  /** Menus a player must act in cannot be closed; they reopen on the next tick. */
  boolean mandatory(ArenaSession s, UUID id) {
    return s != null
        && !s.stopping
        && !s.finishing
        && s.game instanceof WerewolfGame game
        && WerewolfPresentation.menuRequired(game, id);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void close(InventoryCloseEvent e) {
    if (!(e.getPlayer() instanceof Player p)
        || !(e.getInventory().getHolder() instanceof CompassMenuHolder)) return;
    ArenaSession open = plugin.arenas.session(p.getUniqueId());
    // A disconnecting player gets the stored snapshot instead; never touch it here.
    if (open != null
        && open.game instanceof WerewolfGame
        && e.getReason() != InventoryCloseEvent.Reason.DISCONNECT) clearBottom(p);
    if (!mandatory(open, p.getUniqueId())) return;
    p.getScheduler()
        .runDelayed(
            plugin,
            task -> {
              if (p.isOnline()
                  && !plugin.arenas.transitioning(p.getUniqueId())
                  && mandatory(plugin.arenas.session(p.getUniqueId()), p.getUniqueId())
                  && !(p.getOpenInventory().getTopInventory().getHolder()
                      instanceof CompassMenuHolder)) open(p);
            },
            null,
            2);
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

  /** Council ring first, then the inner slots, so up to 45 players still fit the painted window. */
  static final int[] RING = {3, 4, 5, 10, 11, 15, 16, 18, 26, 28, 29, 33, 34, 39, 40, 41};

  static int headSlot(boolean skinned, int index) {
    if (!skinned) return index;
    if (index < RING.length) return RING[index];
    int extra = index - RING.length;
    for (int slot = 0; slot < 45; slot++) {
      boolean inRing = false;
      for (int r : RING) inRing |= r == slot;
      if (!inRing && extra-- == 0) return slot;
    }
    return -1;
  }

  private static ItemStack themed(CompassMenuHolder holder, ItemStack item, String icon) {
    return holder.skinned ? MenuSkin.icon(item, icon, false) : item;
  }

  private static final TextColor GOLD = TextColor.color(0xe9d99b);

  /** Pushes private game notices to their recipients only; the lovers' bond gets a title and sound. */
  void deliverNotices(ArenaSession s) {
    if (s.game == null) return;
    for (var notice : s.game.drainNotices().entrySet()) {
      Player target = Bukkit.getPlayer(notice.getKey());
      if (target == null) continue;
      String message = friendly(notice.getValue(), s);
      plugin.onPlayer(
          target,
          () -> {
            target.sendMessage(text(message, NamedTextColor.LIGHT_PURPLE));
            if (!message.startsWith(WerewolfGame.LOVE_NOTICE)) return null;
            target.showTitle(
                net.kyori.adventure.title.Title.title(
                    text("♥ In love", NamedTextColor.LIGHT_PURPLE),
                    text("Check your menu for your partner", NamedTextColor.GRAY)));
            target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, .5f, 1.6f);
            return null;
          });
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
    WerewolfPresentation.View view =
        game instanceof WerewolfGame wolf ? WerewolfPresentation.view(wolf, p.getUniqueId()) : null;
    String phaseLabel = view == null ? s.phase() : view.phase();
    Set<UUID> alive = game == null ? Set.copyOf(s.players.keySet()) : game.alive();
    Map<UUID, Integer> totals = game == null ? Map.of() : game.votes(p.getUniqueId());
    int index = 0;
    for (var entry :
        s.players.entrySet().stream()
            .filter(e -> alive.contains(e.getKey()))
            .sorted(Map.Entry.comparingByValue())
            .toList()) {
      int slot = headSlot(holder.skinned, index++);
      if (slot < 0 || index > 45) break;
      ItemStack head =
          item(
              Material.PLAYER_HEAD,
              entry.getValue(),
              game == null ? "Waiting for the game to start" : phaseLabel,
              game instanceof FreeplayGame
                  ? "Score: " + game.scores().getOrDefault(entry.getKey(), 0)
                  : "Votes: " + totals.getOrDefault(entry.getKey(), 0),
              game instanceof FreeplayGame
                  ? "Follow the objective to earn points"
                  : view == null
                      ? "Click to vote or use your current role action"
                      : view.instruction());
      SkullMeta skull = (SkullMeta) head.getItemMeta();
      var profile = profiles.get(entry.getKey());
      if (profile != null) skull.setOwnerProfile(profile.clone());
      head.setItemMeta(skull);
      holder.targets.put(slot, entry.getKey());
      inv.setItem(slot, head);
    }
    // Werewolf menus use the whole window: players on top, everything else in the player-inventory
    // half (the arena inventory is otherwise empty). Other games keep the bottom row of the chest.
    boolean wide = game instanceof WerewolfGame;
    if (wide) clearBottom(p);
    else if (!holder.skinned)
      for (int i = 45; i < 54; i++) inv.setItem(i, item(Material.RED_STAINED_GLASS_PANE, " "));
    int roleAt = wide ? 67 : 45, bookAt = wide ? 68 : 48, clockAt = wide ? 81 : 50;
    int[] abilitySlots = wide ? new int[] {83, 84, 85} : new int[] {46, 47, 49, 51, 52};
    holder.leaveSlot = wide ? 88 : 53;
    holder.phase = game == null ? "" : game.phase();
    String role = game == null ? "Not assigned yet" : game.role(p.getUniqueId());
    ItemStack roleCard =
        themed(
            holder,
            item(
                Material.NETHER_STAR,
                "Your secret role: " + role,
                game == null
                    ? "The game starts when an administrator is ready."
                    : friendly(game.help(p.getUniqueId()), s),
                "Role information is visible only to you."),
            "star");
    var roleMeta = roleCard.getItemMeta();
    roleMeta.displayName(text("Your secret role: " + role, roleColor(role)));
    roleCard.setItemMeta(roleMeta);
    place(inv, p, roleAt, roleCard);
    place(
        inv,
        p,
        bookAt,
        themed(
            holder,
            item(
                Material.BOOK,
                s.config.name(),
                "Phase: " + phaseLabel,
                "PvP: " + (s.config.allowPvP() ? "allowed" : "disabled"),
                "Stay within the arena. Your original items are stored.",
                game instanceof FreeplayGame
                    ? friendly(game.help(p.getUniqueId()), s)
                    : "Ties eliminate nobody. Wolves win once no villager is left."),
            "book"));
    place(
        inv,
        p,
        clockAt,
        themed(
            holder,
            item(
                Material.CLOCK,
                "Phase timer",
                s.game == null
                    ? "Waiting for an administrator to start"
                    : view != null && !view.timerVisible()
                        ? view.instruction()
                        : Math.max(0, (s.phaseEndsAt - System.currentTimeMillis() + 999) / 1000)
                            + " seconds remaining"),
            "clock"));
    if (wide)
      place(
          inv,
          p,
          66,
          themed(
              holder,
              item(Material.BELL, "Village", alive.size() + " of " + s.players.size() + " alive"),
              "tag"));
    if (game != null) {
      int i = 0;
      for (var ability : game.abilities(p.getUniqueId()).entrySet()) {
        if (i >= abilitySlots.length) break;
        int position = abilitySlots[i++];
        holder.abilities.put(position, ability.getKey());
        place(
            inv,
            p,
            position,
            themed(
                holder,
                item(Material.POTION, friendly(ability.getValue(), s), "Click to use this ability."),
                "vial"));
      }
    }
    if (plugin.arenas.leaveBlocked(p.getUniqueId())) {
      if (wide)
        place(
            inv,
            p,
            holder.leaveSlot,
            item(Material.BARRIER, "Leave locked", "You cannot leave while the game is running."));
    } else
      place(
          inv,
          p,
          holder.leaveSlot,
          themed(
              holder,
              item(
                  Material.RED_WOOL,
                  "Leave arena",
                  "Restore your original items, stats and position."),
              "exit"));
  }

  /** Raw slot in the 54-slot window: 0-53 chest, 54-80 inventory rows, 81-89 hotbar. */
  private static void place(Inventory top, Player p, int raw, ItemStack item) {
    if (raw < 54) top.setItem(raw, item);
    else p.getInventory().setItem(inventoryIndex(raw), item);
  }

  /** Player-inventory index behind a raw slot of the 54-slot window (54-80 rows, 81-89 hotbar). */
  static int inventoryIndex(int raw) {
    return raw < 81 ? raw - 54 + 9 : raw - 81;
  }

  /** Empties the menu half of the inventory, leaving hotbar slot 8 alone. */
  private void clearBottom(Player p) {
    for (int i = 0; i < 36; i++) if (i != 8) p.getInventory().setItem(i, null);
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
      if (e.getRawSlot() == holder.leaveSlot && holder.leaveSlot != -1) {
        if (plugin.arenas.leaveBlocked(p.getUniqueId())) return;
        plugin.onPlayer(
            p,
            () -> {
              p.closeInventory();
              plugin.handle(p, plugin.arenas.leaveArena(p));
              return null;
            });
        return;
      }
      if (s.game != null && !s.game.phase().equals(holder.phase)) {
        refresh(p);
        return;
      }
      String ability = holder.abilities.get(e.getRawSlot());
      if (ability != null && s.game != null && s.presentation.isDone() && !s.finishing) {
        try {
          p.sendMessage(
              text(
                  friendly(s.game.use(p.getUniqueId(), ability), s),
                  roleColor(s.game.role(p.getUniqueId()))));
          plugin.arenas.phaseChanged(s);
          refresh(p);
        } catch (IllegalArgumentException ex) {
          p.sendMessage(text(ex.getMessage(), NamedTextColor.RED));
        }
        return;
      }
      UUID target = holder.targets.get(e.getRawSlot());
      if (target != null && s.game != null && s.presentation.isDone() && !s.finishing)
        try {
          if (s.game instanceof FreeplayGame) {
            p.sendMessage(text(s.game.help(p.getUniqueId()), NamedTextColor.GOLD));
            return;
          }
          String response =
              s.game
                  .select(p.getUniqueId(), target)
                  .replace(target.toString(), s.players.getOrDefault(target, "Player"));
          p.sendMessage(text(response, roleColor(s.game.role(p.getUniqueId()))));
          deliverNotices(s);
          plugin.arenas.phaseChanged(s);
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

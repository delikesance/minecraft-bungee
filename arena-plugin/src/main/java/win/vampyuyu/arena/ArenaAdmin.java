package win.vampyuyu.arena;

import com.google.gson.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;

final class ArenaAdmin implements Listener {
  private final NexusArenaEngine plugin;
  private final Map<UUID, Location[]> corners = new HashMap<>();
  private final Map<UUID, Input> inputs = new ConcurrentHashMap<>();

  private interface Action {
    void run() throws Exception;
  }

  private interface Input {
    void accept(String text) throws Exception;
  }

  private static final TextColor GOLD = TextColor.color(0xe9d99b);

  private enum Kind {
    SETUP(MenuSkin.SETUP, 54, MenuSkin.ROWS_54),
    LIST(MenuSkin.LIST, 54, MenuSkin.ROWS_54),
    DETAIL(MenuSkin.DETAIL, 27, MenuSkin.ROWS_27),
    CONFIRM(MenuSkin.CONFIRM, 27, MenuSkin.ROWS_27);

    final char glyph;
    final int size;
    final int rows;

    Kind(char glyph, int size, int rows) {
      this.glyph = glyph;
      this.size = size;
      this.rows = rows;
    }
  }

  /** Painted menu when the viewer loaded the resource pack, glass-pane chest otherwise. */
  private final class Menu implements InventoryHolder {
    final UUID viewer;
    final Inventory inventory;
    final boolean skinned;
    final Map<Integer, Action> actions = new HashMap<>();

    Menu(Player p, Kind kind, String title) {
      viewer = p.getUniqueId();
      skinned = plugin.skin != null && plugin.skin.active(p);
      if (skinned) {
        inventory =
            Bukkit.createInventory(this, kind.size, MenuSkin.title(kind.glyph, kind.rows, Component.text(title, GOLD)));
      } else {
        inventory = Bukkit.createInventory(this, kind.size, Component.text("Vampyuyu · " + title, GOLD));
        for (int i = 0; i < kind.size; i++)
          inventory.setItem(i, CompassGUI.item(Material.RED_STAINED_GLASS_PANE, " "));
      }
    }

    public Inventory getInventory() {
      return inventory;
    }

    void put(int slot, Material mat, String title, Action action, String... lore) {
      place(slot, mat, null, false, title, action, lore);
    }

    /** A switch: the item glows when on, and the state is always in the tooltip. */
    void toggle(int slot, Material mat, String title, boolean on, Action action, String... lore) {
      String[] all = new String[lore.length + 1];
      all[0] = on ? "State: ON" : "State: OFF";
      System.arraycopy(lore, 0, all, 1, lore.length);
      place(slot, mat, null, on, title, action, all);
    }

    /** One action over several slots, so a painted button is clickable on its whole surface. */
    void area(int[] slots, Material mat, String icon, String title, Action action, String... lore) {
      for (int slot : slots) place(slot, mat, icon, false, title, action, lore);
    }

    void place(
        int slot, Material mat, String icon, boolean glow, String title, Action action, String... lore) {
      ItemStack item = CompassGUI.item(mat, title, lore);
      if (skinned) MenuSkin.icon(item, icon != null ? icon : MenuSkin.iconFor(mat, title), glow);
      inventory.setItem(slot, item);
      if (action != null) actions.put(slot, action);
    }
  }

  ArenaAdmin(NexusArenaEngine plugin) {
    this.plugin = plugin;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  void authorize(Player p) {
    if (!p.hasPermission("nexusarena.admin") || plugin.arenas.locked(p.getUniqueId()))
      throw new IllegalArgumentException("Administrator access outside an arena required");
  }

  ArenaSession editable(String id) {
    ArenaSession s = plugin.arenas.require(id);
    if (!s.players.isEmpty()
        || s.game != null
        || s.stopping
        || s.finishing
        || plugin.village.pending(id))
      throw new IllegalArgumentException("Stop and empty this arena before editing");
    return s;
  }

  void change(String id, java.util.function.Consumer<JsonObject> edit) throws Exception {
    synchronized (plugin.arenas) {
      JsonObject json = ArenaManager.JSON.toJsonTree(editable(id).config).getAsJsonObject();
      edit.accept(json);
      plugin.arenas.save(ArenaManager.JSON.fromJson(json, ArenaConfig.class));
    }
  }

  ArenaConfig.Spawn point(Player p, String id) {
    ArenaConfig c = editable(id).config;
    Location l = p.getLocation();
    if (!l.getWorld().getName().equals(c.worldName()))
      throw new IllegalArgumentException("Use this arena's world");
    return new ArenaConfig.Spawn(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
  }

  boolean command(CommandSender sender, String sub, String[] args) throws Exception {
    if (!Set.of(
            "admin",
            "create",
            "setup",
            "corner1",
            "corner2",
            "house",
            "houses",
            "meeting",
            "daypoint",
            "doors",
            "tools",
            "select")
        .contains(sub)) return false;
    if (!(sender instanceof Player p))
      throw new IllegalArgumentException("Use this command in game");
    authorize(p);
    switch (sub) {
      case "admin" -> list(p, 0);
      case "tools", "select" -> plugin.tools.command(p, sub, args);
      case "corner1", "corner2" -> {
        capture(p, sub.equals("corner1") ? 0 : 1);
        plugin.message(p, "Corner recorded");
      }
      case "create" -> {
        if (args.length != 3)
          throw new IllegalArgumentException("/arena create <id> <werewolf|freeplay>");
        create(p, args[1], args[2]);
      }
      case "setup" -> {
        need(args, 2, "/arena setup <id>");
        setup(p, args[1]);
      }
      case "houses" -> {
        need(args, 2, "/arena houses <id>");
        houses(p, args[1], 0);
      }
      case "house" -> {
        need(args, 4, "/arena house <id> add <name>");
        if (!args[2].equals("add")) throw new IllegalArgumentException("Use add");
        addHouse(p, args[1], args[3]);
        houses(p, args[1], 0);
      }
      case "meeting", "daypoint" -> {
        need(args, 3, "/arena " + sub + " <id> set");
        if (!args[2].equals("set")) throw new IllegalArgumentException("Use set");
        setPoint(p, args[1], sub);
        setup(p, args[1]);
      }
      case "doors" -> {
        need(args, 2, "/arena doors <id>");
        doors(p, args[1], 0);
      }
    }
    return true;
  }

  private static void need(String[] args, int n, String usage) {
    if (args.length != n) throw new IllegalArgumentException(usage);
  }

  boolean rewards(CommandSender sender, Command cmd, String label, String[] args) {
    try {
      if (!sender.hasPermission("nexusarena.admin"))
        throw new IllegalArgumentException("Arena administrator permission required");
      if (sender instanceof Player p) authorize(p);
      need(args, 4, "/eventadmin rewards <arena> <participation> <victory>");
      if (!args[0].equals("rewards")) throw new IllegalArgumentException("Use rewards");
      var settings = new ArenaConfig.Rewards(Integer.parseInt(args[2]), Integer.parseInt(args[3]));
      change(args[1], json -> json.add("rewards", ArenaManager.JSON.toJsonTree(settings)));
      sender.sendMessage("Rewards saved");
    } catch (Exception e) {
      sender.sendMessage(NexusArenaEngine.reason(e));
    }
    return true;
  }

  void capture(Player p, int index) {
    capture(p, index, p.getLocation());
  }

  void capture(Player p, int index, Location where) {
    Location[] selected = corners.computeIfAbsent(p.getUniqueId(), key -> new Location[2]);
    selected[index] = where.clone();
  }

  Location[] corners(Player p) {
    return corners.get(p.getUniqueId());
  }

  ArenaConfig.Bounds bounds(Player p) {
    var points = corners.get(p.getUniqueId());
    if (points == null || points[0] == null || points[1] == null)
      throw new IllegalArgumentException("Select /arena corner1 and /arena corner2 first");
    if (!points[0].getWorld().equals(points[1].getWorld())
        || !points[0].getWorld().equals(p.getWorld()))
      throw new IllegalArgumentException("Both corners must be in your current world");
    return new ArenaConfig.Bounds(
        points[0].getX(),
        points[0].getY(),
        points[0].getZ(),
        points[1].getX(),
        points[1].getY(),
        points[1].getZ());
  }

  private void create(Player p, String id, String preset) throws Exception {
    if (plugin.arenas.all().stream().anyMatch(s -> s.config.id().equals(id)))
      throw new IllegalArgumentException("Arena ID already exists");
    if (!Set.of("werewolf", "freeplay").contains(preset))
      throw new IllegalArgumentException("Use werewolf or freeplay");
    var l = p.getLocation();
    ArenaConfig config =
        new ArenaConfig(
            id,
            id,
            l.getWorld().getName(),
            preset,
            bounds(p),
            new ArenaConfig.Spawn(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()),
            Set.of(),
            Set.of(),
            false,
            false,
            false,
            GameMode.ADVENTURE,
            900,
            preset.equals("werewolf") ? 4 : 1,
            16,
            60,
            30,
            30,
            GameRules.Werewolf.defaults(),
            GameRules.Freeplay.defaults());
    confirm(
        p,
        "Create " + id,
        () -> {
          if (plugin.arenas.all().stream().anyMatch(s -> s.config.id().equals(id)))
            throw new IllegalArgumentException("Arena already exists");
          plugin.arenas.save(config);
          setup(p, id);
        },
        () -> list(p, 0));
  }

  void list(Player p, int page) {
    var arenas =
        plugin.arenas.all().stream().sorted(Comparator.comparing(s -> s.config.id())).toList();
    Menu m = new Menu(p, Kind.LIST, "Arenas");
    for (int i = page * 36; i < Math.min(arenas.size(), (page + 1) * 36); i++) {
      var s = arenas.get(i);
      m.put(
          i - page * 36,
          Material.MAP,
          s.config.name(),
          () -> setup(p, s.config.id()),
          s.config.id() + " · " + s.phase());
    }
    paging(m, page, arenas.size(), n -> list(p, n));
    m.place(
        46,
        Material.LIME_WOOL,
        "ruler",
        false,
        "Set first corner here",
        () -> {
          capture(p, 0);
          list(p, page);
        });
    m.place(
        47,
        Material.LIME_WOOL,
        "ruler",
        false,
        "Set second corner here",
        () -> {
          capture(p, 1);
          list(p, page);
        });
    m.area(
        new int[] {48, 49, 50},
        Material.EMERALD,
        "plus",
        "Create arena",
        () ->
            prompt(
                p,
                "New arena ID:",
                id -> {
                  Menu choice = new Menu(p, Kind.DETAIL, "Choose preset");
                  choice.put(11, Material.BONE, "Werewolf", () -> create(p, id, "werewolf"));
                  choice.put(
                      15, Material.GRASS_BLOCK, "Free play", () -> create(p, id, "freeplay"));
                  p.openInventory(choice.inventory);
                }),
        "Select both corners, then stand at the spawn");
    p.openInventory(m.inventory);
  }

  void setup(Player p, String id) {
    var c = plugin.arenas.require(id).config;
    Menu m = new Menu(p, Kind.SETUP, c.name());
    // Row 0: the arena itself and its life cycle.
    m.put(
        1,
        Material.WRITABLE_BOOK,
        "Name: " + c.name(),
        () ->
            prompt(
                p,
                "Arena display name:",
                text -> {
                  change(id, j -> j.addProperty("name", text));
                  setup(p, id);
                }));
    m.put(
        2,
        Material.COMPASS,
        "Set spawn here",
        () -> {
          var position = point(p, id);
          change(id, j -> j.add("spawn", ArenaManager.JSON.toJsonTree(position)));
          setup(p, id);
        });
    m.put(
        3,
        Material.MAP,
        "Use selected bounds",
        () -> {
          var b = bounds(p);
          change(id, j -> j.add("bounds", ArenaManager.JSON.toJsonTree(b)));
          setup(p, id);
        });
    m.put(
        5,
        Material.GOLDEN_AXE,
        "Get admin tools",
        () -> {
          p.closeInventory();
          plugin.tools.give(p, id);
        },
        "Items for corners, spawn, houses, points and doors");
    m.put(
        6,
        Material.WRITABLE_BOOK,
        "Readiness checklist",
        () -> {
          p.closeInventory();
          plugin.tools.report(p, id);
        });
    m.put(
        7,
        Material.LIME_WOOL,
        "Start",
        () -> {
          plugin.arenas.start(id);
          p.closeInventory();
        });
    m.put(8, Material.RED_WOOL, "Stop & restore", () -> plugin.handle(p, plugin.arenas.stop(id)));
    // Row 1: village.
    m.toggle(
        10,
        Material.OAK_DOOR,
        "Village",
        c.village().enabled(),
        () -> {
          change(
              id, j -> j.getAsJsonObject("village").addProperty("enabled", !c.village().enabled()));
          plugin.tools.report(p, id);
          setup(p, id);
        },
        "Click to toggle; the checklist shows what is missing");
    m.put(
        11,
        Material.RED_BED,
        "Houses (" + c.village().houses().size() + ")",
        () -> houses(p, id, 0));
    m.put(
        12,
        Material.BONE,
        "Set wolf meeting here",
        () -> {
          setPoint(p, id, "meeting");
          setup(p, id);
        });
    m.put(
        13,
        Material.SUNFLOWER,
        "Set day point here",
        () -> {
          setPoint(p, id, "daypoint");
          setup(p, id);
        });
    m.put(14, Material.IRON_DOOR, "Controlled doors", () -> doors(p, id, 0));
    if (c.preset().equals("werewolf"))
      m.toggle(
          15,
          Material.NOTE_BLOCK,
          "Immersive ambience",
          c.werewolf().ambienceEnabled(),
          () -> {
            change(
                id,
                j ->
                    j.getAsJsonObject("werewolf")
                        .addProperty("ambienceEnabled", !c.werewolf().ambienceEnabled()));
            setup(p, id);
          },
          "Native sounds and titles",
          "Private instructions remain available when disabled");
    // Row 2: limits.
    String[] numbers = {
      "minPlayers",
      "maxPlayers",
      "durationSeconds",
      "discussionSeconds",
      "voteSeconds",
      "nightSeconds"
    };
    int[] values = {
      c.minPlayers(),
      c.maxPlayers(),
      c.durationSeconds(),
      c.discussionSeconds(),
      c.voteSeconds(),
      c.nightSeconds()
    };
    for (int i = 0; i < numbers.length; i++) {
      String key = numbers[i];
      m.put(
          19 + i,
          Material.CLOCK,
          key + ": " + values[i],
          () ->
              prompt(
                  p,
                  key + ":",
                  value -> {
                    int n = Integer.parseInt(value);
                    change(id, j -> j.addProperty(key, n));
                    setup(p, id);
                  }),
          "Click to change");
    }
    // Row 3: rules and rewards.
    String[] toggles = {"allowPvP", "allowChests", "allowDoors"};
    boolean[] states = {c.allowPvP(), c.allowChests(), c.allowDoors()};
    for (int i = 0; i < toggles.length; i++) {
      String key = toggles[i];
      m.toggle(
          28 + i,
          Material.LEVER,
          key,
          states[i],
          () -> {
            change(id, j -> j.addProperty(key, !j.get(key).getAsBoolean()));
            setup(p, id);
          });
    }
    m.put(
        31,
        Material.SUNFLOWER,
        "Rewards",
        () ->
            prompt(
                p,
                "Participation and victory bonus, e.g. 10 5:",
                value -> {
                  String[] parts = value.trim().split("\\s+");
                  if (parts.length != 2) throw new IllegalArgumentException("Use two numbers");
                  var r =
                      new ArenaConfig.Rewards(
                          Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                  change(id, j -> j.add("rewards", ArenaManager.JSON.toJsonTree(r)));
                  setup(p, id);
                }),
        c.rewards().participation() + " participation + " + c.rewards().victory() + " victory");
    m.put(
        44,
        Material.BARRIER,
        "Delete arena",
        () ->
            confirm(
                p,
                "Delete " + id,
                () -> {
                  editable(id);
                  plugin.arenas.delete(id);
                  list(p, 0);
                },
                () -> setup(p, id)));
    m.put(45, Material.ARROW, "Back", () -> list(p, 0));
    p.openInventory(m.inventory);
  }

  void setPoint(Player p, String id, String field) throws Exception {
    var position = point(p, id);
    change(
        id, j -> j.getAsJsonObject("village").add(field, ArenaManager.JSON.toJsonTree(position)));
  }

  private void addHouse(Player p, String id, String name) throws Exception {
    var position = point(p, id);
    change(
        id,
        j -> {
          var houses = j.getAsJsonObject("village").getAsJsonObject("houses");
          if (houses.has(name)) throw new IllegalArgumentException("House name already exists");
          houses.add(name, ArenaManager.JSON.toJsonTree(position));
        });
  }

  private static String coordinates(ArenaConfig.Spawn p) {
    return String.format(Locale.ROOT, "X %.2f / Y %.2f / Z %.2f", p.x(), p.y(), p.z());
  }

  private void houses(Player p, String id, int page) {
    var c = plugin.arenas.require(id).config;
    var entries =
        c.village().houses().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    Menu m = new Menu(p, Kind.LIST, "Houses · " + id);
    for (int i = page * 36; i < Math.min(entries.size(), (page + 1) * 36); i++) {
      var entry = entries.get(i);
      m.put(
          i - page * 36,
          Material.RED_BED,
          entry.getKey(),
          () -> {
            Menu detail = new Menu(p, Kind.DETAIL, entry.getKey());
            detail.put(
                13,
                Material.ENDER_PEARL,
                "Inspect house",
                () -> {
                  var position = editable(id).config.village().houses().get(entry.getKey());
                  if (position == null) throw new IllegalArgumentException("House removed");
                  plugin.handle(
                      p,
                      p.teleportAsync(
                              new Location(
                                  Bukkit.getWorld(c.worldName()),
                                  position.x(),
                                  position.y(),
                                  position.z(),
                                  position.yaw(),
                                  position.pitch()))
                          .thenAccept(
                              ok -> {
                                if (!ok) throw new IllegalArgumentException("Teleport refused");
                              }));
                },
                coordinates(entry.getValue()));
            detail.put(
                11,
                Material.COMPASS,
                "Move house here",
                () -> {
                  var position = point(p, id);
                  change(
                      id,
                      j ->
                          j.getAsJsonObject("village")
                              .getAsJsonObject("houses")
                              .add(entry.getKey(), ArenaManager.JSON.toJsonTree(position)));
                  houses(p, id, page);
                });
            detail.put(
                15,
                Material.BARRIER,
                "Delete house",
                () ->
                    confirm(
                        p,
                        "Delete house " + entry.getKey(),
                        () -> {
                          change(
                              id,
                              j ->
                                  j.getAsJsonObject("village")
                                      .getAsJsonObject("houses")
                                      .remove(entry.getKey()));
                          houses(p, id, 0);
                        },
                        () -> houses(p, id, page)));
            detail.put(18, Material.ARROW, "Back", () -> houses(p, id, page));
            p.openInventory(detail.inventory);
          },
          coordinates(entry.getValue()));
    }
    paging(m, page, entries.size(), n -> houses(p, id, n));
    m.area(
        new int[] {48, 49, 50},
        Material.EMERALD,
        "plus",
        "Add house here",
        () -> {
          var position = point(p, id);
          prompt(
              p,
              "House name:",
              name -> {
                change(
                    id,
                    j -> {
                      var houses = j.getAsJsonObject("village").getAsJsonObject("houses");
                      if (houses.has(name))
                        throw new IllegalArgumentException("House already exists");
                      houses.add(name, ArenaManager.JSON.toJsonTree(position));
                    });
                houses(p, id, 0);
              });
        });
    m.put(46, Material.ARROW, "Setup", () -> setup(p, id));
    p.openInventory(m.inventory);
  }

  private void doors(Player p, String id, int page) {
    var c = plugin.arenas.require(id).config;
    var entries = c.village().doors();
    Menu m = new Menu(p, Kind.LIST, "Doors · " + id);
    for (int i = page * 36; i < Math.min(entries.size(), (page + 1) * 36); i++) {
      var d = entries.get(i);
      m.put(
          i - page * 36,
          Material.OAK_DOOR,
          d.x() + " / " + d.y() + " / " + d.z(),
          () ->
              confirm(
                  p,
                  "Remove door",
                  () -> {
                    change(
                        id,
                        j -> {
                          var list =
                              new ArrayList<>(
                                  ArenaManager.JSON
                                      .fromJson(j, ArenaConfig.class)
                                      .village()
                                      .doors());
                          list.remove(d);
                          j.getAsJsonObject("village")
                              .add("doors", ArenaManager.JSON.toJsonTree(list));
                        });
                    doors(p, id, 0);
                  },
                  () -> doors(p, id, page)));
    }
    paging(m, page, entries.size(), n -> doors(p, id, n));
    m.area(
        new int[] {48, 49, 50},
        Material.EMERALD,
        "plus",
        "Register targeted door",
        () -> {
          editable(id);
          var block = p.getTargetBlockExact(6);
          if (block == null || !(block.getBlockData() instanceof Door door))
            throw new IllegalArgumentException("Look at a door within 6 blocks");
          if (door.getHalf() == Bisected.Half.TOP) block = block.getRelative(0, -1, 0);
          if (!block.getWorld().getName().equals(c.worldName()))
            throw new IllegalArgumentException("Wrong world");
          var d = new ArenaConfig.Door(block.getX(), block.getY(), block.getZ());
          change(
              id,
              j -> {
                var list =
                    new ArrayList<>(
                        ArenaManager.JSON.fromJson(j, ArenaConfig.class).village().doors());
                if (list.contains(d)) throw new IllegalArgumentException("Door already registered");
                list.add(d);
                j.getAsJsonObject("village").add("doors", ArenaManager.JSON.toJsonTree(list));
              });
          doors(p, id, 0);
        },
        "Close the menu to aim at a door, then reopen");
    m.put(46, Material.ARROW, "Setup", () -> setup(p, id));
    p.openInventory(m.inventory);
  }

  private void paging(Menu m, int page, int size, java.util.function.IntConsumer render) {
    if (page > 0) m.put(45, Material.ARROW, "Previous", () -> render.accept(page - 1));
    if (size > (page + 1) * 36) m.put(53, Material.ARROW, "Next", () -> render.accept(page + 1));
  }

  private void confirm(Player p, String title, Action yes, Action no) {
    Menu m = new Menu(p, Kind.CONFIRM, title);
    m.area(new int[] {23, 24, 25}, Material.RED_WOOL, "trash", "Confirm: " + title, yes);
    m.area(new int[] {19, 20, 21}, Material.ARROW, "back", "Cancel", no);
    p.openInventory(m.inventory);
  }

  private void prompt(Player p, String text, Input input) {
    p.closeInventory();
    inputs.put(p.getUniqueId(), input);
    plugin.message(p, text + " (cancel to exit)");
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void chat(AsyncChatEvent e) {
    Input input = inputs.remove(e.getPlayer().getUniqueId());
    if (input == null) return;
    e.setCancelled(true);
    String text =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(e.message());
    if (text.equalsIgnoreCase("cancel")) return;
    plugin.onPlayer(
        e.getPlayer(),
        () -> {
          try {
            authorize(e.getPlayer());
            input.accept(text);
          } catch (Exception ex) {
            plugin.message(e.getPlayer(), NexusArenaEngine.reason(ex));
          }
          return null;
        });
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    inputs.remove(e.getPlayer().getUniqueId());
    corners.remove(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
    e.setCancelled(true);
    if (e.getWhoClicked() instanceof Player p && m.viewer.equals(p.getUniqueId())) {
      Action action = m.actions.get(e.getRawSlot());
      if (action != null)
        plugin.onPlayer(
            p,
            () -> {
              try {
                authorize(p);
                action.run();
              } catch (Exception ex) {
                plugin.message(p, NexusArenaEngine.reason(ex));
              }
              return null;
            });
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }
}

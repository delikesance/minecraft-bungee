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

  private static final class Menu implements InventoryHolder {
    final UUID viewer;
    final Inventory inventory;
    final Map<Integer, Action> actions = new HashMap<>();

    Menu(Player p, String title) {
      viewer = p.getUniqueId();
      inventory = Bukkit.createInventory(this, 54, Component.text("Vampyuyu · " + title, GOLD));
      for (int i = 0; i < 54; i++)
        inventory.setItem(i, CompassGUI.item(Material.RED_STAINED_GLASS_PANE, " "));
    }

    public Inventory getInventory() {
      return inventory;
    }

    void put(int slot, Material mat, String title, Action action, String... lore) {
      inventory.setItem(slot, CompassGUI.item(mat, title, lore));
      if (action != null) actions.put(slot, action);
    }
  }

  ArenaAdmin(NexusArenaEngine plugin) {
    this.plugin = plugin;
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  private void authorize(Player p) {
    if (!p.hasPermission("nexusarena.admin") || plugin.arenas.locked(p.getUniqueId()))
      throw new IllegalArgumentException("Administrator access outside an arena required");
  }

  private ArenaSession editable(String id) {
    ArenaSession s = plugin.arenas.require(id);
    if (!s.players.isEmpty()
        || s.game != null
        || s.stopping
        || s.finishing
        || plugin.village.pending(id))
      throw new IllegalArgumentException("Stop and empty this arena before editing");
    return s;
  }

  private void change(String id, java.util.function.Consumer<JsonObject> edit) throws Exception {
    synchronized (plugin.arenas) {
      JsonObject json = ArenaManager.JSON.toJsonTree(editable(id).config).getAsJsonObject();
      edit.accept(json);
      plugin.arenas.save(ArenaManager.JSON.fromJson(json, ArenaConfig.class));
    }
  }

  private ArenaConfig.Spawn point(Player p, String id) {
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
            "doors")
        .contains(sub)) return false;
    if (!(sender instanceof Player p))
      throw new IllegalArgumentException("Use this command in game");
    authorize(p);
    switch (sub) {
      case "admin" -> list(p, 0);
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

  private void capture(Player p, int index) {
    Location[] selected = corners.computeIfAbsent(p.getUniqueId(), key -> new Location[2]);
    selected[index] = p.getLocation().clone();
  }

  private ArenaConfig.Bounds bounds(Player p) {
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

  private void list(Player p, int page) {
    var arenas =
        plugin.arenas.all().stream().sorted(Comparator.comparing(s -> s.config.id())).toList();
    Menu m = new Menu(p, "Arenas");
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
    m.put(
        46,
        Material.LIME_WOOL,
        "Set first corner here",
        () -> {
          capture(p, 0);
          list(p, page);
        });
    m.put(
        47,
        Material.LIME_WOOL,
        "Set second corner here",
        () -> {
          capture(p, 1);
          list(p, page);
        });
    m.put(
        49,
        Material.EMERALD,
        "Create arena",
        () ->
            prompt(
                p,
                "New arena ID:",
                id -> {
                  Menu choice = new Menu(p, "Choose preset");
                  choice.put(20, Material.BONE, "Werewolf", () -> create(p, id, "werewolf"));
                  choice.put(
                      24, Material.GRASS_BLOCK, "Free play", () -> create(p, id, "freeplay"));
                  p.openInventory(choice.inventory);
                }),
        "Select both corners, then stand at the spawn");
    p.openInventory(m.inventory);
  }

  private void setup(Player p, String id) {
    var c = plugin.arenas.require(id).config;
    Menu m = new Menu(p, c.name());
    m.put(
        10,
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
        11,
        Material.COMPASS,
        "Set spawn here",
        () -> {
          var position = point(p, id);
          change(id, j -> j.add("spawn", ArenaManager.JSON.toJsonTree(position)));
          setup(p, id);
        });
    m.put(
        12,
        Material.MAP,
        "Use selected bounds",
        () -> {
          var b = bounds(p);
          change(id, j -> j.add("bounds", ArenaManager.JSON.toJsonTree(b)));
          setup(p, id);
        });
    m.put(
        13,
        Material.OAK_DOOR,
        "Village: " + c.village().enabled(),
        () -> {
          change(
              id, j -> j.getAsJsonObject("village").addProperty("enabled", !c.village().enabled()));
          setup(p, id);
        });
    m.put(
        14,
        Material.RED_BED,
        "Houses (" + c.village().houses().size() + ")",
        () -> houses(p, id, 0));
    m.put(
        15,
        Material.BONE,
        "Set wolf meeting here",
        () -> {
          setPoint(p, id, "meeting");
          setup(p, id);
        });
    m.put(
        16,
        Material.SUNFLOWER,
        "Set day point here",
        () -> {
          setPoint(p, id, "daypoint");
          setup(p, id);
        });
    m.put(19, Material.IRON_DOOR, "Controlled doors", () -> doors(p, id, 0));
    String[] numbers = {
      "minPlayers",
      "maxPlayers",
      "durationSeconds",
      "discussionSeconds",
      "voteSeconds",
      "nightSeconds"
    };
    for (int i = 0; i < numbers.length; i++) {
      String key = numbers[i];
      m.put(
          20 + i,
          Material.CLOCK,
          key,
          () ->
              prompt(
                  p,
                  key + ":",
                  value -> {
                    int n = Integer.parseInt(value);
                    change(id, j -> j.addProperty(key, n));
                    setup(p, id);
                  }));
    }
    String[] toggles = {"allowPvP", "allowChests", "allowDoors"};
    for (int i = 0; i < toggles.length; i++) {
      String key = toggles[i];
      m.put(
          28 + i,
          Material.LEVER,
          key,
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
        37,
        Material.LIME_WOOL,
        "Start",
        () -> {
          plugin.arenas.start(id);
          p.closeInventory();
        });
    if (c.preset().equals("werewolf"))
      m.put(
          32,
          Material.NOTE_BLOCK,
          "Immersive ambience: " + c.werewolf().ambienceEnabled(),
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
    m.put(38, Material.RED_WOOL, "Stop & restore", () -> plugin.handle(p, plugin.arenas.stop(id)));
    m.put(
        43,
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
    m.put(49, Material.ARROW, "Back", () -> list(p, 0));
    p.openInventory(m.inventory);
  }

  private void setPoint(Player p, String id, String field) throws Exception {
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
    Menu m = new Menu(p, "Houses · " + id);
    for (int i = page * 36; i < Math.min(entries.size(), (page + 1) * 36); i++) {
      var entry = entries.get(i);
      m.put(
          i - page * 36,
          Material.RED_BED,
          entry.getKey(),
          () -> {
            Menu detail = new Menu(p, entry.getKey());
            detail.put(
                20,
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
                22,
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
                24,
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
            detail.put(49, Material.ARROW, "Back", () -> houses(p, id, page));
            p.openInventory(detail.inventory);
          },
          coordinates(entry.getValue()));
    }
    paging(m, page, entries.size(), n -> houses(p, id, n));
    m.put(
        49,
        Material.EMERALD,
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
    m.put(48, Material.ARROW, "Setup", () -> setup(p, id));
    p.openInventory(m.inventory);
  }

  private void doors(Player p, String id, int page) {
    var c = plugin.arenas.require(id).config;
    var entries = c.village().doors();
    Menu m = new Menu(p, "Doors · " + id);
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
    m.put(
        49,
        Material.EMERALD,
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
    m.put(48, Material.ARROW, "Setup", () -> setup(p, id));
    p.openInventory(m.inventory);
  }

  private void paging(Menu m, int page, int size, java.util.function.IntConsumer render) {
    if (page > 0) m.put(45, Material.ARROW, "Previous", () -> render.accept(page - 1));
    if (size > (page + 1) * 36) m.put(53, Material.ARROW, "Next", () -> render.accept(page + 1));
  }

  private void confirm(Player p, String title, Action yes, Action no) {
    Menu m = new Menu(p, title);
    m.put(22, Material.RED_WOOL, "Confirm: " + title, yes);
    m.put(49, Material.ARROW, "Cancel", no);
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

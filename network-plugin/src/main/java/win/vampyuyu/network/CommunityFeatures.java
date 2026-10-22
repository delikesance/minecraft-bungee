package win.vampyuyu.network;

import com.google.gson.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Creative warps and read-through network profiles. No local balance cache. */
final class CommunityFeatures implements Listener {
  private final JavaPlugin plugin;
  private final WarpStore warps;
  private final String server, bridge, token;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final Map<UUID, Consumer<String>> input = new ConcurrentHashMap<>();
  private final Set<UUID> teleporting = new HashSet<>();
  private static final Gson JSON = new Gson();
  private static final TextColor GOLD = TextColor.color(0xe9d99b),
      IVORY = TextColor.color(0xf5e9cf);

  private interface Action {
    void run() throws Exception;
  }

  private enum Kind {
    LIST(MenuPack.LIST, 54, MenuPack.ROWS_54),
    DETAIL(MenuPack.DETAIL, 27, MenuPack.ROWS_27),
    CONFIRM(MenuPack.CONFIRM, 27, MenuPack.ROWS_27),
    PROFILE(MenuPack.PROFILE, 54, MenuPack.ROWS_54);

    final char glyph;
    final int size;
    final int rows;

    Kind(char glyph, int size, int rows) {
      this.glyph = glyph;
      this.size = size;
      this.rows = rows;
    }
  }

  private MenuPack pack;
  private final MenuVault vault;

  private void show(Player p, Menu m) {
    vault.show(p, m.inventory, m.lower);
  }

  /** Painted menu when the viewer loaded the resource pack, glass-pane chest otherwise. */
  private final class Menu implements MenuVault.Menu {
    final Inventory inventory;
    final UUID viewer;
    final boolean skinned;
    final Map<Integer, Action> actions = new HashMap<>();
    final Map<Integer, ItemStack> lower = new HashMap<>();

    Menu(Player p, Kind kind, String title) {
      viewer = p.getUniqueId();
      skinned = pack != null && pack.active(p);
      if (skinned) {
        inventory =
            Bukkit.createInventory(
                this, kind.size, MenuPack.title(kind.glyph, kind.rows, Component.text(title, GOLD)));
      } else {
        inventory =
            Bukkit.createInventory(this, kind.size, Component.text("Vampyuyu · " + title, GOLD));
        for (int i = 0; i < kind.size; i++)
          inventory.setItem(i, item(Material.RED_STAINED_GLASS_PANE, " "));
      }
    }

    public Inventory getInventory() {
      return inventory;
    }

    /** Navigation lives in the hotbar row (0-8), painted as part of the window. */
    int hot(int i) {
      return MenuVault.hotbar(inventory.getSize(), i);
    }

    void put(int slot, Material material, String title, Action action, String... lore) {
      place(slot, material, null, title, action, lore);
    }

    /** One action over several slots, so a painted button is clickable on its whole surface. */
    void area(int[] slots, Material material, String icon, String title, Action action, String... lore) {
      for (int slot : slots) place(slot, material, icon, title, action, lore);
    }

    void place(int slot, Material material, String icon, String title, Action action, String... lore) {
      ItemStack stack = item(material, title, lore);
      if (skinned)
        MenuPack.icon(stack, icon != null ? icon : MenuPack.iconFor(material, title), false);
      if (slot >= inventory.getSize()) lower.put(slot, stack);
      else inventory.setItem(slot, stack);
      if (action != null) actions.put(slot, action);
    }
  }

  static ItemStack item(Material mat, String title, String... lore) {
    ItemStack item = new ItemStack(mat);
    var meta = item.getItemMeta();
    meta.displayName(Component.text(title, GOLD).decoration(TextDecoration.ITALIC, false));
    meta.lore(
        Arrays.stream(lore)
            .map(s -> Component.text(s, IVORY).decoration(TextDecoration.ITALIC, false))
            .toList());
    item.setItemMeta(meta);
    return item;
  }

  CommunityFeatures(
      JavaPlugin plugin, String server, String bridge, String token, MenuPack pack, MenuVault vault)
      throws Exception {
    this.plugin = plugin;
    this.vault = vault;
    this.pack = pack;
    this.server = server;
    this.bridge = bridge;
    this.token = token;
    warps = new WarpStore(plugin.getDataFolder().toPath().resolve("warps.json"));
    for (String name :
        List.of("warp", "warpadd", "warps", "warptp", "warpadmin", "profil", "moneyadmin"))
      Objects.requireNonNull(plugin.getCommand(name))
          .setExecutor(
              (sender, command, label, args) -> {
                try {
                  if (!(sender instanceof Player p)) {
                    if (name.equals("moneyadmin")) money(null, args);
                    else if (name.equals("warpadmin") && server.equals("creative")) quota(args);
                    else throw new IllegalArgumentException("Use this command in game");
                    return true;
                  }
                  available(p);
                  switch (name) {
                    case "profil" -> profile(p, 0);
                    case "moneyadmin" -> {
                      permission(p, "vampyuyu.money.admin");
                      money(p, args);
                    }
                    default -> {
                      if (!server.equals("creative"))
                        throw new IllegalArgumentException("Warps are available on Creative");
                      switch (name) {
                        case "warp" -> {
                          Location origin = p.getLocation().clone();
                          prompt(
                              p,
                              "New warp name (cancel to exit)",
                              (ActionText) text -> create(p, text, origin));
                        }
                        case "warpadd" -> {
                          require(args.length == 1, "/warpadd <name>");
                          create(p, args[0], p.getLocation().clone());
                        }
                        case "warps" -> listing(p, 0);
                        case "warptp" -> {
                          require(args.length == 1, "/warptp <name>");
                          teleport(p, warps.get(args[0]));
                        }
                        case "warpadmin" -> {
                          permission(p, "vampyuyu.warp.admin");
                          quota(args);
                          say(p, "Quota saved");
                        }
                      }
                    }
                  }
                } catch (Exception e) {
                  sender.sendMessage(Component.text(message(e), NamedTextColor.RED));
                }
                return true;
              });
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
  }

  private static String message(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    return e.getMessage() == null ? "Operation unavailable" : e.getMessage();
  }

  private static void require(boolean condition, String msg) {
    if (!condition) throw new IllegalArgumentException(msg);
  }

  private static void permission(Player p, String node) {
    require(p.hasPermission(node), "Permission required: " + node);
  }

  static boolean locked(Player p) {
    var arena = Bukkit.getPluginManager().getPlugin("NexusArenaEngine");
    if (arena == null) return false;
    if (!arena.isEnabled()) return true;
    try {
      Object manager = arena.getClass().getField("arenas").get(arena);
      return manager == null
          || (boolean)
              manager.getClass().getMethod("locked", UUID.class).invoke(manager, p.getUniqueId());
    } catch (ReflectiveOperationException e) {
      return true;
    }
  }

  private void available(Player p) {
    require(p.isOnline() && !locked(p), "Unavailable during arena participation or recovery");
  }

  private void later(Player p, Action action) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              try {
                available(p);
                action.run();
              } catch (Exception e) {
                say(p, message(e));
              }
            });
  }

  private void say(Player p, String text) {
    p.sendMessage(Component.text(text, IVORY));
  }

  private UUID identity(String name) {
    try {
      return UUID.fromString(name);
    } catch (IllegalArgumentException ignored) {
    }
    for (OfflinePlayer p : Bukkit.getOfflinePlayers())
      if (name.equalsIgnoreCase(p.getName())) return p.getUniqueId();
    throw new IllegalArgumentException("Unknown player; use their UUID");
  }

  private void quota(String[] args) throws Exception {
    require(
        args.length == 3 && args[0].equalsIgnoreCase("limit"),
        "/warpadmin limit <player|uuid> <count>");
    warps.limit(identity(args[1]), Integer.parseInt(args[2]));
  }

  private void create(Player p, String name, Location origin) throws Exception {
    available(p);
    warps.create(
        new WarpStore.Warp(
            name,
            "",
            p.getUniqueId(),
            p.getName(),
            origin.getWorld().getName(),
            origin.getX(),
            origin.getY(),
            origin.getZ(),
            origin.getYaw(),
            origin.getPitch()),
        p.hasPermission("vampyuyu.warp.unlimited"));
    say(p, "Warp " + name + " created");
    listing(p, 0);
  }

  private void prompt(Player p, String message, Consumer<String> callback) {
    p.closeInventory();
    input.put(p.getUniqueId(), callback);
    say(p, message);
  }

  private void prompt(Player p, String message, ActionText callback) {
    prompt(
        p,
        message,
        (Consumer<String>)
            text -> {
              try {
                available(p);
                callback.run(text);
              } catch (Exception e) {
                say(p, message(e));
              }
            });
  }

  private interface ActionText {
    void run(String text) throws Exception;
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void chat(AsyncChatEvent e) {
    if (locked(e.getPlayer())) {
      input.remove(e.getPlayer().getUniqueId());
      return;
    }
    Consumer<String> callback = input.remove(e.getPlayer().getUniqueId());
    if (callback == null) return;
    e.setCancelled(true);
    String text =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(e.message());
    if (!text.equalsIgnoreCase("annuler") && !text.equalsIgnoreCase("cancel"))
      later(e.getPlayer(), () -> callback.accept(text));
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    input.remove(e.getPlayer().getUniqueId());
  }

  private void listing(Player p, int page) {
    List<WarpStore.Warp> list = warps.all();
    Menu m = new Menu(p, Kind.LIST, "Warps · " + (page + 1));
    for (int i = page * 36; i < Math.min(list.size(), (page + 1) * 36); i++) {
      var w = list.get(i);
      m.put(
          i - page * 36,
          Material.ENDER_PEARL,
          w.name(),
          () -> details(p, w.name(), page),
          w.description(),
          "Owner: " + w.ownerName(),
          "Click to visit or manage");
    }
    if (page > 0) m.put(m.hot(6), Material.ARROW, "Previous", () -> listing(p, page - 1));
    if (list.size() > (page + 1) * 36)
      m.put(m.hot(8), Material.ARROW, "Next", () -> listing(p, page + 1));
    m.area(
        new int[] {48, 49, 50},
        Material.EMERALD,
        "plus",
        "Create a warp",
        () -> {
          Location origin = p.getLocation().clone();
          prompt(p, "Warp name:", (ActionText) t -> create(p, t, origin));
        });
    show(p, m);
  }

  private void details(Player p, String name, int page) {
    var w = warps.get(name);
    Menu m = new Menu(p, Kind.DETAIL, w.name());
    m.put(
        13,
        Material.ENDER_PEARL,
        "Teleport",
        () -> teleport(p, warps.get(name)),
        w.description(),
        "Owner: " + w.ownerName());
    if (w.owner().equals(p.getUniqueId()) || p.hasPermission("vampyuyu.warp.admin")) {
      m.put(
          11,
          Material.WRITABLE_BOOK,
          "Edit description",
          () ->
              prompt(
                  p,
                  "Description (240 characters maximum):",
                  (ActionText)
                      t -> {
                        warps.edit(
                            name,
                            p.getUniqueId(),
                            p.hasPermission("vampyuyu.warp.admin"),
                            t,
                            false);
                        details(p, name, page);
                      }));
      m.put(
          15,
          Material.BARRIER,
          "Delete",
          () -> {
            Menu confirm = new Menu(p, Kind.CONFIRM, "Delete " + name);
            confirm.area(
                new int[] {23, 24, 25},
                Material.RED_WOOL,
                "trash",
                "Delete " + name,
                () -> {
                  warps.edit(
                      name, p.getUniqueId(), p.hasPermission("vampyuyu.warp.admin"), "", true);
                  listing(p, 0);
                });
            confirm.area(
                new int[] {19, 20, 21},
                Material.ARROW,
                "back",
                "Cancel",
                () -> details(p, name, page));
            show(p, confirm);
          });
    }
    m.put(m.hot(0), Material.ARROW, "Back", () -> listing(p, page));
    show(p, m);
  }

  private void teleport(Player p, WarpStore.Warp w) {
    available(p);
    World world = Bukkit.getWorld(w.world());
    require(world != null, "World unavailable");
    require(teleporting.add(p.getUniqueId()), "Teleport already in progress");
    Location destination = new Location(world, w.x(), w.y(), w.z(), w.yaw(), w.pitch());
    world
        .getChunkAtAsync(destination)
        .whenComplete(
            (chunk, error) ->
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          try {
                            available(p);
                            if (error != null)
                              throw new IllegalArgumentException("Destination unavailable");
                            var feet = destination.getBlock();
                            var head = feet.getRelative(0, 1, 0);
                            var floor = feet.getRelative(0, -1, 0);
                            require(
                                world.getWorldBorder().isInside(destination)
                                    && destination.getY() > world.getMinHeight()
                                    && destination.getY() < world.getMaxHeight() - 1
                                    && feet.isPassable()
                                    && head.isPassable()
                                    && !feet.isLiquid()
                                    && !head.isLiquid()
                                    && floor.getType().isSolid()
                                    && java.util.stream.Stream.of(feet, head, floor)
                                        .noneMatch(
                                            block ->
                                                Set.of(
                                                        Material.LAVA,
                                                        Material.FIRE,
                                                        Material.SOUL_FIRE,
                                                        Material.MAGMA_BLOCK,
                                                        Material.CACTUS,
                                                        Material.CAMPFIRE,
                                                        Material.SOUL_CAMPFIRE,
                                                        Material.POWDER_SNOW,
                                                        Material.SWEET_BERRY_BUSH,
                                                        Material.WITHER_ROSE,
                                                        Material.NETHER_PORTAL,
                                                        Material.END_PORTAL,
                                                        Material.END_GATEWAY)
                                                    .contains(block.getType())),
                                "Unsafe destination");
                            require(p.teleport(destination), "Teleport refused");
                            p.closeInventory();
                          } catch (Exception e) {
                            say(p, message(e));
                          } finally {
                            teleporting.remove(p.getUniqueId());
                          }
                        }));
  }

  private CompletableFuture<JsonObject> post(String route, Object payload) {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(bridge + "/bridge/" + route))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(payload)))
            .build();
    return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
        .thenApply(
            res -> {
              if (res.statusCode() != 200)
                throw new IllegalArgumentException(
                    "Central service unavailable or operation refused");
              return JsonParser.parseString(res.body()).getAsJsonObject();
            });
  }

  private void profile(Player p, int page) {
    say(p, "Loading profile…");
    post("profile", Map.of("uuid", p.getUniqueId().toString(), "page", page))
        .whenComplete(
            (data, error) ->
                later(
                    p,
                    () -> {
                      if (error != null) {
                        say(p, "Profile unavailable. Please retry later.");
                        return;
                      }
                      Menu m = new Menu(p, Kind.PROFILE, "Profile · " + p.getName());
                      // Painted layout: balance medallion on slot 4, history card on rows 1-4.
                      int slot = m.skinned ? 9 : 0;
                      for (var row : data.getAsJsonArray("history")) {
                        JsonObject r = row.getAsJsonObject();
                        String date =
                            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                                .withZone(ZoneId.of("Europe/Paris"))
                                .format(Instant.ofEpochMilli(r.get("at").getAsLong()));
                        m.place(
                            slot++,
                            Material.BOOK,
                            "book",
                            r.get("event").getAsString(),
                            null,
                            date + " · " + r.get("server").getAsString(),
                            r.get("result").getAsString(),
                            "+"
                                + r.get("gain").getAsLong()
                                + " "
                                + data.get("currency").getAsString());
                      }
                      m.put(
                          m.skinned ? 4 : 49,
                          Material.SUNFLOWER,
                          data.get("balance").getAsLong()
                              + " "
                              + data.get("currency").getAsString(),
                          null,
                          "Global balance · Event history");
                      if (page > 0)
                        m.put(m.hot(6), Material.ARROW, "Previous", () -> profile(p, page - 1));
                      if (data.get("more").getAsBoolean())
                        m.put(m.hot(8), Material.ARROW, "Next", () -> profile(p, page + 1));
                      show(p, m);
                    }));
  }

  private void money(Player p, String[] args) {
    require(
        args.length == 3 && List.of("give", "take", "set").contains(args[0]),
        "/moneyadmin give|take|set <player|uuid> <amount>");
    long amount = Long.parseLong(args[2]);
    require(amount >= 0 && amount <= 1000000000, "Invalid amount");
    post(
            "money",
            Map.of(
                "id",
                UUID.randomUUID().toString(),
                "uuid",
                identity(args[1]).toString(),
                "actor",
                p == null ? "console" : p.getUniqueId().toString(),
                "server",
                server,
                "operation",
                args[0],
                "amount",
                amount))
        .whenComplete(
            (data, error) -> {
              String msg =
                  error == null
                      ? "Balance updated"
                      : "Operation not confirmed; check your profile before retrying";
              if (p != null) later(p, () -> say(p, msg));
              else plugin.getLogger().info(msg);
            });
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
    e.setCancelled(true);
    if (e.getWhoClicked() instanceof Player p && m.viewer.equals(p.getUniqueId())) {
      Action a = m.actions.get(e.getRawSlot());
      if (a != null) later(p, a);
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }
}

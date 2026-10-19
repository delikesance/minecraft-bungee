package win.vampyuyu.lobby;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class LobbySelector extends JavaPlugin implements Listener {
  // Keep the in-game selector aligned with the panel's burgundy/ivory/gold palette.
  private static final TextColor ROSE = TextColor.color(0xd58b8e);
  private static final TextColor IVORY = TextColor.color(0xf5e9cf);
  private static final TextColor GOLD = TextColor.color(0xe9d99b);
  private static final TextColor MUTED = TextColor.color(0xc5a69b);
  private NamespacedKey selectorKey;
  private boolean palace;
  private final Map<UUID, Long> gateCooldown = new ConcurrentHashMap<>();
  private final Map<UUID, GameMode> builders = new ConcurrentHashMap<>();

  public boolean isBuildMode(Player player) {
    return builders.containsKey(player.getUniqueId())
        && player.hasPermission("vampyuyu.lobby.build");
  }

  public void setBuildMode(Player player, boolean enabled) {
    if (enabled) {
      if (!player.hasPermission("vampyuyu.lobby.build"))
        throw new IllegalArgumentException("This player needs OP or the lobby build permission");
      if (java.nio.file.Files.exists(
          getDataFolder()
              .toPath()
              .getParent()
              .resolve("NexusArenaEngine/snapshots")
              .resolve(player.getUniqueId() + ".yml")))
        throw new IllegalArgumentException("Leave the arena before enabling lobby build mode");
      builders.putIfAbsent(player.getUniqueId(), player.getGameMode());
      player.setGameMode(GameMode.CREATIVE);
    } else {
      GameMode previous = builders.remove(player.getUniqueId());
      if (previous != null) player.setGameMode(previous);
    }
    player.sendMessage(
        text(
            enabled
                ? "Lobby build mode enabled. You can build and manage your inventory. Use"
                      + " /lobbybuild off when finished."
                : "Lobby build mode disabled. Visitor protections are active.",
            GOLD));
  }

  private static Component text(String content, TextColor color) {
    return Component.text(content, color).decoration(TextDecoration.ITALIC, false);
  }

  private static ItemStack item(Material material, Component name, Component... lore) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    meta.displayName(name);
    meta.lore(List.of(lore));
    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
    item.setItemMeta(meta);
    return item;
  }

  @Override
  public void onEnable() {
    palace =
        java.nio.file.Files.exists(
            getDataFolder()
                .toPath()
                .toAbsolutePath()
                .getParent()
                .getParent()
                .resolve(".vampyuyu-palace"));
    selectorKey = new NamespacedKey(this, "selector");
    getCommand("lobbybuild")
        .setExecutor(
            (sender, command, label, args) -> {
              if (!(sender instanceof Player player)) {
                sender.sendMessage("Use the panel to enable build mode for a connected player.");
                return true;
              }
              try {
                if (args.length > 1
                    || args.length == 1
                        && !List.of("on", "off")
                            .contains(args[0].toLowerCase(java.util.Locale.ROOT)))
                  throw new IllegalArgumentException("Usage: /lobbybuild [on|off]");
                setBuildMode(
                    player,
                    args.length == 0 ? !isBuildMode(player) : args[0].equalsIgnoreCase("on"));
              } catch (IllegalArgumentException e) {
                player.sendMessage(text(e.getMessage(), ROSE));
              }
              return true;
            });
    getServer().getPluginManager().registerEvents(this, this);
    getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
    for (var world : getServer().getWorlds()) {
      world.getWorldBorder().setCenter(0, 0);
      world.getWorldBorder().setSize(100);
      world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
      world.setGameRule(org.bukkit.GameRule.DO_MOB_LOOT, false);
      world.setGameRule(org.bukkit.GameRule.DO_ENTITY_DROPS, false);
      world.setGameRule(org.bukkit.GameRule.MOB_GRIEFING, false);
      world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
      world.setStorm(false);
      world.setThundering(false);
      for (var entity : world.getEntities()) if (!(entity instanceof Player)) entity.remove();
    }
    getServer()
        .getScheduler()
        .runTaskTimer(
            this,
            () -> {
              for (var world : getServer().getWorlds())
                for (var entity : world.getEntities())
                  if (!(entity instanceof Player)) entity.remove();
              for (Player player : getServer().getOnlinePlayers()) rescue(player);
            },
            20,
            20);
    getServer()
        .getScheduler()
        .runTaskLater(
            this,
            () -> {
              var world = getServer().getWorld("world");
              if (world == null) return;
              for (int side : new int[] {-1, 1}) {
                var state = world.getBlockAt(side * 30, 67, -5).getState();
                if (state instanceof Sign sign) {
                  sign.line(
                      0,
                      text(side < 0 ? "CREATIVE" : "SURVIVAL", GOLD).decorate(TextDecoration.BOLD));
                  sign.line(1, text(side < 0 ? "Build your story" : "Begin an adventure", IVORY));
                  sign.line(2, text("Walk towards the gate", ROSE));
                  sign.line(3, text("or use /menu", MUTED));
                  sign.update(true, false);
                }
              }
            },
            40);
  }

  @EventHandler
  public void join(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    player.setGameMode(GameMode.ADVENTURE);
    player.teleport(player.getWorld().getSpawnLocation());
    player.setFoodLevel(20);
    player.getInventory().clear();
    player.getEnderChest().clear();
    player.setLevel(0);
    player.setExp(0);
    player.setTotalExperience(0);
    ItemStack selector =
        item(
            Material.COMPASS,
            text("World Selector", GOLD).decorate(TextDecoration.BOLD),
            text("Vampyuyu Network", ROSE),
            Component.empty(),
            text("Find your next world.", IVORY),
            text("Right-click to choose", GOLD),
            text("or use /menu at any time.", MUTED));
    ItemMeta meta = selector.getItemMeta();
    meta.getPersistentDataContainer().set(selectorKey, PersistentDataType.BYTE, (byte) 1);
    selector.setItemMeta(meta);
    player.getInventory().setItem(0, selector);
    player.sendMessage(
        text("VAMPYUYU ", ROSE)
            .decorate(TextDecoration.BOLD)
            .append(text("Welcome home. ", IVORY))
            .append(text("Right-click your compass or use /menu to choose a world.", GOLD)));
  }

  @EventHandler
  public void interact(PlayerInteractEvent event) {
    if (isBuildMode(event.getPlayer())) return;
    event.setCancelled(true);
    ItemStack item = event.getItem();
    if (event.getHand() == EquipmentSlot.HAND
        && (event.getAction() == Action.RIGHT_CLICK_AIR
            || event.getAction() == Action.RIGHT_CLICK_BLOCK)
        && item != null
        && item.hasItemMeta()
        && item.getItemMeta()
            .getPersistentDataContainer()
            .has(selectorKey, PersistentDataType.BYTE)) {
      event.getPlayer().performCommand("vampyuyunetwork:menu");
    }
  }

  @EventHandler
  public void click(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player) || !isBuildMode(player))
      event.setCancelled(true);
  }

  @EventHandler
  public void drag(InventoryDragEvent event) {
    if (!(event.getWhoClicked() instanceof Player player) || !isBuildMode(player))
      event.setCancelled(true);
  }

  @EventHandler
  public void drop(PlayerDropItemEvent event) {
    event.setCancelled(true);
  }

  @EventHandler
  public void damage(EntityDamageEvent event) {
    event.setCancelled(true);
  }

  @EventHandler
  public void hunger(FoodLevelChangeEvent event) {
    event.setCancelled(true);
  }

  @EventHandler
  public void blockBreak(BlockBreakEvent event) {
    event.setDropItems(false);
    event.setExpToDrop(0);
    if (!isBuildMode(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler
  public void blockPlace(BlockPlaceEvent event) {
    if (!isBuildMode(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void explosion(EntityExplodeEvent event) {
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void explosion(BlockExplodeEvent event) {
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void burn(BlockBurnEvent event) {
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void ignite(BlockIgniteEvent event) {
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void grief(EntityChangeBlockEvent event) {
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void naturalHostile(CreatureSpawnEvent event) {
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void entitySpawn(EntitySpawnEvent event) {
    if (!(event.getEntity() instanceof Player)) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void pickup(EntityPickupItemEvent event) {
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void projectile(ProjectileLaunchEvent event) {
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void weather(WeatherChangeEvent event) {
    if (event.toWeatherState()) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void thunder(ThunderChangeEvent event) {
    if (event.toThunderState()) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void portal(PlayerPortalEvent event) {
    event.setCancelled(true);
  }

  private void connect(Player player, String server) {
    long now = System.currentTimeMillis();
    if (now - gateCooldown.getOrDefault(player.getUniqueId(), 0L) < 4000) return;
    gateCooldown.put(player.getUniqueId(), now);
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (DataOutputStream data = new DataOutputStream(buffer)) {
        data.writeUTF("Connect");
        data.writeUTF(server);
      }
      player.sendPluginMessage(this, "BungeeCord", buffer.toByteArray());
    } catch (java.io.IOException e) {
      player.sendMessage(text("Use /menu to choose your world.", ROSE));
    }
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    builders.remove(event.getPlayer().getUniqueId());
    gateCooldown.remove(event.getPlayer().getUniqueId());
  }

  @Override
  public void onDisable() {
    for (Player player : getServer().getOnlinePlayers()) {
      GameMode previous = builders.remove(player.getUniqueId());
      if (previous != null) player.setGameMode(previous);
    }
  }

  private void rescue(Player player) {
    var at = player.getLocation();
    if (at.getY() < (palace ? 58 : player.getWorld().getMinHeight()) || Math.abs(at.getX()) > 49 || Math.abs(at.getZ()) > 49) {
      player.setFallDistance(0);
      player.teleport(player.getWorld().getSpawnLocation());
    }
  }

  @EventHandler
  public void move(PlayerMoveEvent event) {
    if (event.getTo() == null) return;
    var to = event.getTo();
    Player player = event.getPlayer();
    double floor = palace ? 58 : player.getWorld().getMinHeight();
    if (to.getY() < floor || Math.abs(to.getX()) > 49 || Math.abs(to.getZ()) > 49) {
      player.setFallDistance(0);
      player.teleport(player.getWorld().getSpawnLocation());
      return;
    }
    if (palace
        && !isBuildMode(player)
        && to.getY() >= 65
        && to.getY() < 73
        && Math.abs(to.getZ()) < 3.5) {
      if (to.getX() <= -29) connect(player, "creative");
      else if (to.getX() >= 29) connect(player, "survival");
    }
  }
}

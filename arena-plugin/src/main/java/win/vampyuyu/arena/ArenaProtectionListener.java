package win.vampyuyu.arena;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Container;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;

public final class ArenaProtectionListener implements Listener {
  private final NexusArenaEngine plugin;

  ArenaProtectionListener(NexusArenaEngine plugin) {
    this.plugin = plugin;
  }

  private ArenaSession arena(Player p) {
    return plugin.arenas.session(p.getUniqueId());
  }

  private boolean inside(ArenaSession s, Location l) {
    return l.getWorld().getName().equals(s.config.worldName())
        && s.config.bounds().contains(l.getX(), l.getY(), l.getZ());
  }

  private boolean eliminated(Player p, ArenaSession s) {
    return s != null && s.game != null && !s.game.alive().contains(p.getUniqueId());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void breakBlock(BlockBreakEvent e) {
    ArenaSession s = arena(e.getPlayer());
    if (s != null
        && (!s.config.allowedBreakBlocks().contains(e.getBlock().getType())
            || !inside(s, e.getBlock().getLocation().add(.5, 0, .5))
            || eliminated(e.getPlayer(), s))) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void place(BlockPlaceEvent e) {
    ArenaSession s = arena(e.getPlayer());
    if (s != null
        && (!s.config.allowedPlaceBlocks().contains(e.getBlock().getType())
            || !inside(s, e.getBlock().getLocation().add(.5, 0, .5))
            || eliminated(e.getPlayer(), s))) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void damage(EntityDamageEvent e) {
    if (!(e.getEntity() instanceof Player victim)) return;
    ArenaSession s = arena(victim);
    if (plugin.arenas.transitioning(victim.getUniqueId())) {
      e.setCancelled(true);
      return;
    }
    Entity source = e instanceof EntityDamageByEntityEvent hit ? hit.getDamager() : null;
    Player attacker =
        source instanceof Player p
            ? p
            : source instanceof Projectile projectile && projectile.getShooter() instanceof Player p
                ? p
                : null;
    ArenaSession other = attacker == null ? null : arena(attacker);
    if (s == null && other == null) return;
    if (s == null
        || attacker != null && (s != other || !s.config.allowPvP() || eliminated(attacker, other))
        || eliminated(victim, s)) {
      e.setCancelled(true);
      return;
    }
    if (s.game instanceof FreeplayGame && e.getFinalDamage() >= victim.getHealth()) {
      e.setCancelled(true);
      if (attacker != null && !attacker.getUniqueId().equals(victim.getUniqueId()))
        s.game.event(attacker.getUniqueId(), "PVP");
      plugin.arenas.respawn(victim);
      return;
    }
    if (s.game == null
        || s.config.preset().equals("werewolf")
        || e.getFinalDamage() >= victim.getHealth()) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void hurtEntity(EntityDamageByEntityEvent e) {
    Entity source = e.getDamager();
    Player p =
        source instanceof Player a
            ? a
            : source instanceof Projectile shot && shot.getShooter() instanceof Player a ? a : null;
    if (p != null && arena(p) != null && !(e.getEntity() instanceof Player)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void interact(PlayerInteractEvent e) {
    if (plugin.arenas.transitioning(e.getPlayer().getUniqueId())) {
      e.setCancelled(true);
      return;
    }
    ArenaSession s = arena(e.getPlayer());
    if (s == null) return;
    if (eliminated(e.getPlayer(), s)) {
      e.setCancelled(true);
      return;
    }
    var block = e.getClickedBlock();
    if (block == null) return;
    if (!inside(s, block.getLocation().add(.5, 0, .5))
        || block.getState() instanceof Container && !s.config.allowChests()
        || block.getBlockData() instanceof Openable && !s.config.allowDoors()
        || e.getItem() != null
            && (e.getItem().getType() == Material.WATER_BUCKET
                || e.getItem().getType() == Material.LAVA_BUCKET
                || e.getItem().getType() == Material.FLINT_AND_STEEL
                || e.getItem().getType() == Material.FIRE_CHARGE)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void open(InventoryOpenEvent e) {
    if (e.getPlayer() instanceof Player p
        && arena(p) != null
        && !(e.getInventory().getHolder() instanceof CompassMenuHolder)
        && !arena(p).config.allowChests()) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void entityInteract(PlayerInteractEntityEvent e) {
    if (arena(e.getPlayer()) != null) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void move(PlayerMoveEvent e) {
    if (plugin.arenas.transitioning(e.getPlayer().getUniqueId())
        && !(e instanceof PlayerTeleportEvent)) {
      e.setTo(e.getFrom());
      return;
    }
    ArenaSession s = arena(e.getPlayer());
    if (s == null || plugin.arenas.teleporting(e.getPlayer().getUniqueId()) || e.getTo() == null)
      return;
    if (!(e instanceof PlayerTeleportEvent)
        && (plugin.village.sleeping(e.getPlayer()) || !s.presentation.isDone())
        && e.hasChangedPosition()) {
      e.setTo(e.getFrom());
      return;
    }
    if (!inside(s, e.getTo())) {
      if (inside(s, e.getFrom())) e.setTo(e.getFrom());
      else if (!(e instanceof PlayerTeleportEvent))
        plugin.handle(e.getPlayer(), plugin.arenas.leaveArena(e.getPlayer()));
      else e.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void teleport(PlayerTeleportEvent e) {
    if (arena(e.getPlayer()) != null && !plugin.arenas.teleporting(e.getPlayer().getUniqueId()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void command(PlayerCommandPreprocessEvent e) {
    if (plugin.arenas.locked(e.getPlayer().getUniqueId())) {
      String command = e.getMessage().trim().toLowerCase(java.util.Locale.ROOT);
      if (!command.equals("/arena")
          && !command.equals("/arena leave")
          && !command.equals("/nexusarenaengine:arena leave")) {
        e.setCancelled(true);
        plugin.message(e.getPlayer(), "Use your compass or /arena leave during a game.");
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void drop(PlayerDropItemEvent e) {
    if (plugin.arenas.locked(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void swap(PlayerSwapHandItemsEvent e) {
    if (plugin.arenas.locked(e.getPlayer().getUniqueId())
        && (plugin.gui.compass(e.getMainHandItem()) || plugin.gui.compass(e.getOffHandItem())))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void pickup(EntityPickupItemEvent e) {
    if (e.getEntity() instanceof Player p && plugin.arenas.locked(p.getUniqueId()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void bucket(PlayerBucketEmptyEvent e) {
    if (arena(e.getPlayer()) != null) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void bucketFill(PlayerBucketFillEvent e) {
    if (arena(e.getPlayer()) != null) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void portal(PlayerPortalEvent e) {
    if (arena(e.getPlayer()) != null) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void food(FoodLevelChangeEvent e) {
    if (e.getEntity() instanceof Player p) {
      ArenaSession s = arena(p);
      if (s != null && (!s.config.preset().equals("freeplay") || !s.config.freeplay().hunger()))
        e.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void scoredBreak(BlockBreakEvent e) {
    ArenaSession s = arena(e.getPlayer());
    if (s != null && s.game != null) s.game.event(e.getPlayer().getUniqueId(), "BLOCK_BREAK");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void scoredPlace(BlockPlaceEvent e) {
    ArenaSession s = arena(e.getPlayer());
    if (s != null && s.game != null) s.game.event(e.getPlayer().getUniqueId(), "BLOCK_PLACE");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void checkpoint(PlayerMoveEvent e) {
    ArenaSession s = arena(e.getPlayer());
    if (s != null && s.game != null && e.getTo() != null && e.hasChangedPosition())
      s.game.visit(
          e.getPlayer().getUniqueId(), e.getTo().getX(), e.getTo().getY(), e.getTo().getZ());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void chat(AsyncChatEvent e) {
    ArenaSession s = arena(e.getPlayer());
    if (s == null) return;
    e.setCancelled(true);
    GamePreset game = s.game;
    UUID from = e.getPlayer().getUniqueId();
    boolean alive = game == null || game.alive().contains(from);
    boolean night = game != null && WerewolfPresentation.secret(game.phase());
    if (night && alive) {
      plugin.message(
          e.getPlayer(), "The village is asleep. Your menu opens when your role wakes up.");
      return;
    }
    Component message =
        Component.text(
                (!alive ? "[Spectators] " : "[Arena] ")
                    + e.getPlayer().getName()
                    + " » ",
                !alive ? NamedTextColor.GRAY : NamedTextColor.WHITE)
            .append(e.message());
    for (UUID target : s.players.keySet()) {
      boolean targetAlive = game == null || game.alive().contains(target);
      if (targetAlive != alive) continue;
      Player p = Bukkit.getPlayer(target);
      if (p != null)
        plugin.onPlayer(
            p,
            () -> {
              p.sendMessage(message);
              return null;
            });
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void villageDoor(org.bukkit.event.player.PlayerInteractEvent e) {
    if (e.getClickedBlock() != null && plugin.village.controlled(e.getClickedBlock()))
      e.setCancelled(true);
  }

  @EventHandler
  public void villageRedstone(org.bukkit.event.block.BlockRedstoneEvent e) {
    if (plugin.village.controlled(e.getBlock())) e.setNewCurrent(e.getOldCurrent());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    plugin.arenas.disconnected(e.getPlayer());
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    Player p = e.getPlayer();
    plugin.onPlayer(
        p,
        () -> {
          if (plugin.arenas.snapshots.exists(p.getUniqueId())) plugin.arenas.recover(p);
          else plugin.bridge.arrived(p);
          return null;
        });
  }
}

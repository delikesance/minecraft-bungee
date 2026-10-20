package win.vampyuyu.arena;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.bukkit.*;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.potion.*;

/** Owns spatial effects; game state remains independent of Bukkit. */
final class VillageController {
  private final NexusArenaEngine plugin;
  private final Path directory;
  private final Set<String> recovering = ConcurrentHashMap.newKeySet();

  record SavedDoor(String world, int x, int y, int z, String material, boolean open) {}

  record Journal(List<SavedDoor> doors) {}

  VillageController(NexusArenaEngine plugin) throws Exception {
    this.plugin = plugin;
    directory = plugin.getDataFolder().toPath().resolve("doors");
    Files.createDirectories(directory);
  }

  private <T> CompletableFuture<T> region(World world, int x, int z, Supplier<T> action) {
    CompletableFuture<T> f = new CompletableFuture<>();
    Bukkit.getRegionScheduler()
        .execute(
            plugin,
            world,
            x >> 4,
            z >> 4,
            () -> {
              try {
                f.complete(action.get());
              } catch (Throwable e) {
                f.completeExceptionally(e);
              }
            });
    return f;
  }

  private Path path(String id) {
    return directory.resolve(id + ".json");
  }

  boolean pending(String id) {
    return Files.exists(path(id)) || recovering.contains(id);
  }

  CompletableFuture<Void> prepare(ArenaSession s) {
    var village = s.config.village();
    if (!village.enabled()) return CompletableFuture.completedFuture(null);
    if (pending(s.config.id()))
      return CompletableFuture.failedFuture(new IllegalArgumentException("Door recovery pending"));
    String missing = village.missing(s.players.size());
    if (missing != null)
      return CompletableFuture.failedFuture(new IllegalArgumentException(missing));
    World w = Bukkit.getWorld(s.config.worldName());
    if (w == null)
      return CompletableFuture.failedFuture(new IllegalArgumentException("World unavailable"));
    List<CompletableFuture<SavedDoor>> reads = new ArrayList<>();
    for (var d : village.doors())
      reads.add(
          region(
              w,
              d.x(),
              d.z(),
              () -> {
                var block = w.getBlockAt(d.x(), d.y(), d.z());
                var upper = block.getRelative(0, 1, 0);
                if (!(block.getBlockData() instanceof Door door)
                    || door.getHalf() != Bisected.Half.BOTTOM
                    || upper.getType() != block.getType()
                    || !(upper.getBlockData() instanceof Door top)
                    || top.getHalf() != Bisected.Half.TOP)
                  throw new IllegalArgumentException("Registered door missing");
                return new SavedDoor(
                    w.getName(), d.x(), d.y(), d.z(), block.getType().name(), door.isOpen());
              }));
    return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new))
        .thenCompose(
            v ->
                plugin.io(
                    () -> {
                      Journal j = new Journal(reads.stream().map(CompletableFuture::join).toList());
                      Path temp = Files.createTempFile(directory, "doors-", ".tmp");
                      try {
                        Files.writeString(temp, ArenaManager.JSON.toJson(j));
                        try (var channel =
                            java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
                          channel.force(true);
                        }
                        Files.move(temp, path(s.config.id()), StandardCopyOption.ATOMIC_MOVE);
                      } finally {
                        Files.deleteIfExists(temp);
                      }
                      return null;
                    }));
  }

  CompletableFuture<Void> restore(String id) {
    if (!recovering.add(id))
      return CompletableFuture.failedFuture(
          new IllegalStateException("Door recovery already running"));
    return plugin
        .io(
            () ->
                Files.exists(path(id))
                    ? ArenaManager.JSON.fromJson(Files.readString(path(id)), Journal.class)
                    : null)
        .thenCompose(
            j -> {
              if (j == null) return CompletableFuture.completedFuture(null);
              List<CompletableFuture<?>> tasks = new ArrayList<>();
              for (var d : j.doors()) {
                World w = Bukkit.getWorld(d.world());
                if (w == null)
                  return CompletableFuture.failedFuture(
                      new IllegalStateException("Door world unavailable"));
                tasks.add(
                    region(
                        w,
                        d.x(),
                        d.z(),
                        () -> {
                          setDoor(w, d.x(), d.y(), d.z(), d.material(), d.open());
                          return null;
                        }));
              }
              return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
            })
        .thenCompose(
            v ->
                plugin.io(
                    () -> {
                      Files.deleteIfExists(path(id));
                      return (Void) null;
                    }))
        .whenComplete((v, e) -> recovering.remove(id));
  }

  void recover() throws Exception {
    try (var files = Files.list(directory)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
        String id = file.getFileName().toString().replaceFirst("\\.json$", "");
        restore(id)
            .exceptionally(
                e -> {
                  plugin
                      .getLogger()
                      .warning("Door journal retained: " + id + ": " + NexusArenaEngine.reason(e));
                  return null;
                });
      }
    }
  }

  private void setDoor(World w, int x, int y, int z, String expected, boolean open) {
    var lower = w.getBlockAt(x, y, z);
    var upper = w.getBlockAt(x, y + 1, z);
    if (!lower.getType().name().equals(expected)
        || upper.getType() != lower.getType()
        || !(lower.getBlockData() instanceof Door a)
        || !(upper.getBlockData() instanceof Door b)
        || a.getHalf() != Bisected.Half.BOTTOM
        || b.getHalf() != Bisected.Half.TOP) return;
    a.setOpen(open);
    b.setOpen(open);
    lower.setBlockData(a, false);
    upper.setBlockData(b, false);
  }

  boolean sleeping(Player p) {
    var s = plugin.arenas.session(p.getUniqueId());
    return s != null
        && s.config.village().enabled()
        && s.game instanceof WerewolfGame game
        && game.sleeping(p.getUniqueId());
  }

  CompletableFuture<Void> present(ArenaSession s, String previous) {
    if (!(s.game instanceof WerewolfGame game)) return CompletableFuture.completedFuture(null);
    String phase = game.phase();
    boolean night =
        Set.of("CUPID", "GUARD", "SEER", "NIGHT", "WITCH", "HUNTER_SHOT").contains(phase);
    List<CompletableFuture<?>> tasks = new ArrayList<>();
    for (UUID id : s.players.keySet()) {
      Player p = Bukkit.getPlayer(id);
      if (p == null) continue;
      boolean asleep = game.sleeping(id);
      CompletableFuture<Void> move = CompletableFuture.completedFuture(null);
      if (s.config.village().enabled() && game.alive().contains(id)) {
        var home = s.homes.get(id);
        boolean returned =
            previous.equals("NIGHT") && !phase.equals("NIGHT") && game.role(id).equals("WEREWOLF");
        if (returned) move = move.thenCompose(v -> teleport(p, s, home));
        ArenaConfig.Spawn target =
            night
                ? (phase.equals("NIGHT") && game.role(id).equals("WEREWOLF")
                    ? s.config.village().meeting()
                    : home)
                : (phase.equals("DISCUSSION") || phase.equals("DAY_VOTE"))
                    ? s.config.village().daypoint()
                    : home;
        if (!returned || !Objects.equals(home, target))
          move = move.thenCompose(v -> teleport(p, s, target));
      }
      tasks.add(
          move.thenCompose(
              v ->
                  plugin.onPlayer(
                      p,
                      () -> {
                        if (s.game != game || s.stopping) return null;
                        if (asleep)
                          p.addPotionEffect(
                              new PotionEffect(
                                  PotionEffectType.BLINDNESS,
                                  PotionEffect.INFINITE_DURATION,
                                  0,
                                  false,
                                  false));
                        else p.removePotionEffect(PotionEffectType.BLINDNESS);
                        p.setPlayerTime(night ? 18000 : 1000, false);
                        return null;
                      })));
    }
    if (s.config.village().enabled()) {
      World w = Bukkit.getWorld(s.config.worldName());
      for (var d : s.config.village().doors())
        tasks.add(
            region(
                w,
                d.x(),
                d.z(),
                () -> {
                  var block = w.getBlockAt(d.x(), d.y(), d.z());
                  setDoor(w, d.x(), d.y(), d.z(), block.getType().name(), !night);
                  return null;
                }));
    }
    return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
  }

  private CompletableFuture<Void> teleport(Player p, ArenaSession s, ArenaConfig.Spawn point) {
    return plugin
        .onPlayer(
            p,
            () -> {
              if (s.stopping || s.game == null) throw new IllegalStateException("Arena stopping");
              World w = Bukkit.getWorld(s.config.worldName());
              if (w == null || point == null)
                throw new IllegalArgumentException("Village destination unavailable");
              return new Location(w, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
            })
        .thenCompose(
            l -> {
              return region(
                  l.getWorld(),
                  l.getBlockX(),
                  l.getBlockZ(),
                  () -> {
                    var feet = l.getBlock();
                    var head = feet.getRelative(0, 1, 0);
                    var floor = feet.getRelative(0, -1, 0);
                    Set<Material> hazards =
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
                            Material.END_GATEWAY);
                    if (!l.getWorld().getWorldBorder().isInside(l)
                        || !feet.isPassable()
                        || !head.isPassable()
                        || feet.isLiquid()
                        || head.isLiquid()
                        || !floor.getType().isSolid()
                        || java.util.stream.Stream.of(feet, head, floor)
                            .anyMatch(block -> hazards.contains(block.getType())))
                      throw new IllegalArgumentException("Unsafe village destination");
                    return l;
                  });
            })
        .thenCompose(
            l -> {
              plugin.arenas.allowTeleport(p.getUniqueId(), true);
              return p.teleportAsync(l);
            })
        .thenAccept(
            ok -> {
              if (!ok) throw new IllegalStateException("Village teleport refused");
            })
        .whenComplete((v, e) -> plugin.arenas.allowTeleport(p.getUniqueId(), false));
  }

  boolean controlled(org.bukkit.block.Block block) {
    for (var s : plugin.arenas.all())
      if (s.game != null
          && s.config.village().enabled()
          && s.config.worldName().equals(block.getWorld().getName()))
        for (var d : s.config.village().doors())
          if (d.x() == block.getX()
              && d.z() == block.getZ()
              && (d.y() == block.getY() || d.y() + 1 == block.getY())) return true;
    return false;
  }
}

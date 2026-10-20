package win.vampyuyu.arena;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class NexusArenaEngine extends JavaPlugin {
  public ArenaManager arenas;
  CompassGUI gui;
  WerewolfAmbience ambience;
  VillageController village;
  ArenaAdmin admin;
  EventRewards rewards;
  RedisManager redis;
  CrossServerBridge bridge;
  String serverId;
  List<Map<String, Object>> worlds = List.of();
  private ArenaHttpApi api;
  private final ExecutorService worker =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(128),
          r -> Thread.ofPlatform().daemon().name("nexus-io").unstarted(r),
          new ThreadPoolExecutor.AbortPolicy());
  private final ScheduledExecutorService clock =
      Executors.newSingleThreadScheduledExecutor(
          r -> Thread.ofPlatform().daemon().name("nexus-game-clock").unstarted(r));

  @Override
  public void onEnable() {
    try {
      Files.createDirectories(getDataFolder().toPath());
      serverId = System.getenv().getOrDefault("SERVER_ID", "creative");
      for (String resource : List.of("presets/werewolf.json", "presets/freeplay.json"))
        if (!Files.exists(getDataFolder().toPath().resolve(resource)))
          saveResource(resource, false);
      worlds =
          Bukkit.getWorlds().stream()
              .map(
                  w -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", w.getName());
                    m.put("minY", w.getMinHeight());
                    m.put("maxY", w.getMaxHeight());
                    var l = w.getSpawnLocation();
                    m.put("spawn", Map.of("x", l.getX(), "y", l.getY(), "z", l.getZ()));
                    return m;
                  })
              .toList();
      arenas = new ArenaManager(this);
      gui = new CompassGUI(this);
      ambience = new WerewolfAmbience(this);
      village = new VillageController(this);
      village.recover();
      admin = new ArenaAdmin(this);
      bridge = new CrossServerBridge(this);
      getServer().getPluginManager().registerEvents(gui, this);
      getServer().getPluginManager().registerEvents(new ArenaProtectionListener(this), this);
      getServer().getPluginManager().registerEvents(bridge, this);
      try {
        redis = new RedisManager(this);
        redis.start();
      } catch (Exception e) {
        getLogger()
            .warning(
                "Redis configuration unavailable: " + reason(e) + "; local arenas remain usable");
      }
      String token =
          Files.readString(
                  Path.of(
                      System.getenv()
                          .getOrDefault(
                              "ROLE_BRIDGE_TOKEN_FILE", "/run/secrets/role_bridge_token")))
              .trim();
      if (token.length() < 32)
        throw new IllegalArgumentException("Panel bridge token missing or too short");
      rewards = new EventRewards(this, token);
      api = new ArenaHttpApi(this, token);
      Objects.requireNonNull(getCommand("eventadmin")).setExecutor(admin::rewards);
      Objects.requireNonNull(getCommand("arena")).setExecutor(this::command);
      getCommand("arena")
          .setTabCompleter(
              (sender, cmd, alias, args) ->
                  args.length == 1
                      ? List.of("list", "join", "leave", "status")
                      : args.length == 2 && args[0].equals("join")
                          ? arenas.all().stream().map(s -> s.config.id()).toList()
                          : List.of());
      clock.scheduleAtFixedRate(
          () -> {
            try {
              arenas.tick();
              for (ArenaSession session : arenas.all()) ambience.tick(session);
              if (System.currentTimeMillis() / 1000 % 5 == 0) rewards.flush();
              for (Player p : Bukkit.getOnlinePlayers())
                onPlayer(
                    p,
                    () -> {
                      gui.refresh(p);
                      return null;
                    });
            } catch (Exception e) {
              getLogger().warning("Arena clock: " + reason(e));
            }
          },
          1,
          1,
          TimeUnit.SECONDS);
      for (Player p : Bukkit.getOnlinePlayers()) arenas.recover(p);
      getLogger()
          .info(
              "Arena engine ready. Configure arenas in the panel; no world is created or modified"
                  + " automatically.");
    } catch (Exception e) {
      getLogger().log(java.util.logging.Level.SEVERE, "Arena engine startup failed", e);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  public <T> CompletableFuture<T> io(Callable<T> task) {
    CompletableFuture<T> result = new CompletableFuture<>();
    try {
      worker.execute(
          () -> {
            try {
              result.complete(task.call());
            } catch (Throwable e) {
              result.completeExceptionally(e);
            }
          });
    } catch (RejectedExecutionException e) {
      result.completeExceptionally(
          new IllegalArgumentException("Arena operation queue is full; retry shortly"));
    }
    return result;
  }

  public <T> CompletableFuture<T> onPlayer(Player p, Callable<T> task) {
    CompletableFuture<T> result = new CompletableFuture<>();
    try {
      boolean accepted =
          p.getScheduler()
              .execute(
                  this,
                  () -> {
                    try {
                      if (!p.isOnline()) throw new IllegalArgumentException("Player disconnected");
                      result.complete(task.call());
                    } catch (Throwable e) {
                      result.completeExceptionally(e);
                    }
                  },
                  () ->
                      result.completeExceptionally(
                          new IllegalArgumentException("Player disconnected")),
                  1);
      if (!accepted)
        result.completeExceptionally(new IllegalArgumentException("Player disconnected"));
    } catch (Exception e) {
      result.completeExceptionally(e);
    }
    return result;
  }

  public void message(Player p, String message) {
    onPlayer(
        p,
        () -> {
          p.sendMessage(Component.text(message, NamedTextColor.GOLD));
          return null;
        });
  }

  public void handle(Player p, CompletableFuture<?> future) {
    future.exceptionally(
        e -> {
          message(p, reason(e));
          return null;
        });
  }

  static String reason(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    return e.getMessage() == null ? "Arena operation failed" : e.getMessage();
  }

  private boolean command(CommandSender sender, Command command, String label, String[] args) {
    try {
      String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
      if (sub.equals("list")) {
        sender.sendMessage(
            Component.text(
                "Arenas: "
                    + String.join(
                        ", ",
                        arenas.all().stream()
                            .map(s -> s.config.id() + " (" + s.phase() + ")")
                            .toList()),
                NamedTextColor.GOLD));
        return true;
      }
      if (Set.of("join", "leave", "status").contains(sub)) {
        if (!(sender instanceof Player p))
          throw new IllegalArgumentException("This command requires a player");
        if (sub.equals("join")) {
          if (args.length != 2)
            throw new IllegalArgumentException("Usage: /arena join <id or server:id>");
          handle(p, bridge.join(p, args[1]));
        } else if (sub.equals("leave")) handle(p, arenas.leaveArena(p));
        else if (arenas.session(p.getUniqueId()) != null) gui.open(p);
        else message(p, "Use /arena list and /arena join <id>.");
        return true;
      }
      if (!sender.hasPermission("nexusarena.admin"))
        throw new IllegalArgumentException("Arena administrator permission required");
      if (admin.command(sender, sub, args)) return true;
      CompletableFuture<?> op =
          switch (sub) {
            case "reload" ->
                io(
                    () -> {
                      arenas.reload();
                      return null;
                    });
            case "start" -> {
              if (args.length != 2) throw new IllegalArgumentException("Usage: /arena start <id>");
              yield io(
                  () -> {
                    arenas.start(args[1]);
                    return null;
                  });
            }
            case "stop" -> {
              if (args.length != 2) throw new IllegalArgumentException("Usage: /arena stop <id>");
              yield arenas.stop(args[1]);
            }
            case "announce" -> {
              if (args.length < 2)
                throw new IllegalArgumentException("Usage: /arena announce <id> [message]");
              yield bridge.announce(
                  args[1],
                  args.length > 2
                      ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                      : "Join " + arenas.require(args[1]).config.name() + "!");
            }
            default ->
                throw new IllegalArgumentException(
                    "Use /arena list, join, leave, status, start, stop, announce or reload");
          };
      op.whenComplete(
          (v, e) -> {
            if (sender instanceof Player p)
              message(p, e == null ? "Arena operation completed." : reason(e));
            else getLogger().info(e == null ? "Arena operation completed." : reason(e));
          });
    } catch (Exception e) {
      sender.sendMessage(Component.text(reason(e), NamedTextColor.RED));
    }
    return true;
  }

  @Override
  public void onDisable() {
    if (api != null) api.close();
    clock.shutdownNow();
    if (redis != null) redis.close();
    if (arenas != null) {
      for (ArenaSession session : arenas.all()) session.ambienceEpoch++;
      // Paper shutdown runs on its main thread. Folia snapshots are intentionally
      // retained for recovery on login; never mutate foreign-region entities here.
      boolean folia;
      try {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
        folia = true;
      } catch (ClassNotFoundException e) {
        folia = false;
      }
      if (!folia)
        for (Player p : Bukkit.getOnlinePlayers())
          if (arenas.snapshots.exists(p.getUniqueId()))
            try {
              var y = arenas.snapshots.read(p.getUniqueId());
              arenas.allowTeleport(p.getUniqueId(), true);
              if (!p.teleport(arenas.snapshots.origin(y)))
                throw new IllegalStateException("Return teleport was refused");
              ambience.clearNow(p, arenas.session(p.getUniqueId()));
              arenas.snapshots.apply(p, y);
              p.saveData();
              arenas.snapshots.delete(p.getUniqueId());
            } catch (Exception e) {
              getLogger().warning("Snapshot retained for next-login recovery: " + p.getUniqueId());
            } finally {
              arenas.allowTeleport(p.getUniqueId(), false);
            }
    }
    worker.shutdown();
  }
}

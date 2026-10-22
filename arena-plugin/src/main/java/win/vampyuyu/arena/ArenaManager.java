package win.vampyuyu.arena;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;

public final class ArenaManager {
  private final NexusArenaEngine plugin;
  private final Path configs;
  public final PlayerSnapshotManager snapshots;
  private final Map<String, ArenaPreset> presets = new ConcurrentHashMap<>();
  private final Set<UUID> recovery = ConcurrentHashMap.newKeySet();
  private final Map<String, ArenaSession> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, ArenaSession> members = new ConcurrentHashMap<>();
  private final Map<UUID, CompletableFuture<Void>> joining = new ConcurrentHashMap<>();
  private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
  private final Set<UUID> teleports = ConcurrentHashMap.newKeySet();
  public static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

  ArenaManager(NexusArenaEngine plugin) throws Exception {
    this.plugin = plugin;
    registerPreset(new WerewolfPreset());
    registerPreset(
        new ArenaPreset() {
          public String id() {
            return "freeplay";
          }

          public GamePreset create(ArenaConfig c, Collection<UUID> ids) {
            return new FreeplayGame(ids, c.freeplay());
          }
        });
    configs = plugin.getDataFolder().toPath().resolve("arenas");
    Files.createDirectories(configs);
    snapshots = new PlayerSnapshotManager(plugin);
    reload();
  }

  public void registerPreset(ArenaPreset preset) {
    if (presets.putIfAbsent(preset.id(), preset) != null)
      throw new IllegalArgumentException("Preset already registered");
  }

  public synchronized void reload() throws Exception {
    if (!members.isEmpty()
        || !busy.isEmpty()
        || sessions.values().stream().anyMatch(s -> s.finishing))
      throw new IllegalArgumentException("Stop occupied arenas before reloading presets");
    Map<String, ArenaSession> next = new LinkedHashMap<>();
    try (var files = Files.list(configs)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
        if (Files.isSymbolicLink(file) || Files.size(file) > 65536)
          throw new IOException("Invalid arena configuration file");
        ArenaConfig c = JSON.fromJson(Files.readString(file), ArenaConfig.class);
        validate(c, next.values());
        if (!file.getFileName().toString().equals(c.id() + ".json")
            || next.put(c.id(), new ArenaSession(c)) != null)
          throw new IllegalArgumentException("Duplicate or mismatched arena ID");
      }
    }
    sessions.clear();
    sessions.putAll(next);
  }

  private void validate(ArenaConfig c, Collection<ArenaSession> against) {
    if (c == null) throw new IllegalArgumentException("Arena configuration required");
    if (!presets.containsKey(c.preset()))
      throw new IllegalArgumentException("Unregistered game preset");
    World w = Bukkit.getWorld(c.worldName());
    if (w == null) throw new IllegalArgumentException("Choose a loaded world");
    if (!c.preset().equals("werewolf") && c.village().enabled())
      throw new IllegalArgumentException("Village mode requires Werewolf");
    if (c.preset().equals("freeplay") && c.freeplay().objective().equals("PVP") && !w.getPVP())
      throw new IllegalArgumentException(
          "This world has PvP disabled. Enable it in Server settings or choose another world.");
    if (c.bounds().minY() < w.getMinHeight() || c.bounds().maxY() > w.getMaxHeight())
      throw new IllegalArgumentException("Y bounds exceed world height");
    for (var s : against)
      if (!s.config.id().equals(c.id())
          && s.config.worldName().equals(c.worldName())
          && s.config.bounds().overlaps(c.bounds()))
        throw new IllegalArgumentException("Arena overlaps " + s.config.name());
  }

  public synchronized void save(ArenaConfig c) throws IOException {
    validate(c, sessions.values());
    ArenaSession old = sessions.get(c.id());
    if (old != null
        && (!old.players.isEmpty()
            || old.stopping
            || old.finishing
            || old.game != null
            || plugin.village.pending(c.id())))
      throw new IllegalArgumentException(
          "Stop this arena and wait for restoration before editing it");
    Path temp = Files.createTempFile(configs, "arena-", ".tmp");
    try {
      Files.writeString(temp, JSON.toJson(c));
      Files.move(
          temp,
          configs.resolve(c.id() + ".json"),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
    sessions.put(c.id(), new ArenaSession(c));
  }

  public synchronized void delete(String id) throws IOException {
    ArenaSession s = require(id);
    if (!s.players.isEmpty()
        || s.stopping
        || s.finishing
        || s.game != null
        || plugin.village.pending(id))
      throw new IllegalArgumentException("Stop this arena before deleting it");
    Files.delete(configs.resolve(s.config.id() + ".json"));
    sessions.remove(id);
  }

  public ArenaSession require(String id) {
    ArenaSession s = sessions.get(id);
    if (s == null) throw new IllegalArgumentException("Unknown arena");
    return s;
  }

  public ArenaSession session(UUID id) {
    return members.get(id);
  }

  /** Living players cannot walk out of a running werewolf game. */
  public boolean leaveBlocked(UUID id) {
    ArenaSession s = members.get(id);
    return s != null
        && s.game instanceof WerewolfGame game
        && !s.stopping
        && !s.finishing
        && game.winner().isEmpty()
        && game.alive().contains(id);
  }

  public boolean locked(UUID id) {
    return members.containsKey(id) || busy.contains(id) || recovery.contains(id);
  }

  public boolean transitioning(UUID id) {
    return busy.contains(id) || recovery.contains(id);
  }

  void allowTeleport(UUID id, boolean allow) {
    if (allow) teleports.add(id);
    else teleports.remove(id);
  }

  public boolean teleporting(UUID id) {
    return teleports.contains(id);
  }

  public Collection<ArenaSession> all() {
    return List.copyOf(sessions.values());
  }

  public CompletableFuture<Void> joinArena(Player p, String id) {
    UUID uuid = p.getUniqueId();
    ArenaSession s;
    synchronized (this) {
      s = require(id);
      if (locked(uuid) || snapshots.exists(uuid))
        return CompletableFuture.failedFuture(
            new IllegalArgumentException(
                "Leave your current arena or restore your saved inventory first"));
      if (s.stopping || s.finishing || s.game != null || s.players.size() >= s.config.maxPlayers())
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("Arena is full or already playing"));
      members.put(uuid, s);
      s.players.put(uuid, p.getName());
      busy.add(uuid);
    }
    CompletableFuture<Void> future =
        plugin
            .onPlayer(
                p,
                () -> {
                  // An admin menu may hold the real inventory: hand it back before the snapshot.
                  if (plugin.admin != null) plugin.admin.vault.release(p);
                  return snapshots.capture(p);
                })
            .thenCompose(
                snapshot ->
                    plugin.io(
                        () -> {
                          snapshots.write(uuid, snapshot);
                          return null;
                        }))
            .thenCompose(
                v ->
                    plugin.onPlayer(
                        p,
                        () -> {
                          if (!p.isOnline() || s.stopping || s.finishing)
                            throw new IllegalArgumentException(
                                "Joining was interrupted; original state will be restored");
                          p.getInventory().clear();
                          p.getEnderChest().clear();
                          for (var effect : p.getActivePotionEffects())
                            p.removePotionEffect(effect.getType());
                          p.setGameMode(s.config.gameMode());
                          p.setAllowFlight(false);
                          p.setFlying(false);
                          p.setInvulnerable(false);
                          p.setFoodLevel(20);
                          p.setFireTicks(0);
                          p.setFallDistance(0);
                          p.setLevel(0);
                          p.setExp(0);
                          p.setTotalExperience(0);
                          teleports.add(uuid);
                          return s.config.location();
                        }))
            .thenCompose(location -> p.teleportAsync(location))
            .thenCompose(
                ok ->
                    plugin.onPlayer(
                        p,
                        () -> {
                          teleports.remove(uuid);
                          if (!ok) throw new IllegalArgumentException("Arena teleport was refused");
                          WorldBorder border = Bukkit.createWorldBorder();
                          var b = s.config.bounds();
                          border.setCenter((b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2);
                          border.setSize(Math.max(b.maxX() - b.minX(), b.maxZ() - b.minZ()));
                          border.setWarningDistance(0);
                          border.setDamageAmount(0);
                          p.setWorldBorder(border);
                          plugin.gui.giveCompass(p);
                          if (s.config.preset().equals("freeplay")) {
                            var rules = s.config.freeplay();
                            p.setAllowFlight(rules.allowFlight());
                            if (rules.personalTime() >= 0)
                              p.setPlayerTime(rules.personalTime(), false);
                            if (!rules.weather().equals("WORLD"))
                              p.setPlayerWeather(WeatherType.valueOf(rules.weather()));
                            for (var item : rules.kit().entrySet())
                              p.getInventory()
                                  .addItem(
                                      new org.bukkit.inventory.ItemStack(
                                          Material.matchMaterial(item.getKey()), item.getValue()));
                            for (var effect : rules.effects().entrySet())
                              p.addPotionEffect(
                                  new org.bukkit.potion.PotionEffect(
                                      org.bukkit.potion.PotionEffectType.getByKey(
                                          NamespacedKey.minecraft(effect.getKey())),
                                      s.config.durationSeconds() * 20,
                                      effect.getValue() - 1));
                            p.sendMessage(
                                Component.text(rules.instructions(), NamedTextColor.GOLD));
                          }
                          p.sendMessage(
                              Component.text(
                                  "Joined "
                                      + s.config.name()
                                      + ". Your belongings are safely stored.",
                                  NamedTextColor.GOLD));
                          return (Void) null;
                        }));
    joining.put(uuid, future);
    future.whenComplete(
        (v, e) -> {
          joining.remove(uuid);
          busy.remove(uuid);
          teleports.remove(uuid);
          if (e != null) {
            if (snapshots.exists(uuid) && p.isOnline()) leaveArena(p);
            else {
              members.remove(uuid);
              s.players.remove(uuid);
            }
          }
        });
    return future;
  }

  public CompletableFuture<Void> leaveArena(Player p) {
    UUID uuid = p.getUniqueId();
    CompletableFuture<Void> entering = joining.get(uuid);
    if (entering != null && !entering.isDone())
      return entering.handle((v, e) -> null).thenCompose(v -> leaveArena(p));
    if (!busy.add(uuid))
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Player restoration is already in progress"));
    if (!snapshots.exists(uuid)) {
      busy.remove(uuid);
      remove(uuid);
      return CompletableFuture.completedFuture(null);
    }
    teleports.add(uuid);
    ArenaSession leaving = members.get(uuid);
    CompletableFuture<Void> restore =
        plugin
            .onPlayer(
                p,
                () -> {
                  plugin.ambience.clearNow(p, leaving);
                  return null;
                })
            .thenCompose(v -> snapshots.restore(p));
    restore.whenComplete(
        (v, e) -> {
          teleports.remove(uuid);
          busy.remove(uuid);
          if (e == null) {
            recovery.remove(uuid);
            remove(uuid);
            plugin.message(p, "Your original inventory, stats and position have been restored.");
          } else {
            recovery.add(uuid);
            plugin.message(p, "Restoration is pending: " + NexusArenaEngine.reason(e));
            plugin
                .getLogger()
                .warning("Snapshot retained for " + uuid + ": " + NexusArenaEngine.reason(e));
          }
        });
    return restore;
  }

  public void recover(Player p) {
    if (snapshots.exists(p.getUniqueId())) {
      recovery.add(p.getUniqueId());
      leaveArena(p);
    }
  }

  public synchronized void disconnected(Player p) {
    UUID id = p.getUniqueId();
    ArenaSession s = members.get(id);
    // Restore items before Minecraft saves the departing entity, but keep the journal
    // until a successful return teleport on the next login.
    if (s != null)
      try {
        if (snapshots.exists(id)) snapshots.apply(p, snapshots.read(id));
      } catch (Exception e) {
        plugin.getLogger().warning("Disconnect snapshot retained for " + id);
      }
    remove(id);
    teleports.remove(id);
  }

  private synchronized void remove(UUID id) {
    if (plugin.gui != null) plugin.gui.forget(id);
    ArenaSession s = members.remove(id);
    if (s != null) {
      if (s.game != null && !s.stopping) s.abandoned.add(id);
      s.players.remove(id);
      if (s.game != null) s.game.remove(id);
      if (!s.bots.isEmpty() && s.players.keySet().stream().allMatch(s.bots::contains)) {
        stop(s.config.id(), "PRACTICE");
      }
      if (s.stopping && s.players.isEmpty()) {
        s.stopping = false;
        s.game = null;
      }
    }
  }

  public CompletableFuture<Void> startPractice(String id, int count, Player joining) {
    ArenaSession s;
    boolean needsJoin;
    synchronized (this) {
      s = require(id);
      needsJoin = joining != null && !s.players.containsKey(joining.getUniqueId());
      int total = s.players.size() + count + (needsJoin ? 1 : 0);
      if (!s.config.preset().equals("werewolf")
          || s.game != null
          || s.stopping
          || s.finishing
          || !s.bots.isEmpty())
        throw new IllegalArgumentException("Choose a waiting Werewolf arena for practice");
      if (count < 1 || count > 44 || total < s.config.minPlayers() || total > s.config.maxPlayers())
        throw new IllegalArgumentException(
            "Real and practice players together must fit the arena player limits");
      if (s.players.isEmpty() && joining == null)
        throw new IllegalArgumentException("Choose a connected player or join the arena first");
    }
    return (needsJoin ? joinArena(joining, id) : CompletableFuture.<Void>completedFuture(null))
        .thenCompose(
            ignored ->
                plugin.io(
                    () -> {
                      synchronized (this) {
                        if (require(id) != s
                            || s.game != null
                            || s.stopping
                            || s.finishing
                            || s.players.isEmpty()
                            || s.players.size() + count > s.config.maxPlayers())
                          throw new IllegalArgumentException(
                              "Arena changed; refresh before starting practice");
                        try {
                          for (int i = 1; i <= count; i++) {
                            UUID bot = UUID.randomUUID();
                            s.bots.add(bot);
                            s.players.put(bot, "[Bot] Guest " + i);
                          }
                          start(id);
                        } catch (RuntimeException error) {
                          clearBots(s);
                          throw error;
                        }
                      }
                      return null;
                    }));
  }

  private void clearBots(ArenaSession s) {
    for (UUID bot : s.bots) s.players.remove(bot);
    s.bots.clear();
  }

  private long phaseMillis(ArenaSession s) {
    return Math.min(s.game.seconds(), s.bots.isEmpty() ? s.config.durationSeconds() : 15) * 1000L;
  }

  public synchronized void start(String id) {
    ArenaSession s = require(id);
    if (s.stopping
        || s.finishing
        || s.game != null
        || s.players.size() < s.config.minPlayers()
        || s.players.keySet().stream().anyMatch(busy::contains))
      throw new IllegalArgumentException("Wait for enough fully joined players before starting");
    if (plugin.village.pending(id)) throw new IllegalArgumentException("Door recovery pending");
    s.config.village().requireCapacity(s.players.size());
    s.eventId = UUID.randomUUID().toString();
    s.ambienceEpoch++;
    s.ambienceNight = false;
    s.ambiencePhase = "";
    s.revealed.clear();
    s.ambienceFinale = false;
    s.resultQueued = false;
    s.finishing = false;
    s.practice = !s.bots.isEmpty();
    s.roster.clear();
    s.abandoned.clear();
    s.homes.clear();
    for (var entry : s.players.entrySet())
      if (!s.bots.contains(entry.getKey())) s.roster.put(entry.getKey(), entry.getValue());
    List<ArenaConfig.Spawn> houses = new ArrayList<>(s.config.village().houses().values());
    Collections.shuffle(houses);
    int houseIndex = 0;
    if (s.config.village().enabled())
      for (UUID player : s.players.keySet()) s.homes.put(player, houses.get(houseIndex++));
    s.game = presets.get(s.config.preset()).create(s.config, Set.copyOf(s.players.keySet()));
    s.startedAt = System.currentTimeMillis();
    s.phaseEndsAt = s.startedAt + phaseMillis(s);
    s.observedPhase = s.game.phase();
    s.presentation =
        plugin
            .rewards
            .begin(s)
            .thenCompose(v -> plugin.village.prepare(s))
            .thenCompose(v -> plugin.village.present(s, "WAITING"))
            .thenRun(
                () -> {
                  if (s.game != null) s.phaseEndsAt = System.currentTimeMillis() + phaseMillis(s);
                })
            .thenCompose(v -> plugin.ambience.enter(s, true));
    s.presentation.whenComplete(
        (v, e) -> {
          if (e != null) {
            plugin.getLogger().warning("Village start failed: " + NexusArenaEngine.reason(e));
            tell(s, "The game could not start: " + NexusArenaEngine.reason(e));
            stop(s.config.id(), "INTERRUPTED");
          }
        });
    update(
        s,
        s.game instanceof WerewolfGame
            ? null
            : "The game has begun. Open your compass to see your secret role.");
  }

  public CompletableFuture<Void> stop(String id) {
    return stop(id, "STOPPED");
  }

  private CompletableFuture<Void> stop(String id, String reason) {
    ArenaSession current = require(id);
    CompletableFuture<Void> completion;
    synchronized (this) {
      if (current.finishing) return current.stopFuture;
      current.finishing = true;
      current.ambienceEpoch++;
      completion = new CompletableFuture<>();
      current.stopFuture = completion;
    }
    current
        .presentation
        .handle((ignored, error) -> (Void) null)
        .thenCompose(
            v ->
                current.game == null
                    ? CompletableFuture.completedFuture(null)
                    : plugin.rewards.enqueue(current, reason))
        .thenCompose(v -> plugin.ambience.conclude(current, reason))
        .thenCompose(v -> stopPlayers(id))
        .thenCompose(v -> plugin.village.restore(id))
        .whenComplete(
            (v, e) -> {
              current.finishing = false;
              if (e != null) {
                plugin.getLogger().warning("Arena stop pending: " + NexusArenaEngine.reason(e));
                completion.completeExceptionally(e);
              } else completion.complete(null);
            });
    return completion;
  }

  private CompletableFuture<Void> stopPlayers(String id) {
    ArenaSession s;
    List<Player> players = new ArrayList<>();
    synchronized (this) {
      s = require(id);
      s.stopping = true;
      clearBots(s);
      for (UUID uuid : s.players.keySet()) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) players.add(p);
        else remove(uuid);
      }
      if (s.players.isEmpty()) {
        s.stopping = false;
        s.game = null;
      }
    }
    return CompletableFuture.allOf(
        players.stream().map(this::leaveArena).toArray(CompletableFuture[]::new));
  }

  public synchronized void tick() {
    long now = System.currentTimeMillis();
    for (ArenaSession s : sessions.values()) {
      if (s.stopping || s.finishing || s.game == null || !s.presentation.isDone()) continue;
      if (now - s.startedAt >= s.config.durationSeconds() * 1000L) s.game.finish();
      if (!s.game.phase().equals(s.observedPhase)) {
        phaseChanged(s);
        continue;
      }
      if (now - s.startedAt >= s.config.durationSeconds() * 1000L || !s.game.winner().isEmpty()) {
        update(
            s,
            s.game instanceof WerewolfGame
                ? null
                : s.game.winner().isEmpty()
                    ? "Time is up. Returning to your original position."
                    : "Winner: " + s.game.winner() + ". Returning to your original position.");
        stop(s.config.id(), "COMPLETED");
        continue;
      }
      if (now >= s.phaseEndsAt) {
        String before = s.game.phase();
        if (s.game instanceof WerewolfGame werewolf && !s.bots.isEmpty())
          werewolf.simulateTurn(Set.copyOf(s.bots), new Random());
        if (s.game.phase().equals(before)) s.game.advance();
        phaseChanged(s);
      }
    }
  }

  public synchronized void phaseChanged(ArenaSession s) {
    // Notices can come from a click, a bot or a timeout (e.g. Cupid running out of time).
    if (plugin.gui != null) plugin.gui.deliverNotices(s);
    if (s.game == null || s.stopping || s.finishing || s.game.phase().equals(s.observedPhase))
      return;
    String previous = s.observedPhase;
    s.ambienceEpoch++;
    s.observedPhase = s.game.phase();
    s.phaseEndsAt = System.currentTimeMillis() + phaseMillis(s);
    CompletableFuture<Void> previousPresentation = s.presentation;
    s.presentation =
        previousPresentation
            .thenCompose(v -> plugin.village.present(s, previous))
            .thenRun(
                () -> {
                  if (s.game != null) s.phaseEndsAt = System.currentTimeMillis() + phaseMillis(s);
                })
            .thenCompose(v -> plugin.ambience.enter(s, false));
    s.presentation.whenComplete(
        (v, e) -> {
          if (e != null) {
            plugin.getLogger().warning("Village transition failed: " + NexusArenaEngine.reason(e));
            tell(s, "The game was interrupted: " + NexusArenaEngine.reason(e));
            stop(s.config.id(), "INTERRUPTED");
          }
        });
    update(
        s,
        s.game instanceof WerewolfGame
            ? null
            : "Phase: " + s.game.phase() + ". Open your compass for the current actions.");
  }

  /** Tells every real participant why the game ended, before they are restored. */
  private void tell(ArenaSession s, String message) {
    for (UUID uuid : s.players.keySet()) {
      Player p = Bukkit.getPlayer(uuid);
      if (p != null) plugin.message(p, message);
    }
  }

  private void update(ArenaSession s, String message) {
    if (message != null)
      for (var entry : s.players.entrySet())
        message = message.replace(entry.getKey().toString(), entry.getValue());
    final String announcement = message;
    for (UUID uuid : s.players.keySet()) {
      Player p = Bukkit.getPlayer(uuid);
      if (p != null)
        plugin.onPlayer(
            p,
            () -> {
              if (announcement != null)
                p.sendMessage(Component.text(announcement, NamedTextColor.WHITE));
              if (s.game != null && !s.game.alive().contains(uuid)) {
                if (s.game instanceof WerewolfGame wolf && wolf.actors().contains(uuid)) {
                  // A pending hunter must still be able to click the shot menu.
                  p.setGameMode(GameMode.ADVENTURE);
                  p.setAllowFlight(true);
                  p.setFlying(true);
                } else p.setGameMode(GameMode.SPECTATOR);
              }
              if (p.getOpenInventory().getTopInventory().getHolder() instanceof CompassMenuHolder)
                plugin.gui.open(p);
              return null;
            });
    }
  }

  public void respawn(Player player) {
    ArenaSession s = session(player.getUniqueId());
    if (s == null || s.stopping || s.finishing || transitioning(player.getUniqueId())) return;
    UUID id = player.getUniqueId();
    busy.add(id);
    teleports.add(id);
    plugin
        .onPlayer(
            player,
            () -> {
              player.setHealth(
                  player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
              player.setFoodLevel(20);
              player.setFireTicks(0);
              player.setFallDistance(0);
              player.setNoDamageTicks(40);
              return s.config.location();
            })
        .thenCompose(location -> player.teleportAsync(location))
        .whenComplete(
            (ok, error) -> {
              busy.remove(id);
              teleports.remove(id);
              if (error != null || !Boolean.TRUE.equals(ok))
                plugin.message(player, "Arena respawn failed; use /arena leave to return safely.");
            });
  }

  public synchronized Map<String, Object> state() {
    List<Object> list = new ArrayList<>();
    for (ArenaSession s :
        sessions.values().stream().sorted(Comparator.comparing(a -> a.config.id())).toList()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("config", s.config);
      row.put("phase", s.phase());
      row.put("practice", !s.bots.isEmpty());
      row.put("startedAt", s.startedAt);
      row.put("phaseEndsAt", s.phaseEndsAt);
      row.put(
          "players",
          s.players.entrySet().stream()
              .map(
                  e ->
                      Map.of(
                          "uuid",
                          e.getKey().toString(),
                          "name",
                          e.getValue(),
                          "alive",
                          s.game == null || s.game.alive().contains(e.getKey()),
                          "bot",
                          s.bots.contains(e.getKey()),
                          "score",
                          s.game == null ? 0 : s.game.scores().getOrDefault(e.getKey(), 0)))
              .toList());
      list.add(row);
    }
    return Map.of(
        "arenas",
        list,
        "server",
        plugin.serverId,
        "redisConnected",
        plugin.redis != null && plugin.redis.connected(),
        "worlds",
        plugin.worlds,
        "presets",
        List.copyOf(presets.keySet()),
        "onlinePlayers",
        Bukkit.getOnlinePlayers().stream()
            .map(p -> Map.of("uuid", p.getUniqueId().toString(), "name", p.getName()))
            .toList());
  }
}

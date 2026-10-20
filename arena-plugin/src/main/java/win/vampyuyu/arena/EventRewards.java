package win.vampyuyu.arena;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable outbox: retain exact payloads until the central transaction acknowledges them. */
final class EventRewards {
  private final NexusArenaEngine plugin;
  private final Path directory, active;
  private final String bridge, token;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final AtomicBoolean flushing = new AtomicBoolean();
  private volatile long lastWarning;

  EventRewards(NexusArenaEngine plugin, String token) throws Exception {
    this.plugin = plugin;
    this.token = token;
    bridge = System.getenv().getOrDefault("ROLE_BRIDGE_URL", "http://panel:8083");
    directory = plugin.getDataFolder().toPath().resolve("results");
    Files.createDirectories(directory);
    active = directory.resolve("active");
    Files.createDirectories(active);
    // A completed durable payload takes precedence over its interrupted-start journal.
    try (var files = Files.list(active)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
        Path result = directory.resolve(file.getFileName());
        if (!Files.exists(result)) Files.move(file, result, StandardCopyOption.ATOMIC_MOVE);
        else Files.delete(file);
      }
    }
  }

  CompletableFuture<Void> begin(ArenaSession s) {
    String payload = payload(s, "INTERRUPTED");
    return plugin.io(
        () -> {
          write(active.resolve(s.eventId + ".json"), payload);
          return null;
        });
  }

  private void write(Path target, String payload) throws Exception {
    Path temp = Files.createTempFile(target.getParent(), "result-", ".tmp");
    try {
      Files.writeString(temp, payload);
      try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private String payload(ArenaSession s, String reason) {
    GamePreset game = s.game;
    Set<UUID> winners = new HashSet<>();
    if (game instanceof WerewolfGame wolf) winners.addAll(wolf.winners());
    else
      try {
        winners.add(UUID.fromString(game.winner()));
      } catch (IllegalArgumentException ignored) {
      }
    List<Map<String, Object>> entries = new ArrayList<>();
    for (UUID id : s.roster.keySet()) {
      String result = s.practice ? "PRACTICE" : s.abandoned.contains(id) ? "ABANDONED" : reason;
      if (result.equals("COMPLETED"))
        result =
            winners.contains(id)
                ? "WIN"
                : game.winner().equals("DRAW")
                    ? "DRAW"
                    : game.winner().isEmpty() ? "COMPLETED" : "LOSS";
      boolean eligible = Set.of("WIN", "LOSS", "DRAW", "COMPLETED").contains(result);
      int gain =
          eligible
              ? s.config.rewards().participation()
                  + (result.equals("WIN") ? s.config.rewards().victory() : 0)
              : 0;
      entries.add(Map.of("uuid", id.toString(), "result", result, "gain", gain));
    }
    return ArenaManager.JSON.toJson(
        Map.of(
            "id",
            s.eventId,
            "event",
            s.config.name(),
            "server",
            plugin.serverId,
            "at",
            System.currentTimeMillis(),
            "entries",
            entries));
  }

  CompletableFuture<Void> enqueue(ArenaSession s, String reason) {
    if (s.resultQueued) return CompletableFuture.completedFuture(null);
    GamePreset game = s.game;
    if (game == null) return CompletableFuture.completedFuture(null);
    String payload = payload(s, reason);
    s.resultQueued = true;
    return plugin
        .io(
            () -> {
              Path journal = active.resolve(s.eventId + ".json");
              write(journal, payload);
              Files.move(
                  journal,
                  directory.resolve(s.eventId + ".json"),
                  StandardCopyOption.ATOMIC_MOVE,
                  StandardCopyOption.REPLACE_EXISTING);
              return (Void) null;
            })
        .whenComplete(
            (v, e) -> {
              if (e != null) s.resultQueued = false;
            });
  }

  void flush() {
    if (!flushing.compareAndSet(false, true)) return;
    plugin
        .io(
            () -> {
              try (var files = Files.list(directory)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                  var request =
                      HttpRequest.newBuilder(URI.create(bridge + "/bridge/events"))
                          .timeout(Duration.ofSeconds(5))
                          .header("Authorization", "Bearer " + token)
                          .header("Content-Type", "application/json")
                          .POST(HttpRequest.BodyPublishers.ofString(Files.readString(file)))
                          .build();
                  var response = http.send(request, HttpResponse.BodyHandlers.discarding());
                  if (response.statusCode() != 200) {
                    if (response.statusCode() == 400 || response.statusCode() == 409) {
                      warn(
                          "Result retained for review: "
                              + file.getFileName()
                              + " (HTTP "
                              + response.statusCode()
                              + ")");
                      continue;
                    }
                    break;
                  }
                  Files.delete(file);
                }
              }
              return null;
            })
        .whenComplete(
            (v, e) -> {
              flushing.set(false);
              if (e != null) warn("Result delivery deferred: " + NexusArenaEngine.reason(e));
            });
  }

  private void warn(String message) {
    long now = System.currentTimeMillis();
    if (now - lastWarning > 60000) {
      lastWarning = now;
      plugin.getLogger().warning(message);
    }
  }
}

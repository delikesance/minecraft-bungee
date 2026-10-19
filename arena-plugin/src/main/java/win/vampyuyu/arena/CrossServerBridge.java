package win.vampyuyu.arena;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.*;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

public final class CrossServerBridge implements Listener {
  private static final Set<String> SERVERS = Set.of("lobby", "creative", "survival");
  private final NexusArenaEngine plugin;
  private final Map<String, String> bans = new ConcurrentHashMap<>();
  private final Path banFile;
  private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();

  CrossServerBridge(NexusArenaEngine plugin) throws Exception {
    this.plugin = plugin;
    banFile = plugin.getDataFolder().toPath().resolve("network-bans.json");
    if (Files.isRegularFile(banFile)) {
      JsonObject obj = JsonParser.parseString(Files.readString(banFile)).getAsJsonObject();
      obj.entrySet().forEach(e -> bans.put(e.getKey(), e.getValue().getAsString()));
    }
    plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
  }

  public synchronized void syncBans() throws IOException {
    Map<String, String> remote = plugin.redis.bans();
    bans.clear();
    bans.putAll(remote);
    saveBans();
    for (Player p : Bukkit.getOnlinePlayers())
      if (bans.containsKey(p.getUniqueId().toString()))
        kick(p, bans.get(p.getUniqueId().toString()));
  }

  private synchronized void saveBans() throws IOException {
    Path temp = Files.createTempFile(plugin.getDataFolder().toPath(), "bans-", ".tmp");
    try {
      Files.writeString(temp, ArenaManager.JSON.toJson(bans));
      Files.move(
          temp, banFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public Map<String, String> banned() {
    return Map.copyOf(bans);
  }

  @EventHandler
  public void login(AsyncPlayerPreLoginEvent e) {
    String reason = bans.get(e.getUniqueId().toString());
    if (reason != null)
      e.disallow(
          AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
          Component.text("Network ban: " + reason, NamedTextColor.RED));
  }

  public CompletableFuture<Void> moderate(String action, String uuid, String reason) {
    UUID id = UUID.fromString(uuid);
    if (!id.toString().equals(uuid)
        || !Set.of("BAN", "KICK", "UNBAN").contains(action)
        || reason == null
        || reason.length() > 300
        || reason.isBlank())
      throw new IllegalArgumentException("Invalid network moderation request");
    requireRedis();
    return plugin.io(
        () -> {
          if (action.equals("BAN")) plugin.redis.ban(uuid, reason);
          if (action.equals("UNBAN")) plugin.redis.pardon(uuid);
          plugin.redis.publish(
              "server:moderation",
              ArenaManager.JSON.toJson(
                  Map.of("action", action, "targetUuid", uuid, "reason", reason)));
          return null;
        });
  }

  public CompletableFuture<Void> announce(String arenaId, String message) {
    ArenaSession s = plugin.arenas.require(arenaId);
    if (s.game != null || s.stopping)
      throw new IllegalArgumentException("Only waiting arenas can be advertised");
    if (message == null || message.isBlank() || message.length() > 300)
      throw new IllegalArgumentException("Announcement must contain 1–300 characters");
    requireRedis();
    return plugin.io(
        () -> {
          plugin.redis.publish(
              "server:announcements",
              ArenaManager.JSON.toJson(
                  Map.of(
                      "id",
                      UUID.randomUUID().toString(),
                      "server",
                      plugin.serverId,
                      "arenaId",
                      arenaId,
                      "message",
                      message,
                      "name",
                      s.config.name(),
                      "sentAt",
                      System.currentTimeMillis())));
          return null;
        });
  }

  private void requireRedis() {
    if (plugin.redis == null || !plugin.redis.connected())
      throw new IllegalArgumentException(
          "Redis is unavailable; cross-server operation was not sent");
  }

  void receive(String channel, String payload) {
    try {
      JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
      if (channel.equals("server:moderation")) {
        String action = obj.get("action").getAsString(),
            uuid = obj.get("targetUuid").getAsString(),
            reason = obj.get("reason").getAsString();
        UUID id = UUID.fromString(uuid);
        if (!id.toString().equals(uuid)
            || reason.length() > 300
            || !Set.of("BAN", "KICK", "UNBAN").contains(action)) return;
        if (action.equals("BAN")) bans.put(uuid, reason);
        if (action.equals("UNBAN")) bans.remove(uuid);
        if (!action.equals("KICK"))
          plugin.io(
              () -> {
                saveBans();
                return null;
              });
        Player p = Bukkit.getPlayer(id);
        if (p != null && !action.equals("UNBAN")) kick(p, reason);
      } else if (channel.equals("server:announcements")) {
        String server = obj.get("server").getAsString(),
            arena = obj.get("arenaId").getAsString(),
            message = obj.get("message").getAsString(),
            id = obj.get("id").getAsString();
        if (!SERVERS.contains(server)
            || !arena.matches("[a-z0-9][a-z0-9_-]{0,39}")
            || message.length() > 300
            || id.length() > 80
            || Math.abs(System.currentTimeMillis() - obj.get("sentAt").getAsLong()) > 60000) return;
        synchronized (seen) {
          if (seen.put(id, true) != null) return;
          while (seen.size() > 1024) seen.remove(seen.keySet().iterator().next());
        }
        Component invite =
            Component.text("✦ Vampyuyu · ", NamedTextColor.GOLD)
                .append(Component.text(message + " ", NamedTextColor.WHITE))
                .append(
                    Component.text("[Join arena]", NamedTextColor.LIGHT_PURPLE)
                        .hoverEvent(
                            HoverEvent.showText(
                                Component.text(
                                    "Join " + obj.get("name").getAsString() + " on " + server)))
                        .clickEvent(ClickEvent.runCommand("/arena join " + server + ":" + arena)));
        for (Player p : Bukkit.getOnlinePlayers())
          plugin.onPlayer(
              p,
              () -> {
                p.sendMessage(invite);
                return null;
              });
      }
    } catch (Exception ignored) {
      plugin.getLogger().warning("Rejected malformed arena Redis message");
    }
  }

  private void kick(Player p, String reason) {
    plugin.onPlayer(
        p,
        () -> {
          p.kick(Component.text(reason, NamedTextColor.RED));
          return null;
        });
  }

  public CompletableFuture<Void> join(Player p, String token) {
    String[] parts = token.split(":", -1);
    String server = parts.length == 2 ? parts[0] : plugin.serverId,
        id = parts.length == 2 ? parts[1] : token;
    if (parts.length > 2 || !SERVERS.contains(server) || !id.matches("[a-z0-9][a-z0-9_-]{0,39}"))
      throw new IllegalArgumentException("Invalid arena destination");
    if (server.equals(plugin.serverId)) return plugin.arenas.joinArena(p, id);
    requireRedis();
    CompletableFuture<Void> leave =
        plugin.arenas.locked(p.getUniqueId())
            ? plugin.arenas.leaveArena(p)
            : CompletableFuture.completedFuture(null);
    return leave
        .thenCompose(
            v ->
                plugin.io(
                    () -> {
                      plugin.redis.putTransfer(
                          p.getUniqueId().toString(),
                          ArenaManager.JSON.toJson(Map.of("server", server, "arenaId", id)));
                      return null;
                    }))
        .thenCompose(
            v ->
                plugin.onPlayer(
                    p,
                    () -> {
                      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                      try (DataOutputStream data = new DataOutputStream(bytes)) {
                        data.writeUTF("Connect");
                        data.writeUTF(server);
                      }
                      p.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
                      return null;
                    }));
  }

  void arrived(Player p) {
    if (plugin.redis == null || !plugin.redis.connected()) return;
    plugin
        .io(() -> plugin.redis.takeTransfer(p.getUniqueId().toString()))
        .thenAccept(
            raw -> {
              if (raw == null) return;
              try {
                JsonObject handoff = JsonParser.parseString(raw).getAsJsonObject();
                if (plugin.serverId.equals(handoff.get("server").getAsString()))
                  plugin.handle(
                      p, plugin.arenas.joinArena(p, handoff.get("arenaId").getAsString()));
              } catch (Exception e) {
                plugin.message(p, "Arena invitation expired or is no longer available.");
              }
            });
  }
}

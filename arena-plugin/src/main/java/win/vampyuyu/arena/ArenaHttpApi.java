package win.vampyuyu.arena;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Private bearer API. All operations are fixed and validated; never dispatches commands. */
final class ArenaHttpApi implements AutoCloseable {
  private final NexusArenaEngine plugin;
  private final HttpServer server;
  private final byte[] authorization;
  private final ExecutorService threads =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(16),
          r -> Thread.ofPlatform().daemon().name("nexus-api").unstarted(r),
          new ThreadPoolExecutor.AbortPolicy());

  ArenaHttpApi(NexusArenaEngine plugin, String token) throws IOException {
    this.plugin = plugin;
    authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8085), 16);
    server.setExecutor(threads);
    server.createContext("/arenas", this::handle);
    server.start();
  }

  private void handle(HttpExchange request) throws IOException {
    try {
      String auth = request.getRequestHeaders().getFirst("Authorization");
      if (!MessageDigest.isEqual(
          authorization, auth == null ? new byte[0] : auth.getBytes(StandardCharsets.UTF_8))) {
        reply(request, 401, Map.of("error", "unauthorized"));
        return;
      }
      if (request.getRequestMethod().equals("GET")
          && request.getRequestURI().getPath().equals("/arenas")) {
        Map<String, Object> out = new LinkedHashMap<>(plugin.arenas.state());
        out.put("bans", plugin.bridge.banned());
        reply(request, 200, out);
        return;
      }
      if (!request.getRequestMethod().equals("POST")
          || !request.getRequestURI().getPath().equals("/arenas/action")) {
        reply(request, 404, Map.of("error", "Unknown arena endpoint"));
        return;
      }
      byte[] bytes = request.getRequestBody().readNBytes(65537);
      if (bytes.length > 65536) throw new IllegalArgumentException("Arena request exceeds 64 KiB");
      JsonObject input =
          JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
      operation(input).get(15, TimeUnit.SECONDS);
      reply(request, 200, Map.of("ok", true));
    } catch (Exception e) {
      Throwable root = e;
      while (root.getCause() != null) root = root.getCause();
      int status =
          root instanceof IllegalArgumentException || root instanceof JsonParseException
              ? 400
              : 503;
      if (status == 503)
        plugin.getLogger().warning("Arena operation: " + NexusArenaEngine.reason(root));
      reply(
          request,
          status,
          Map.of(
              "error",
              root instanceof TimeoutException
                  ? "Operation is still completing; refresh before retrying"
                  : NexusArenaEngine.reason(root)));
    } finally {
      request.close();
    }
  }

  private CompletableFuture<?> operation(JsonObject input) {
    String action = text(input, "action", 40);
    String id = input.has("arenaId") ? text(input, "arenaId", 40) : "";
    return switch (action) {
      case "arena-save" ->
          plugin.io(
              () -> {
                plugin.arenas.save(
                    ArenaManager.JSON.fromJson(input.get("config"), ArenaConfig.class));
                return null;
              });
      case "arena-delete" ->
          plugin.io(
              () -> {
                plugin.arenas.delete(id);
                return null;
              });
      case "arena-reload" ->
          plugin.io(
              () -> {
                plugin.arenas.reload();
                return null;
              });
      case "arena-start" ->
          plugin.io(
              () -> {
                plugin.arenas.start(id);
                return null;
              });
      case "arena-stop" -> plugin.arenas.stop(id);
      case "arena-announce" -> plugin.bridge.announce(id, text(input, "message", 300));
      case "arena-kick", "arena-ban", "arena-unban" ->
          plugin.bridge.moderate(
              action.substring(6).toUpperCase(Locale.ROOT),
              text(input, "uuid", 36),
              text(input, "reason", 300));
      case "arena-join", "arena-leave" -> {
        Player p = Bukkit.getPlayer(UUID.fromString(text(input, "uuid", 36)));
        if (p == null)
          throw new IllegalArgumentException("Player must be connected to this server");
        yield action.equals("arena-join")
            ? plugin.arenas.joinArena(p, id)
            : plugin.arenas.leaveArena(p);
      }
      default -> throw new IllegalArgumentException("Unsupported arena operation");
    };
  }

  private static String text(JsonObject input, String key, int max) {
    if (!input.has(key)
        || !input.get(key).isJsonPrimitive()
        || !input.get(key).getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Missing " + key);
    String text = input.get(key).getAsString();
    if (text.length() > max || text.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid " + key);
    return text;
  }

  private void reply(HttpExchange exchange, int status, Object value) throws IOException {
    byte[] bytes = ArenaManager.JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  @Override
  public void close() {
    server.stop(0);
    threads.shutdownNow();
  }
}

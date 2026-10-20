package win.vampyuyu.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Network display and explicitly configured limited role commands. */
public final class NetworkDisplay extends JavaPlugin implements Listener {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final TextColor GOLD = TextColor.color(0xe9d99b);
    private static final TextColor IVORY = TextColor.color(0xf5e9cf);
    private static final TextColor ROSE = TextColor.color(0xd58b8e);
    private static final TextColor MUTED = TextColor.color(0xc5a69b);
    private static final Role DEFAULT = new Role("player", "Yuyu's Bat", "#D6C7BD", "Yuyu's Bat", 0);
    private final Map<String, Role> roles = new ConcurrentHashMap<>();
    private final Map<UUID, String> assignments = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<List<Identity>> registration = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "vampyuyu-role-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile List<Identity> online = List.of();
    private volatile boolean active;
    private volatile int networkOnline;
    private volatile Map<String, List<String>> commandRoles = Map.of();
    private volatile List<Identity> operators = List.of();
    private RoleAccess access;
    private ManagementApi management;
    private volatile long lastPolicySync;
    private String server;
    private String bridge;
    private String token;
    private String address;
    private long lastWarning;

    private record Role(String id, String name, String color, String prefix, int priority) {}
    private record Identity(String uuid, String name, long lastSeen, String gamemode) {}
    private record Request(String server, List<Identity> players, List<Identity> operators) {}
    private record Response(List<Role> roles, Map<String, String> assignments, int online, Map<String, List<String>> commands) {}

    @Override
    public void onEnable() {
        server = System.getenv("SERVER_ID");
        bridge = System.getenv("ROLE_BRIDGE_URL");
        address = System.getenv().getOrDefault("MC_CONNECT_ADDRESS", "play.vampyuyu.win");
        if (server == null || !List.of("lobby", "creative", "survival").contains(server) || bridge == null) {
            getLogger().severe("SERVER_ID and ROLE_BRIDGE_URL are required");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            token = Files.readString(Path.of("/run/secrets/role_bridge_token"), StandardCharsets.UTF_8).trim();
            if (token.length() < 32) throw new IOException("invalid bridge token");
        } catch (IOException exception) {
            getLogger().severe("Role bridge secret is unavailable");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        MenuPack pack = MenuPack.load(this);
        if (pack != null) getServer().getPluginManager().registerEvents(pack, this);
        new WorldSelector(this, pack);
        try { new CommunityFeatures(this, server, bridge, token, pack); }
        catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Community features failed to load", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        access = new RoleAccess(this);
        try { management = new ManagementApi(this, token); }
        catch (IOException exception) {
            getLogger().severe("Private management listener could not start; disabling plugin");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        roles.put(DEFAULT.id(), DEFAULT);
        active = true;
        getServer().getPluginManager().registerEvents(this, this);
        // Bukkit access stays on the main thread; HTTP and JSON run on the worker.
        getServer().getScheduler().runTaskTimer(this, () -> {
            online = getServer().getOnlinePlayers().stream()
                    .map(player -> new Identity(player.getUniqueId().toString(), player.getName(), 0, player.getGameMode().name())).toList();
            operators = getServer().getOperators().stream()
                    .map(player -> new Identity(player.getUniqueId().toString(), player.getName() == null ? "" : player.getName(), 0, "")).toList();
            getServer().getOnlinePlayers().forEach(this::apply);
        }, 1L, 20L);
        getServer().getScheduler().runTaskLater(this, this::discoverKnownPlayers, 20L);
        worker.scheduleWithFixedDelay(this::synchronize, 1, 5, TimeUnit.SECONDS);
        getLogger().info("Cosmetic roles and branded TAB list enabled for " + server);
    }

    private void discoverKnownPlayers() {
        List<Identity> batch = new ArrayList<>();
        for (OfflinePlayer player : getServer().getOfflinePlayers()) {
            if (player.getName() == null) continue;
            batch.add(new Identity(player.getUniqueId().toString(), player.getName(), player.getLastPlayed() / 1000, ""));
            if (batch.size() == 200) {
                registration.add(List.copyOf(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) registration.add(List.copyOf(batch));
    }

    private Response request(String route, List<Identity> players) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(bridge + "/bridge/" + route))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(new Request(server, players, operators))))
                .build();
        HttpResponse<String> result = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (result.statusCode() != 200) throw new IOException("bridge returned HTTP " + result.statusCode());
        Response response = JSON.fromJson(result.body(), Response.class);
        if (response == null || response.roles() == null || response.assignments() == null) {
            throw new IOException("bridge returned an invalid response");
        }
        return response;
    }

    private void synchronize() {
        if (!active) return;
        try {
            Response response = request("sync", online);
            Map<String, Role> next = new HashMap<>();
            for (Role role : response.roles()) {
                if (role.id() == null || role.prefix() == null || role.name() == null
                        || role.color() == null || TextColor.fromHexString(role.color()) == null) {
                    throw new IOException("bridge returned invalid role metadata");
                }
                next.put(role.id(), role);
            }
            roles.putAll(next);
            for (Map.Entry<String, String> entry : response.assignments().entrySet()) {
                assignments.put(UUID.fromString(entry.getKey()), entry.getValue());
            }
            networkOnline = response.online();
            commandRoles = response.commands() == null ? Map.of() : response.commands();
            lastPolicySync = System.currentTimeMillis();
            List<Identity> batch = registration.peek();
            if (batch != null) {
                request("register", batch);
                registration.poll();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            // Retain the last received cosmetics during an outage; never change permissions.
            long now = System.currentTimeMillis();
            if (active && now - lastWarning > 60000) {
                getLogger().warning("Role synchronization unavailable; retaining cached display settings");
                lastWarning = now;
            }
        }
    }

    private Role role(Player player) {
        return roles.getOrDefault(assignments.getOrDefault(player.getUniqueId(), "player"), DEFAULT);
    }

    private static Component text(String value, TextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private Component display(Player player) {
        Role role = role(player);
        TextColor color = TextColor.fromHexString(role.color());
        return formatName(role.prefix(), player.getName(), color);
    }

    static Component formatName(String prefix, String username, TextColor color) {
        // A neutral parent keeps the role's bold style out of the username and chat body.
        return Component.empty()
                .append(text("[" + prefix + "] ", color).decorate(TextDecoration.BOLD))
                .append(text(username, color).decoration(TextDecoration.BOLD, false));
    }

    private void apply(Player player) {
        access.apply(player, System.currentTimeMillis() - lastPolicySync < 25000
                ? commandRoles.getOrDefault(role(player).id(), List.of()) : List.of());
        Component display = display(player);
        if (!display.equals(player.playerListName())) player.playerListName(display);
        if (!display.equals(player.displayName())) player.displayName(display);
        String world = switch (server) {
            case "creative" -> "Creative";
            case "survival" -> "Survival";
            default -> "Lobby";
        };
        Component header = Component.newline()
                .append(text("✦ ", GOLD))
                .append(text("VAMPYUYU", ROSE).decorate(TextDecoration.BOLD))
                .append(text(" NETWORK ✦", GOLD).decorate(TextDecoration.BOLD))
                .append(Component.newline())
                .append(text("play.", ROSE)).append(text("vampyuyu", IVORY)).append(text(".win", GOLD))
                .append(Component.newline());
        Component footer = Component.newline()
                .append(text(world, GOLD)).append(text("  ·  ", MUTED))
                .append(text(online.size() + " here", IVORY)).append(text("  ·  ", MUTED))
                .append(text(networkOnline + " across the network", IVORY))
                .append(Component.newline())
                .append(text("Your worlds. Your next story.", MUTED));
        if (!address.equals("play.vampyuyu.win")) {
            footer = footer.append(Component.newline()).append(text("Local connection: ", MUTED))
                    .append(text(address, GOLD));
        }
        player.sendPlayerListHeaderAndFooter(header, footer.append(Component.newline()));
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void chat(AsyncChatEvent event) {
        // The signed message content stays intact; only the surrounding renderer changes.
        Component name = display(event.getPlayer());
        event.renderer((source, sourceDisplayName, message, viewer) -> name
                .append(text(" » ", MUTED)).append(message));
    }

    @Override
    public void onDisable() {
        active = false;
        worker.shutdownNow();
        if (access != null) access.close();
        if (management != null) management.close();
    }
}

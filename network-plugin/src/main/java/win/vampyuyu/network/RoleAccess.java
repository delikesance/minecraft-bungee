package win.vampyuyu.network;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

/** Limited role grants. Never dispatches role commands through the console. */
final class RoleAccess implements Listener {
    private static final Map<String, String> PERMISSIONS = Map.of(
            "ban", "minecraft.command.ban",
            "kick", "minecraft.command.kick",
            "pardon", "minecraft.command.pardon",
            "fly", "vampyuyu.fly",
            "invsee", InventoryViewer.PERMISSION);
    /** Builder tools every player gets on the creative server, on top of any role grants. */
    static final Set<String> CREATIVE_BUILDER = Set.of(
            "minecraft.command.give",
            "minecraft.command.clear",
            "minecraft.command.enchant",
            "minecraft.command.teleport",
            "minecraft.debugstick");
    private final JavaPlugin plugin;
    private final Set<String> baseline;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private final Map<UUID, Set<String>> applied = new HashMap<>();
    private final Set<UUID> flight = new HashSet<>();

    static Set<String> permissionsFor(List<String> commands) {
        Set<String> result = new HashSet<>();
        if (commands != null) for (String command : commands) {
            String permission = PERMISSIONS.get(command);
            if (permission != null) result.add(permission);
        }
        return Set.copyOf(result);
    }

    RoleAccess(JavaPlugin plugin, Set<String> baseline) {
        this.plugin = plugin;
        this.baseline = Set.copyOf(baseline);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Objects.requireNonNull(plugin.getCommand("fly")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Use /fly in game.");
                return true;
            }
            if (!player.hasPermission("vampyuyu.fly")) {
                player.sendMessage("Your role does not allow /fly.");
                return true;
            }
            if (args.length != 0) {
                player.sendMessage("Usage: /fly (your own flight only)");
                return true;
            }
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                player.sendMessage("Flight is already available in your game mode.");
                return true;
            }
            if (flight.remove(player.getUniqueId())) {
                player.setFlying(false);
                player.setAllowFlight(false);
                player.sendMessage("Flight disabled.");
            } else {
                flight.add(player.getUniqueId());
                player.setAllowFlight(true);
                player.setFlying(true);
                player.sendMessage("Flight enabled.");
            }
            return true;
        });
        Objects.requireNonNull(plugin.getCommand("vampyuyuoperator")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
                sender.sendMessage("This operation is only available through the server console.");
                return true;
            }
            if (args.length != 2 || !args[0].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || !(args[1].equals("true") || args[1].equals("false"))) {
                sender.sendMessage("Invalid operator request.");
                return true;
            }
            UUID uuid = UUID.fromString(args[0]);
            if (uuid.equals(new UUID(0, 0))) {
                sender.sendMessage("Invalid operator target.");
                return true;
            }
            plugin.getServer().getOfflinePlayer(uuid).setOp(Boolean.parseBoolean(args[1]));
            sender.sendMessage("Operator status updated: " + uuid + " " + args[1]);
            return true;
        });
    }

    void apply(Player player, List<String> commands) {
        UUID uuid = player.getUniqueId();
        Set<String> permissions = new HashSet<>(permissionsFor(commands));
        permissions.addAll(baseline);
        if (!permissions.equals(applied.get(uuid))) {
            PermissionAttachment previous = attachments.remove(uuid);
            if (previous != null) player.removeAttachment(previous);
            PermissionAttachment attachment = player.addAttachment(plugin);
            for (String permission : permissions) attachment.setPermission(permission, true);
            attachments.put(uuid, attachment);
            applied.put(uuid, permissions);
            player.recalculatePermissions();
            player.updateCommands();
        }
        if (flight.contains(uuid) && !permissions.contains("vampyuyu.fly")) revokeFlight(player);
    }

    private void revokeFlight(Player player) {
        if (flight.remove(player.getUniqueId()) && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        // The baseline must not wait for the first role sync.
        apply(event.getPlayer(), List.of());
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        revokeFlight(player);
        PermissionAttachment previous = attachments.remove(player.getUniqueId());
        if (previous != null) player.removeAttachment(previous);
        applied.remove(player.getUniqueId());
    }

    void close() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            revokeFlight(player);
            PermissionAttachment previous = attachments.remove(player.getUniqueId());
            if (previous != null) player.removeAttachment(previous);
        }
        applied.clear();
    }
}

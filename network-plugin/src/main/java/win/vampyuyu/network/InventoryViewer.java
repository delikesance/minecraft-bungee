package win.vampyuyu.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Staff-only, read-only live view of an online player's inventory. */
final class InventoryViewer implements Listener {
    static final String PERMISSION = "vampyuyu.invsee";
    private static final TextColor ROSE = TextColor.color(0xd58b8e);
    private static final TextColor IVORY = TextColor.color(0xf5e9cf);
    private static final TextColor MUTED = TextColor.color(0xc5a69b);
    /** Player inventory index shown in each of the 54 view slots, or -1 for decoration. */
    static final int[] LAYOUT = layout();
    private final JavaPlugin plugin;

    private static int[] layout() {
        int[] result = new int[54];
        java.util.Arrays.fill(result, -1);
        // Rows 1-3: main storage (9-35), row 4: hotbar (0-8), as in the player's own screen.
        for (int i = 0; i < 27; i++) result[i] = i + 9;
        for (int i = 0; i < 9; i++) result[27 + i] = i;
        // Bottom row: helmet, chestplate, leggings, boots, then off hand.
        result[45] = 39;
        result[46] = 38;
        result[47] = 37;
        result[48] = 36;
        result[50] = 40;
        return result;
    }

    private static final class View implements InventoryHolder {
        private final UUID target;
        private final Inventory inventory;

        View(Player target) {
            this.target = target.getUniqueId();
            inventory = Bukkit.createInventory(this, 54, Component.text(target.getName() + "'s inventory"));
            ItemStack filler = label(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int slot = 36; slot < 54; slot++) inventory.setItem(slot, filler);
            ItemStack armor = label(Material.RED_STAINED_GLASS_PANE, "Armor ↓");
            for (int slot = 36; slot < 40; slot++) inventory.setItem(slot, armor);
            inventory.setItem(41, label(Material.RED_STAINED_GLASS_PANE, "Off hand ↓"));
            refresh(target);
        }

        void refresh(Player target) {
            PlayerInventory source = target.getInventory();
            for (int slot = 0; slot < LAYOUT.length; slot++) {
                if (LAYOUT[slot] < 0) continue;
                ItemStack item = source.getItem(LAYOUT[slot]);
                ItemStack shown = item == null ? null : item.clone();
                if (!Objects.equals(inventory.getItem(slot), shown)) inventory.setItem(slot, shown);
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static ItemStack label(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, ROSE).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    InventoryViewer(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var command = Objects.requireNonNull(plugin.getCommand("invsee"));
        command.setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player viewer)) {
                sender.sendMessage("Use /invsee in game.");
                return true;
            }
            if (!viewer.hasPermission(PERMISSION)) {
                viewer.sendMessage(Component.text("You do not have permission to view inventories.", ROSE));
                return true;
            }
            if (args.length != 1) {
                viewer.sendMessage(Component.text("Usage: /invsee <player>", MUTED));
                return true;
            }
            Player target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null) {
                viewer.sendMessage(Component.text(args[0] + " is not online on this server.", ROSE));
                return true;
            }
            viewer.openInventory(new View(target).getInventory());
            viewer.sendMessage(Component.text("Viewing ", MUTED).append(Component.text(target.getName(), IVORY))
                    .append(Component.text("'s inventory (read-only).", MUTED)));
            return true;
        });
        command.setTabCompleter((sender, cmd, label, args) -> {
            if (args.length != 1 || !sender.hasPermission(PERMISSION)) return List.of();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) names.add(player.getName());
            }
            return names;
        });
        // Keep open views in sync with the target; close them once the target is gone.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshAll, 10L, 10L);
    }

    private void refreshAll() {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!(viewer.getOpenInventory().getTopInventory().getHolder() instanceof View view)) continue;
            Player target = plugin.getServer().getPlayer(view.target);
            if (target == null || !viewer.hasPermission(PERMISSION)) {
                viewer.closeInventory();
                continue;
            }
            view.refresh(target);
        }
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true);
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true);
    }
}

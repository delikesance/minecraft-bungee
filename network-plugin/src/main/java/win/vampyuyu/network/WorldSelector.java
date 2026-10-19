package win.vampyuyu.network;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

final class WorldSelector implements Listener {
    // Keep the in-game selector aligned with the panel's burgundy/ivory/gold palette.
    private static final TextColor WINE = TextColor.color(0x8e2e35);
    private static final TextColor ROSE = TextColor.color(0xd58b8e);
    private static final TextColor IVORY = TextColor.color(0xf5e9cf);
    private static final TextColor GOLD = TextColor.color(0xe9d99b);
    private static final TextColor MUTED = TextColor.color(0xc5a69b);
    private static final int CREATIVE_SLOT = 11;
    private static final int SURVIVAL_SLOT = 15;
    private static final int LOBBY_SLOT = 22;
    private static final int CLOSE_SLOT = 26;

    private static Component text(String content, TextColor color) {
        return Component.text(content, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component brand() {
        return text("Vampyuyu", WINE).decorate(TextDecoration.BOLD)
                .append(text(" · ", MUTED)).append(text("Worlds", GOLD));
    }

    private static ItemStack item(Material material, Component name, Component... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(List.of(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static final class Menu implements InventoryHolder {
        private final Inventory inventory;

        Menu() {
            inventory = Bukkit.createInventory(this, 27, brand());
            ItemStack dark = item(Material.BLACK_STAINED_GLASS_PANE, Component.empty());
            ItemStack wine = item(Material.RED_STAINED_GLASS_PANE, Component.empty());
            ItemStack ivory = item(Material.WHITE_STAINED_GLASS_PANE, Component.empty());
            ItemStack gold = item(Material.YELLOW_STAINED_GLASS_PANE, Component.empty());
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                inventory.setItem(slot, dark);
            }
            // A symmetrical red frame, with pale accents around the two world cards.
            for (int slot : new int[] {0, 1, 2, 6, 7, 8, 9, 17, 18, 19, 20, 24, 25, 26}) {
                inventory.setItem(slot, wine);
            }
            for (int slot : new int[] {3, 5, 21, 23}) {
                inventory.setItem(slot, ivory);
            }
            inventory.setItem(4, gold);
            inventory.setItem(CREATIVE_SLOT, item(Material.BRICKS,
                    text("CREATIVE", ROSE).decorate(TextDecoration.BOLD),
                    text("A world for your imagination.", IVORY),
                    Component.empty(),
                    text("Build freely. Dream bigger.", MUTED),
                    text("Creative mode · Free building", MUTED),
                    Component.empty(),
                    text("Click to enter →", GOLD)));
            inventory.setItem(13, item(Material.NETHER_STAR,
                    text("VAMPYUYU NETWORK", GOLD).decorate(TextDecoration.BOLD),
                    text("Two worlds. Your next story.", IVORY),
                    Component.empty(),
                    text("Choose Creative or Survival", MUTED),
                    text("to begin your adventure.", MUTED)));
            inventory.setItem(SURVIVAL_SLOT, item(Material.GOLDEN_PICKAXE,
                    text("SURVIVAL", GOLD).decorate(TextDecoration.BOLD),
                    text("An adventure worth sharing.", IVORY),
                    Component.empty(),
                    text("Gather. Explore. Make it yours.", MUTED),
                    text("Survival mode · Open exploration", MUTED),
                    Component.empty(),
                    text("Click to enter →", GOLD)));
            inventory.setItem(LOBBY_SLOT, item(Material.COMPASS,
                    text("LOBBY", ROSE).decorate(TextDecoration.BOLD),
                    text("Return to the network hub.", MUTED)));
            inventory.setItem(CLOSE_SLOT, item(Material.BARRIER,
                    text("Close menu", ROSE),
                    text("Stay in your current world.", MUTED)));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;

    WorldSelector(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Objects.requireNonNull(plugin.getCommand("menu")).setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) {
                open(player);
            } else {
                sender.sendMessage("Use this command in game.");
            }
            return true;
        });
    }

    private void open(Player player) {
        player.openInventory(new Menu().getInventory());
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.35f, 1.1f);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot == CLOSE_SLOT) {
            // Inventory view changes must run after the click event has completed.
            plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
            return;
        }
        String server = slot == CREATIVE_SLOT ? "creative" : slot == SURVIVAL_SLOT ? "survival" : slot == LOBBY_SLOT ? "lobby" : null;
        if (server == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream data = new DataOutputStream(bytes);
                data.writeUTF("Connect");
                data.writeUTF(server);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.35f, 1.3f);
                player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
                player.closeInventory();
                String destination = server.equals("creative") ? "Creative" : server.equals("survival") ? "Survival" : "Lobby";
                player.sendMessage(text("VAMPYUYU ", ROSE).decorate(TextDecoration.BOLD)
                        .append(text("Connecting to ", IVORY))
                        .append(text(destination, GOLD))
                        .append(text("… If unavailable, please try again shortly.", MUTED)));
            } catch (IOException exception) {
                plugin.getLogger().warning("Unable to encode server selection");
            }
        });
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }
}

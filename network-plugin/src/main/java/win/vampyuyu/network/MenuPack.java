package win.vampyuyu.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Offers the optional Vampyuyu menu resource pack and remembers who actually loaded it, so menus can
 * switch between the painted layout and the plain glass-pane layout per player.
 */
final class MenuPack implements Listener {
    /** Font that carries the full-window menu backgrounds (see resource-pack/pack). */
    static final Key FONT = Key.key("vampyuyu", "menus");
    static final char SHIFT_BACK = '\uE000';
    static final char SHIFT_FORWARD = '\uE001';
    static final char LOBBY = '\uE100';
    static final char LIST = '\uE102';
    static final char DETAIL = '\uE103';
    static final char CONFIRM = '\uE104';
    private static final NamespacedKey BLANK = new NamespacedKey("vampyuyu", "blank");

    private final JavaPlugin plugin;
    private final UUID id;
    private final String url;
    private final byte[] hash;
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();

    MenuPack(JavaPlugin plugin, String baseUrl, String sha1) {
        this.plugin = plugin;
        this.hash = parseSha1(sha1);
        this.id = packId(sha1);
        this.url = packUrl(baseUrl, sha1);
    }

    /** Reads MENU_PACK_BASE_URL and the SHA-1 file written by the image build; null when not configured. */
    static MenuPack load(JavaPlugin plugin) {
        String base = System.getenv("MENU_PACK_BASE_URL");
        Path file = Path.of(System.getenv().getOrDefault("MENU_PACK_SHA1_FILE", "/opt/vampyuyu-menus.sha1"));
        if (base == null || base.isBlank() || !Files.isRegularFile(file)) {
            plugin.getLogger().info("Menu resource pack not configured; plain menus stay active");
            return null;
        }
        try {
            String sha1 = Files.readString(file, StandardCharsets.UTF_8).trim();
            MenuPack pack = new MenuPack(plugin, base, sha1);
            plugin.getLogger().info("Menu resource pack offered from " + pack.url);
            return pack;
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().warning("Menu resource pack disabled: " + e.getMessage());
            return null;
        }
    }

    static byte[] parseSha1(String sha1) {
        if (sha1 == null || !sha1.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("invalid SHA-1");
        return HexFormat.of().parseHex(sha1);
    }

    /** Stable per pack revision: the same on every backend so clients keep one cached pack. */
    static UUID packId(String sha1) {
        parseSha1(sha1);
        return UUID.nameUUIDFromBytes(("vampyuyu-menus:" + sha1).getBytes(StandardCharsets.UTF_8));
    }

    static String packUrl(String baseUrl, String sha1) {
        parseSha1(sha1);
        String base = baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.startsWith("http://") && !base.startsWith("https://"))
            throw new IllegalArgumentException("MENU_PACK_BASE_URL must be http(s)");
        return base + "/pack/" + sha1 + ".zip";
    }

    /** Full-window background glyph followed by the real title text, aligned to the vanilla title origin. */
    static Component title(char glyph, Component after) {
        return Component.text("" + SHIFT_BACK + glyph + SHIFT_FORWARD, NamedTextColor.WHITE)
                .font(FONT)
                .append(after);
    }

    /** An item that draws nothing: the painted background shows through while the tooltip still works. */
    static ItemStack invisible(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(BLANK);
        item.setItemMeta(meta);
        return item;
    }

    /** Themed icon item model (vampyuyu:icon/<name>); glow marks a switched-on option. */
    static ItemStack icon(ItemStack item, String name, boolean glow) {
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("vampyuyu", "icon/" + name));
        if (glow) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    static String iconFor(org.bukkit.Material material, String title) {
        String t = title.toLowerCase(java.util.Locale.ROOT);
        return switch (material) {
            case ENDER_PEARL -> "pearl";
            case WRITABLE_BOOK, BOOK -> "pencil";
            case BARRIER -> "trash";
            case RED_WOOL -> "trash";
            case EMERALD -> "plus";
            case SUNFLOWER -> "coin";
            case ARROW -> t.startsWith("next") ? "next" : "back";
            default -> "tag";
        };
    }

    boolean active(Player player) {
        return loaded.contains(player.getUniqueId());
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        loaded.remove(event.getPlayer().getUniqueId());
        event.getPlayer().setResourcePack(id, url, hash,
                Component.text("Optional Vampyuyu menu theme"), false);
    }

    @EventHandler
    public void status(PlayerResourcePackStatusEvent event) {
        if (!id.equals(event.getID())) return;
        UUID player = event.getPlayer().getUniqueId();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> loaded.add(player);
            case ACCEPTED, DOWNLOADED -> { }
            default -> loaded.remove(player);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        loaded.remove(event.getPlayer().getUniqueId());
    }
}

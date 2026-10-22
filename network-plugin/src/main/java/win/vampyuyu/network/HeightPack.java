package win.vampyuyu.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Raises the overworld build limit by installing a datapack that overrides the overworld dimension
 * type. The JSON is copied from the running server's own data and only {@code height} and
 * {@code logical_height} change, so it always matches the server version. {@code min_y} is kept, so
 * existing chunks stay valid; only empty space is added above them. Takes effect on the next restart.
 */
final class HeightPack {
    /** Vanilla rule: min_y + height may not exceed 2032, and height is a multiple of 16 (max 4064). */
    static final int TOP = 2032;
    private static final String RESOURCE = "data/minecraft/dimension_type/overworld.json";

    private HeightPack() {}

    static int heightFor(int minY) {
        return Math.min(4064, (TOP - minY) / 16 * 16);
    }

    static void install(JavaPlugin plugin) {
        try {
            JsonObject type = readVanilla(plugin);
            if (type == null) {
                plugin.getLogger().warning("Build height unchanged: vanilla overworld dimension type not found");
                return;
            }
            int height = heightFor(type.get("min_y").getAsInt());
            if (type.get("height").getAsInt() >= height) return;
            type.addProperty("height", height);
            type.addProperty("logical_height", height);
            Path pack = world(plugin).resolve("datapacks").resolve("vampyuyu-height");
            Path file = pack.resolve("data/minecraft/dimension_type/overworld.json");
            String json = type.toString();
            // Data packs for versions below 82 need supported_formats; 82+ use min/max_format only.
            String meta = "{\"pack\":{\"description\":\"Vampyuyu build height\",\"min_format\":[82,0],\"max_format\":[1000,0]}}";
            Path mcmeta = pack.resolve("pack.mcmeta");
            if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(json)
                    && Files.exists(mcmeta) && Files.readString(mcmeta, StandardCharsets.UTF_8).equals(meta)) return;
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
            Files.writeString(mcmeta, meta, StandardCharsets.UTF_8);
            plugin.getLogger().info("Build height datapack installed (" + height + " blocks); restart to apply");
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Build height unchanged: " + e);
        }
    }

    private static JsonObject readVanilla(JavaPlugin plugin) throws IOException {
        for (ClassLoader loader : new ClassLoader[] {plugin.getServer().getClass().getClassLoader(), ClassLoader.getSystemClassLoader()}) {
            try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
                if (in != null) return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
        return null;
    }

    private static Path world(JavaPlugin plugin) throws IOException {
        String name = "world";
        Path properties = Path.of("server.properties");
        if (Files.isRegularFile(properties))
            for (String line : Files.readAllLines(properties))
                if (line.startsWith("level-name=") && line.length() > 11) name = line.substring(11).trim();
        return plugin.getServer().getWorldContainer().toPath().resolve(name);
    }
}

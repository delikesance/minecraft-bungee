package win.vampyuyu.arena;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/** Write-before-clear journal. Files survive disconnects, crashes and plugin restarts. */
public final class PlayerSnapshotManager {
  private final NexusArenaEngine plugin;
  private final Path directory;

  PlayerSnapshotManager(NexusArenaEngine plugin) throws IOException {
    this.plugin = plugin;
    directory = plugin.getDataFolder().toPath().resolve("snapshots");
    Files.createDirectories(directory);
  }

  public boolean exists(UUID id) {
    return Files.exists(file(id), LinkOption.NOFOLLOW_LINKS);
  }

  private Path file(UUID id) {
    return directory.resolve(id + ".yml");
  }

  public String capture(Player p) {
    if (p.isDead()) throw new IllegalArgumentException("Respawn before joining an arena");
    p.closeInventory();
    YamlConfiguration y = new YamlConfiguration();
    y.set("schema", 1);
    y.set("uuid", p.getUniqueId().toString());
    y.set("minecraft", Bukkit.getMinecraftVersion());
    Location l = p.getLocation();
    y.set("world", l.getWorld().getUID().toString());
    y.set(
        "position",
        List.of(l.getX(), l.getY(), l.getZ(), (double) l.getYaw(), (double) l.getPitch()));
    y.set("mode", p.getGameMode().name());
    y.set("level", p.getLevel());
    y.set("xp", p.getExp());
    y.set("totalXp", p.getTotalExperience());
    y.set("health", p.getHealth());
    y.set("food", p.getFoodLevel());
    y.set("saturation", p.getSaturation());
    y.set("exhaustion", p.getExhaustion());
    y.set("flight", p.getAllowFlight());
    y.set("flying", p.isFlying());
    y.set("flySpeed", p.getFlySpeed());
    y.set("walkSpeed", p.getWalkSpeed());
    y.set("fire", p.getFireTicks());
    y.set("air", p.getRemainingAir());
    y.set("absorption", p.getAbsorptionAmount());
    y.set("invulnerable", p.isInvulnerable());
    y.set("collidable", p.isCollidable());
    y.set("playerTime", p.getPlayerTimeOffset());
    y.set("relativeTime", p.isPlayerTimeRelative());
    y.set("playerWeather", p.getPlayerWeather() == null ? "WORLD" : p.getPlayerWeather().name());
    y.set("selectedSlot", p.getInventory().getHeldItemSlot());
    y.set("effects", new ArrayList<>(p.getActivePotionEffects()));
    List<String> items = new ArrayList<>();
    for (ItemStack item : p.getInventory().getContents())
      items.add(item == null ? "" : Base64.getEncoder().encodeToString(item.serializeAsBytes()));
    y.set("items", items);
    List<String> ender = new ArrayList<>();
    for (ItemStack item : p.getEnderChest().getContents())
      ender.add(item == null ? "" : Base64.getEncoder().encodeToString(item.serializeAsBytes()));
    y.set("ender", ender);
    return y.saveToString();
  }

  public void write(UUID id, String snapshot) throws IOException {
    byte[] bytes = snapshot.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 8 * 1024 * 1024)
      throw new IOException("Player snapshot exceeds 8 MiB; inventory was not changed");
    if (exists(id)) throw new IOException("An unrestored arena snapshot already exists");
    Path temp = Files.createTempFile(directory, "snapshot-", ".tmp");
    try {
      Files.write(temp, bytes);
      try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(temp, file(id), StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public YamlConfiguration read(UUID id) throws Exception {
    Path f = file(id);
    if (Files.isSymbolicLink(f) || Files.size(f) > 8 * 1024 * 1024)
      throw new IOException("Invalid snapshot file");
    YamlConfiguration y = new YamlConfiguration();
    y.load(f.toFile());
    if (y.getInt("schema") != 1
        || !id.toString().equals(y.getString("uuid"))
        || !Bukkit.getMinecraftVersion().equals(y.getString("minecraft")))
      throw new IOException(
          "Snapshot version mismatch; keep the file and use its original Minecraft version");
    return y;
  }

  public Location origin(YamlConfiguration y) {
    World w = Bukkit.getWorld(UUID.fromString(Objects.requireNonNull(y.getString("world"))));
    if (w == null)
      throw new IllegalArgumentException("Original world is not loaded; snapshot retained");
    List<Double> v = y.getDoubleList("position");
    if (v.size() != 5) throw new IllegalArgumentException("Invalid snapshot position");
    return new Location(
        w, v.get(0), v.get(1), v.get(2), v.get(3).floatValue(), v.get(4).floatValue());
  }

  public void apply(Player p, YamlConfiguration y) {
    List<String> raw = y.getStringList("items");
    if (raw.size() != p.getInventory().getContents().length)
      throw new IllegalArgumentException("Invalid snapshot inventory");
    ItemStack[] items = new ItemStack[raw.size()];
    for (int i = 0; i < items.length; i++)
      if (!raw.get(i).isEmpty())
        items[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(raw.get(i)));
    if (y.contains("ender")) {
      List<String> rawEnder = y.getStringList("ender");
      if (rawEnder.size() != p.getEnderChest().getSize())
        throw new IllegalArgumentException("Invalid snapshot ender chest");
      ItemStack[] ender = new ItemStack[rawEnder.size()];
      for (int i = 0; i < ender.length; i++)
        if (!rawEnder.get(i).isEmpty())
          ender[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(rawEnder.get(i)));
      p.getEnderChest().setContents(ender);
    }
    p.closeInventory();
    p.getInventory().setContents(items);
    p.getInventory().setHeldItemSlot(y.getInt("selectedSlot"));
    p.setGameMode(GameMode.valueOf(y.getString("mode")));
    p.setTotalExperience(y.getInt("totalXp"));
    p.setLevel(y.getInt("level"));
    p.setExp((float) y.getDouble("xp"));
    for (PotionEffect effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType());
    for (Object effect : y.getList("effects", List.of()))
      if (effect instanceof PotionEffect potion) p.addPotionEffect(potion);
    p.setAllowFlight(y.getBoolean("flight"));
    p.setFlying(y.getBoolean("flight") && y.getBoolean("flying"));
    p.setFlySpeed((float) y.getDouble("flySpeed"));
    p.setWalkSpeed((float) y.getDouble("walkSpeed"));
    p.setFoodLevel(y.getInt("food"));
    p.setSaturation((float) y.getDouble("saturation"));
    p.setExhaustion((float) y.getDouble("exhaustion"));
    p.setHealth(
        Math.max(
            1,
            Math.min(
                y.getDouble("health"),
                p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue())));
    p.setAbsorptionAmount(y.getDouble("absorption"));
    p.setFireTicks(y.getInt("fire"));
    p.setRemainingAir(y.getInt("air"));
    p.setInvulnerable(y.getBoolean("invulnerable"));
    p.setCollidable(y.getBoolean("collidable"));
    p.setFallDistance(0);
    p.setVelocity(new org.bukkit.util.Vector());
    if (y.contains("playerTime"))
      p.setPlayerTime(y.getLong("playerTime"), y.getBoolean("relativeTime"));
    else p.resetPlayerTime();
    if (!y.getString("playerWeather", "WORLD").equals("WORLD"))
      p.setPlayerWeather(WeatherType.valueOf(y.getString("playerWeather")));
    else p.resetPlayerWeather();
    p.setWorldBorder(null);
    p.saveData();
  }

  public void delete(UUID id) throws IOException {
    Files.deleteIfExists(file(id));
  }

  public CompletableFuture<Void> restore(Player p) {
    return plugin
        .io(() -> read(p.getUniqueId()))
        .thenCompose(
            y ->
                plugin
                    .onPlayer(p, () -> origin(y))
                    .thenCompose(location -> p.teleportAsync(location))
                    .thenCompose(
                        ok -> {
                          if (!ok)
                            return CompletableFuture.failedFuture(
                                new IOException("Return teleport was refused; snapshot retained"));
                          return plugin.onPlayer(
                              p,
                              () -> {
                                apply(p, y);
                                return null;
                              });
                        })
                    .thenCompose(
                        ignored ->
                            plugin.io(
                                () -> {
                                  delete(p.getUniqueId());
                                  return null;
                                })));
  }
}

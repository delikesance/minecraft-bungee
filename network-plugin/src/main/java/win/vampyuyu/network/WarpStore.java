package win.vampyuyu.network;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;

final class WarpStore {
  record Warp(
      String name,
      String description,
      UUID owner,
      String ownerName,
      String world,
      double x,
      double y,
      double z,
      float yaw,
      float pitch) {}

  static final class Data {
    Map<String, Warp> warps = new TreeMap<>();
    Map<UUID, Integer> limits = new HashMap<>();
  }

  private final Path path;
  private Data data;
  private static final Gson JSON = new Gson();

  WarpStore(Path path) throws Exception {
    this.path = path;
    data = Files.exists(path) ? JSON.fromJson(Files.readString(path), Data.class) : new Data();
    if (data == null || data.warps == null || data.limits == null)
      throw new IllegalStateException("Invalid warp database");
  }

  synchronized List<Warp> all() {
    return List.copyOf(data.warps.values());
  }

  synchronized Warp get(String name) {
    Warp warp = data.warps.get(name.toLowerCase(Locale.ROOT));
    if (warp == null) throw new IllegalArgumentException("Unknown warp");
    return warp;
  }

  synchronized void create(Warp warp, boolean unlimited) throws Exception {
    if (!warp.name().matches("[A-Za-z0-9_-]{1,32}"))
      throw new IllegalArgumentException("Name: 1–32 letters, digits, _ or -");
    String key = warp.name().toLowerCase(Locale.ROOT);
    if (data.warps.containsKey(key)) throw new IllegalArgumentException("This name already exists");
    long count = data.warps.values().stream().filter(w -> w.owner().equals(warp.owner())).count();
    if (!unlimited && count >= data.limits.getOrDefault(warp.owner(), 1))
      throw new IllegalArgumentException("Your warp quota has been reached");
    data.warps.put(key, warp);
    try {
      save();
    } catch (Exception e) {
      data.warps.remove(key);
      throw e;
    }
  }

  synchronized void edit(String name, UUID actor, boolean admin, String description, boolean delete)
      throws Exception {
    Warp old = get(name);
    if (!admin && !old.owner().equals(actor))
      throw new IllegalArgumentException("This warp belongs to another player");
    if (description.length() > 240 || description.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Description: 240 characters maximum");
    String key = old.name().toLowerCase(Locale.ROOT);
    if (delete) data.warps.remove(key);
    else
      data.warps.put(
          key,
          new Warp(
              old.name(),
              description,
              old.owner(),
              old.ownerName(),
              old.world(),
              old.x(),
              old.y(),
              old.z(),
              old.yaw(),
              old.pitch()));
    try {
      save();
    } catch (Exception e) {
      data.warps.put(key, old);
      throw e;
    }
  }

  synchronized void limit(UUID id, int count) throws Exception {
    if (count < 0) throw new IllegalArgumentException("Quota must be zero or positive");
    Integer previous = data.limits.put(id, count);
    try {
      save();
    } catch (Exception e) {
      if (previous == null) data.limits.remove(id);
      else data.limits.put(id, previous);
      throw e;
    }
  }

  private void save() throws Exception {
    Files.createDirectories(path.getParent());
    Path temp = Files.createTempFile(path.getParent(), "warps-", ".tmp");
    try {
      Files.writeString(temp, JSON.toJson(data));
      try (var ch = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
        ch.force(true);
      }
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}

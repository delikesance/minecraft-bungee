package win.vampyuyu.arena;

import java.util.*;
import org.bukkit.*;

public record ArenaConfig(
    String id,
    String name,
    String worldName,
    String preset,
    Bounds bounds,
    Spawn spawn,
    Set<Material> allowedBreakBlocks,
    Set<Material> allowedPlaceBlocks,
    boolean allowPvP,
    boolean allowChests,
    boolean allowDoors,
    GameMode gameMode,
    int durationSeconds,
    int minPlayers,
    int maxPlayers,
    int discussionSeconds,
    int voteSeconds,
    int nightSeconds,
    GameRules.Werewolf werewolf,
    GameRules.Freeplay freeplay,
    Village village,
    Rewards rewards) {
  public record Door(int x, int y, int z) {}

  public record Village(
      boolean enabled, Map<String, Spawn> houses, Spawn meeting, Spawn daypoint, List<Door> doors) {
    public Village {
      houses = houses == null ? Map.of() : Map.copyOf(houses);
      doors = doors == null ? List.of() : List.copyOf(doors);
      if (houses.size() > 45 || doors.size() > 256)
        throw new IllegalArgumentException("Too many houses or doors");
      Set<String> positions = new HashSet<>();
      for (Spawn p : houses.values())
        if (!positions.add(p.x() + ":" + p.y() + ":" + p.z()))
          throw new IllegalArgumentException("Houses must have distinct positions");
      if (new HashSet<>(doors).size() != doors.size())
        throw new IllegalArgumentException("Duplicate registered door");
      for (String name : houses.keySet())
        if (!name.matches("[A-Za-z0-9_-]{1,32}"))
          throw new IllegalArgumentException("Invalid house name");
    }

    public void requireCapacity(int players) {
      String missing = missing(players);
      if (enabled && missing != null) throw new IllegalArgumentException(missing);
    }

    /** Human-readable list of what village mode still lacks, or null when ready. */
    public String missing(int players) {
      List<String> lacks = new ArrayList<>();
      if (houses.size() < players)
        lacks.add(
            "houses: " + houses.size() + "/" + players + " (one per player, bots included)");
      if (meeting == null) lacks.add("wolf meeting point");
      if (daypoint == null) lacks.add("day point");
      return lacks.isEmpty() ? null : "Village mode is missing " + String.join(", ", lacks);
    }

    public static Village defaults() {
      return new Village(false, Map.of(), null, null, List.of());
    }
  }

  public record Rewards(int participation, int victory) {
    public Rewards {
      if (participation < 0 || victory < 0 || participation > 100000 || victory > 100000)
        throw new IllegalArgumentException("Rewards must be 0–100000");
    }
  }

  // Compatibility for existing integrations and fixtures.
  public ArenaConfig(
      String id,
      String name,
      String worldName,
      String preset,
      Bounds bounds,
      Spawn spawn,
      Set<Material> allowedBreakBlocks,
      Set<Material> allowedPlaceBlocks,
      boolean allowPvP,
      boolean allowChests,
      boolean allowDoors,
      GameMode gameMode,
      int durationSeconds,
      int minPlayers,
      int maxPlayers,
      int discussionSeconds,
      int voteSeconds,
      int nightSeconds,
      GameRules.Werewolf werewolf,
      GameRules.Freeplay freeplay) {
    this(
        id,
        name,
        worldName,
        preset,
        bounds,
        spawn,
        allowedBreakBlocks,
        allowedPlaceBlocks,
        allowPvP,
        allowChests,
        allowDoors,
        gameMode,
        durationSeconds,
        minPlayers,
        maxPlayers,
        discussionSeconds,
        voteSeconds,
        nightSeconds,
        werewolf,
        freeplay,
        null,
        null);
  }

  public record Bounds(
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public Bounds {
      // Either corner may be entered first; retain exact coordinates.
      double x = minX, y = minY, z = minZ;
      minX = Math.min(x, maxX);
      minY = Math.min(y, maxY);
      minZ = Math.min(z, maxZ);
      maxX = Math.max(x, maxX);
      maxY = Math.max(y, maxY);
      maxZ = Math.max(z, maxZ);
      for (double n : new double[] {minX, minY, minZ, maxX, maxY, maxZ})
        if (!Double.isFinite(n) || Math.abs(n) > 29_999_000)
          throw new IllegalArgumentException("Invalid arena coordinate");
      if (maxX - minX < 4
          || maxZ - minZ < 4
          || maxY - minY < 3
          || maxX - minX > 1024
          || maxZ - minZ > 1024
          || maxY - minY > 4096)
        throw new IllegalArgumentException(
            "Bounds must be 4–1024 blocks wide and at least 3 blocks high");
    }

    public boolean contains(double x, double y, double z) {
      return x >= minX + .3
          && x < maxX - .3
          && y >= minY
          && y < maxY - 1.8
          && z >= minZ + .3
          && z < maxZ - .3;
    }

    public boolean overlaps(Bounds b) {
      return minX < b.maxX
          && maxX > b.minX
          && minY < b.maxY
          && maxY > b.minY
          && minZ < b.maxZ
          && maxZ > b.minZ;
    }
  }

  public record Spawn(double x, double y, double z, float yaw, float pitch) {}

  public ArenaConfig {
    village = village == null ? Village.defaults() : village;
    rewards = rewards == null ? new Rewards(10, 5) : rewards;
    if (bounds != null) {
      List<Spawn> points = new ArrayList<>(village.houses().values());
      if (village.meeting() != null) points.add(village.meeting());
      if (village.daypoint() != null) points.add(village.daypoint());
      for (Spawn p : points)
        if (!Double.isFinite(p.x())
            || !Double.isFinite(p.y())
            || !Double.isFinite(p.z())
            || !Float.isFinite(p.yaw())
            || !Float.isFinite(p.pitch())
            || !bounds.contains(p.x(), p.y(), p.z()))
          throw new IllegalArgumentException("Village points must be inside the arena");
      for (Door d : village.doors())
        if (!bounds.contains(d.x() + .5, d.y(), d.z() + .5))
          throw new IllegalArgumentException("Doors must be inside the arena");
    }

    werewolf = werewolf == null ? GameRules.Werewolf.defaults() : werewolf;
    freeplay = freeplay == null ? GameRules.Freeplay.defaults() : freeplay;
    if ("werewolf".equals(preset)
        && !werewolf.roles().isEmpty()
        && werewolf.roles().getOrDefault("WEREWOLF", 0) * 2 >= minPlayers)
      throw new IllegalArgumentException(
          "Wolves must be in the minority at the minimum player count");
    if ("werewolf".equals(preset)
        && !werewolf.roles().isEmpty()
        && werewolf.roles().values().stream().mapToInt(Integer::intValue).sum() > minPlayers)
      throw new IllegalArgumentException("Minimum players must cover the configured role counts");
    if (preset != null && preset.equals("freeplay")) {
      if (freeplay.objective().equals("PVP") && !allowPvP)
        throw new IllegalArgumentException("PvP objectives require player combat enabled");
      if (freeplay.objective().equals("BLOCK_BREAK")
          && (allowedBreakBlocks == null || allowedBreakBlocks.isEmpty()))
        throw new IllegalArgumentException("Choose which blocks score when broken");
      if (freeplay.objective().equals("BLOCK_PLACE")
          && (allowedPlaceBlocks == null || allowedPlaceBlocks.isEmpty()))
        throw new IllegalArgumentException("Choose which blocks score when placed");
      for (var point : freeplay.checkpoints())
        if (bounds == null || !bounds.contains(point.x(), point.y(), point.z()))
          throw new IllegalArgumentException("Checkpoints must be inside the arena");
      for (int a = 0; a < freeplay.checkpoints().size(); a++)
        for (int b = a + 1; b < freeplay.checkpoints().size(); b++) {
          var p = freeplay.checkpoints().get(a);
          var q = freeplay.checkpoints().get(b);
          if (Math.pow(p.x() - q.x(), 2) + Math.pow(p.y() - q.y(), 2) + Math.pow(p.z() - q.z(), 2)
              < Math.pow(p.radius() + q.radius(), 2))
            throw new IllegalArgumentException("Checkpoint areas must not overlap");
        }
    }
    if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,39}"))
      throw new IllegalArgumentException(
          "Arena ID must use lowercase letters, digits, _ or - (40 characters maximum)");
    if (name == null
        || name.isBlank()
        || name.length() > 80
        || name.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid arena name");
    if (worldName == null || worldName.isBlank() || worldName.length() > 128)
      throw new IllegalArgumentException("A loaded world is required");
    if (preset == null || !preset.matches("[a-z0-9_-]{1,40}"))
      throw new IllegalArgumentException("Unknown preset");
    if (bounds == null
        || spawn == null
        || !Double.isFinite(spawn.x)
        || !Double.isFinite(spawn.y)
        || !Double.isFinite(spawn.z)
        || !Float.isFinite(spawn.yaw)
        || !Float.isFinite(spawn.pitch)
        || Math.abs(spawn.pitch) > 90
        || !bounds.contains(spawn.x, spawn.y, spawn.z))
      throw new IllegalArgumentException("Spawn must be inside the arena bounds");
    if (gameMode == null || gameMode == GameMode.SPECTATOR)
      throw new IllegalArgumentException("Choose survival, creative or adventure");
    if (durationSeconds < 60
        || durationSeconds > 14400
        || minPlayers < 1
        || maxPlayers < minPlayers
        || maxPlayers > 45
        || (preset.equals("werewolf") && minPlayers < 4))
      throw new IllegalArgumentException(
          "Invalid duration or player limits (Werewolf needs at least 4 players)");
    if (discussionSeconds < 5
        || discussionSeconds > 600
        || voteSeconds < 5
        || voteSeconds > 300
        || nightSeconds < 5
        || nightSeconds > 300) throw new IllegalArgumentException("Invalid phase durations");
    if (allowedBreakBlocks != null && allowedBreakBlocks.stream().anyMatch(Objects::isNull)
        || allowedPlaceBlocks != null && allowedPlaceBlocks.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Unknown block material in allowlist");
    allowedBreakBlocks = allowedBreakBlocks == null ? Set.of() : Set.copyOf(allowedBreakBlocks);
    allowedPlaceBlocks = allowedPlaceBlocks == null ? Set.of() : Set.copyOf(allowedPlaceBlocks);
    if (allowedBreakBlocks.size() > 128
        || allowedPlaceBlocks.size() > 128
        || allowedBreakBlocks.stream().anyMatch(m -> !m.isBlock())
        || allowedPlaceBlocks.stream().anyMatch(m -> !m.isBlock()))
      throw new IllegalArgumentException(
          "Block allowlists must contain at most 128 block materials");
    // Werewolf is a social deduction game; PvP must not bypass the secret ballot.
    if (preset.equals("werewolf") && allowPvP)
      throw new IllegalArgumentException("Werewolf requires PvP disabled");
  }

  public Location location() {
    World w = Bukkit.getWorld(worldName);
    if (w == null) throw new IllegalArgumentException("Arena world is not loaded");
    return new Location(w, spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch);
  }
}

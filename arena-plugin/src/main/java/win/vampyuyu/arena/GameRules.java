package win.vampyuyu.arena;

import java.util.*;
import org.bukkit.Material;

/** Versioned, bounded gameplay settings persisted alongside each arena. */
public final class GameRules {
  private GameRules() {}

  public record Werewolf(
      Map<String, Integer> roles,
      int cupidSeconds,
      int witchSeconds,
      int hunterSeconds,
      Boolean ambienceEnabled) {
    public Werewolf(
        Map<String, Integer> roles, int cupidSeconds, int witchSeconds, int hunterSeconds) {
      this(roles, cupidSeconds, witchSeconds, hunterSeconds, true);
    }

    public Werewolf {
      ambienceEnabled = ambienceEnabled == null ? true : ambienceEnabled;
      roles = roles == null ? Map.of() : Map.copyOf(roles);
      for (var e : roles.entrySet()) {
        try {
          WerewolfGame.Role.valueOf(e.getKey());
        } catch (Exception ex) {
          throw new IllegalArgumentException("Unknown Werewolf role");
        }
        if (e.getValue() == null
            || e.getValue() < 0
            || e.getValue() > 20
            || !e.getKey().equals("VILLAGER") && !e.getKey().equals("WEREWOLF") && e.getValue() > 1)
          throw new IllegalArgumentException("Special roles may appear at most once");
      }
      if (!roles.isEmpty() && roles.getOrDefault("WEREWOLF", 0) < 1)
        throw new IllegalArgumentException("Custom roles need at least one werewolf");
      if (cupidSeconds < 5
          || cupidSeconds > 120
          || witchSeconds < 5
          || witchSeconds > 120
          || hunterSeconds < 5
          || hunterSeconds > 120)
        throw new IllegalArgumentException("Special-role phases must last 5–120 seconds");
    }

    public static Werewolf defaults() {
      return new Werewolf(Map.of(), 15, 15, 15);
    }
  }

  public record Checkpoint(double x, double y, double z, double radius) {
    public Checkpoint {
      if (!Double.isFinite(x)
          || !Double.isFinite(y)
          || !Double.isFinite(z)
          || !Double.isFinite(radius)
          || radius < 1
          || radius > 8) throw new IllegalArgumentException("Checkpoint radius must be 1–8 blocks");
    }
  }

  public record Freeplay(
      String objective,
      int targetScore,
      int pointsPerEvent,
      boolean allowFlight,
      boolean hunger,
      long personalTime,
      String weather,
      Map<String, Integer> kit,
      Map<String, Integer> effects,
      List<Checkpoint> checkpoints,
      String instructions) {
    public Freeplay {
      if (!Set.of("SANDBOX", "PVP", "BLOCK_BREAK", "BLOCK_PLACE", "RACE")
          .contains(objective == null ? "" : objective))
        throw new IllegalArgumentException("Unknown free-play objective");
      if (targetScore < 1 || targetScore > 1000 || pointsPerEvent < 1 || pointsPerEvent > 100)
        throw new IllegalArgumentException("Invalid score settings");
      if (personalTime < -1
          || personalTime > 23999
          || !Set.of("WORLD", "CLEAR", "DOWNFALL").contains(weather == null ? "" : weather))
        throw new IllegalArgumentException("Invalid personal time or weather");
      kit = kit == null ? Map.of() : Map.copyOf(kit);
      effects = effects == null ? Map.of() : Map.copyOf(effects);
      checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
      if (kit.size() > 24 || effects.size() > 6 || checkpoints.size() > 20)
        throw new IllegalArgumentException("Too many kit items, effects or checkpoints");
      for (var e : kit.entrySet()) {
        Material mat = Material.matchMaterial(e.getKey());
        if (mat == null
            || !mat.isItem()
            || mat.isAir()
            || e.getValue() == null
            || e.getValue() < 1
            || e.getValue() > mat.getMaxStackSize())
          throw new IllegalArgumentException("Invalid kit material or stack size: " + e.getKey());
      }
      for (var e : effects.entrySet())
        if (!Set.of("speed", "jump_boost", "strength", "regeneration", "night_vision", "haste")
                .contains(e.getKey())
            || e.getValue() == null
            || e.getValue() < 1
            || e.getValue() > 5) throw new IllegalArgumentException("Effect levels must be 1–5");
      if (objective.equals("RACE") && checkpoints.size() < 2)
        throw new IllegalArgumentException("A race requires at least two checkpoints");
      if (instructions == null || instructions.length() > 600)
        throw new IllegalArgumentException("Instructions must contain at most 600 characters");
    }

    public static Freeplay defaults() {
      return new Freeplay(
          "SANDBOX",
          10,
          1,
          false,
          false,
          -1,
          "WORLD",
          Map.of(),
          Map.of(),
          List.of(),
          "Enjoy the arena. Use your compass to follow the game or leave.");
    }
  }
}

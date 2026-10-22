package win.vampyuyu.arena;

import java.util.*;

/**
 * Pure game state. ArenaManager owns lifecycle; game implementations never mutate Bukkit entities.
 */
public interface GamePreset {
  String phase();

  String role(UUID player);

  Set<UUID> alive();

  Map<UUID, Integer> votes(UUID viewer);

  String select(UUID voter, UUID target);

  void remove(UUID player);

  void advance();

  String winner();

  int seconds();

  default Map<String, String> abilities(UUID player) {
    return Map.of();
  }

  default String use(UUID player, String ability) {
    throw new IllegalArgumentException("Ability unavailable");
  }

  default String help(UUID player) {
    return "Open a player head to use the current action.";
  }

  default Map<UUID, Integer> scores() {
    return Map.of();
  }

  default void event(UUID player, String event) {}

  default void visit(UUID player, double x, double y, double z) {}

  /** Private messages the game wants delivered right now, once (e.g. Cupid's arrow). */
  default Map<UUID, String> drainNotices() {
    return Map.of();
  }

  default void finish() {}
}

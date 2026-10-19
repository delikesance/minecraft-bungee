package win.vampyuyu.arena;

import java.util.*;

/** Configurable score/race objectives; scoring comes only from validated engine events. */
public final class FreeplayGame implements GamePreset {
  private final Set<UUID> alive;
  private final Map<UUID, Integer> scores = new LinkedHashMap<>(), nextCheckpoint = new HashMap<>();
  private final GameRules.Freeplay settings;
  private String winner = "";

  public FreeplayGame(Collection<UUID> players) {
    this(players, GameRules.Freeplay.defaults());
  }

  public FreeplayGame(Collection<UUID> players, GameRules.Freeplay settings) {
    this.settings = settings;
    alive = new HashSet<>(players);
    players.forEach(p -> scores.put(p, 0));
  }

  public synchronized String phase() {
    return winner.isEmpty() ? "FREEPLAY" : "FINISHED";
  }

  public String role(UUID p) {
    return "PLAYER";
  }

  public synchronized Set<UUID> alive() {
    return Set.copyOf(alive);
  }

  public Map<UUID, Integer> votes(UUID p) {
    return Map.of();
  }

  public String select(UUID p, UUID t) {
    throw new IllegalArgumentException("This preset has no voting");
  }

  public synchronized void remove(UUID p) {
    alive.remove(p);
    scores.remove(p);
    nextCheckpoint.remove(p);
  }

  public void advance() {}

  public synchronized String winner() {
    return winner;
  }

  public int seconds() {
    return 14400;
  }

  public String help(UUID p) {
    return settings.instructions()
        + " Objective: "
        + settings.objective()
        + ". Target: "
        + goal()
        + " points.";
  }

  public synchronized Map<UUID, Integer> scores() {
    return Map.copyOf(scores);
  }

  public int goal() {
    return settings.objective().equals("RACE")
        ? settings.targetScore() * settings.checkpoints().size()
        : settings.targetScore();
  }

  public synchronized void event(UUID p, String event) {
    if (!alive.contains(p) || !winner.isEmpty() || !settings.objective().equals(event)) return;
    award(p, settings.pointsPerEvent());
  }

  private void award(UUID p, int points) {
    int total = scores.merge(p, points, Integer::sum);
    if (total >= goal()) winner = p.toString();
  }

  public synchronized void visit(UUID p, double x, double y, double z) {
    if (!settings.objective().equals("RACE") || !alive.contains(p) || !winner.isEmpty()) return;
    int index = nextCheckpoint.getOrDefault(p, 0);
    var point = settings.checkpoints().get(index);
    if (Math.pow(x - point.x(), 2) + Math.pow(y - point.y(), 2) + Math.pow(z - point.z(), 2)
        <= point.radius() * point.radius()) {
      nextCheckpoint.put(p, (index + 1) % settings.checkpoints().size());
      award(p, 1);
    }
  }

  public synchronized void finish() {
    if (!winner.isEmpty() || settings.objective().equals("SANDBOX")) return;
    int best = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    var top = scores.entrySet().stream().filter(e -> e.getValue() == best).toList();
    winner = best > 0 && top.size() == 1 ? top.getFirst().getKey().toString() : "DRAW";
  }
}

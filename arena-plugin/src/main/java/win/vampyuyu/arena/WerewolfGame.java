package win.vampyuyu.arena;

import java.util.*;

/** Pure, synchronized social-deduction state. No Bukkit/world access. */
public final class WerewolfGame implements GamePreset {
  public enum Role {
    VILLAGER,
    WEREWOLF,
    SEER,
    CUPID,
    HUNTER,
    WITCH,
    GUARD
  }

  public enum Phase {
    CUPID,
    DISCUSSION,
    DAY_VOTE,
    NIGHT,
    WITCH,
    HUNTER_SHOT,
    FINISHED
  }

  private final Map<UUID, Role> roles = new LinkedHashMap<>();
  private final Set<UUID> alive = new LinkedHashSet<>(),
      visions = new HashSet<>(),
      healUsed = new HashSet<>(),
      poisonUsed = new HashSet<>();
  private final Map<UUID, UUID> ballots = new HashMap<>(),
      lovers = new HashMap<>(),
      guards = new HashMap<>(),
      lastGuard = new HashMap<>(),
      poisons = new HashMap<>();
  private final Set<UUID> heals = new HashSet<>();
  private final Deque<UUID> hunters = new ArrayDeque<>();
  private final int discussion, vote, night;
  private final GameRules.Werewolf settings;
  private UUID firstLover, nightVictim;
  private Phase phase = Phase.DISCUSSION, resume = Phase.DISCUSSION;
  private String winner = "";

  /** Classic composition retained as an explicit constructor for integrations/tests. */
  public WerewolfGame(
      Collection<UUID> players, int discussion, int vote, int night, Random random) {
    this(players, discussion, vote, night, random, null);
  }

  public WerewolfGame(
      Collection<UUID> players,
      int discussion,
      int vote,
      int night,
      Random random,
      GameRules.Werewolf settings) {
    if (players.size() < 4
        || players.size() > 45
        || new HashSet<>(players).size() != players.size())
      throw new IllegalArgumentException("Werewolf requires 4–45 distinct participants");
    this.discussion = discussion;
    this.vote = vote;
    this.night = night;
    this.settings = settings == null ? GameRules.Werewolf.defaults() : settings;
    List<Role> deck = new ArrayList<>();
    if (settings != null && !settings.roles().isEmpty()) {
      for (Role role : Role.values())
        for (int i = 0; i < settings.roles().getOrDefault(role.name(), 0); i++) deck.add(role);
    } else {
      for (int i = 0; i < Math.max(1, players.size() / 4); i++) deck.add(Role.WEREWOLF);
      deck.add(Role.SEER);
      if (settings != null)
        for (Role special : List.of(Role.CUPID, Role.HUNTER, Role.WITCH, Role.GUARD))
          if (deck.size() < players.size()) deck.add(special);
    }
    long wolves = deck.stream().filter(r -> r == Role.WEREWOLF).count();
    if (deck.size() > players.size() || wolves < 1 || wolves * 2 >= players.size())
      throw new IllegalArgumentException(
          "Role composition must fit the players and start with fewer wolves than villagers");
    while (deck.size() < players.size()) deck.add(Role.VILLAGER);
    Collections.shuffle(deck, random);
    int i = 0;
    for (UUID id : players) roles.put(id, deck.get(i++));
    alive.addAll(players);
    if (roles.containsValue(Role.CUPID)) phase = Phase.CUPID;
  }

  public synchronized String phase() {
    return phase.name();
  }

  public synchronized String role(UUID p) {
    return roles.getOrDefault(p, Role.VILLAGER).name();
  }

  public synchronized Set<UUID> alive() {
    return Set.copyOf(alive);
  }

  public synchronized String winner() {
    return winner;
  }

  public synchronized int seconds() {
    return switch (phase) {
      case CUPID -> settings.cupidSeconds();
      case DISCUSSION -> discussion;
      case DAY_VOTE -> vote;
      case NIGHT -> night;
      case WITCH -> settings.witchSeconds();
      case HUNTER_SHOT -> settings.hunterSeconds();
      case FINISHED -> 0;
    };
  }

  public synchronized Map<UUID, Integer> votes(UUID viewer) {
    if (phase != Phase.DAY_VOTE && !(phase == Phase.NIGHT && roles.get(viewer) == Role.WEREWOLF))
      return Map.of();
    Map<UUID, Integer> totals = new HashMap<>();
    ballots.values().forEach(p -> totals.merge(p, 1, Integer::sum));
    return Map.copyOf(totals);
  }

  public synchronized String help(UUID p) {
    String bond =
        lovers.containsKey(p)
            ? " You are in love with " + lovers.get(p) + "; if either dies, both die."
            : "";
    return (switch (roles.getOrDefault(p, Role.VILLAGER)) {
          case CUPID -> "Opening phase: select two living players to bind as lovers.";
          case HUNTER -> "If eliminated, choose one living player for your final shot.";
          case WITCH ->
              "Potion phase: use Heal to save the wolf victim; click a head to poison once per"
                  + " game.";
          case GUARD ->
              "At night, protect one player, including yourself. Never the same target twice in a"
                  + " row.";
          case SEER -> "At night, inspect one other living player.";
          case WEREWOLF -> "At night, vote to hunt a non-wolf. Night chat is private to wolves.";
          default -> "Discuss by day, then vote to identify the wolves.";
        })
        + bond;
  }

  public synchronized Map<String, String> abilities(UUID p) {
    if (phase == Phase.WITCH
        && alive.contains(p)
        && roles.get(p) == Role.WITCH
        && !healUsed.contains(p)
        && nightVictim != null
        && !heals.contains(p)) return Map.of("heal", "Use healing potion · save " + nightVictim);
    return Map.of();
  }

  public synchronized String use(UUID p, String ability) {
    if (!ability.equals("heal") || !abilities(p).containsKey("heal"))
      throw new IllegalArgumentException("Healing potion unavailable");
    heals.add(p);
    healUsed.add(p);
    return "Healing potion used. The wolf victim will survive this night.";
  }

  public synchronized String select(UUID voter, UUID target) {
    if (!alive.contains(target)) throw new IllegalArgumentException("Select a living player");
    if (phase == Phase.HUNTER_SHOT && Objects.equals(hunters.peekFirst(), voter)) {
      hunters.removeFirst();
      eliminate(target, true);
      resumeAfterHunters();
      return "Your final shot has been fired.";
    }
    if (!alive.contains(voter)) throw new IllegalArgumentException("Eliminated players cannot act");
    Role role = roles.get(voter);
    if (phase == Phase.CUPID && role == Role.CUPID) {
      if (!lovers.isEmpty())
        throw new IllegalArgumentException("The lovers have already been chosen");
      if (firstLover == null) {
        firstLover = target;
        return "First lover selected. Choose a different second lover.";
      }
      if (firstLover.equals(target))
        throw new IllegalArgumentException("Choose a different second lover");
      lovers.put(firstLover, target);
      lovers.put(target, firstLover);
      phase = Phase.DISCUSSION;
      return "The lovers are bound. Only they know their bond.";
    }
    if (phase == Phase.NIGHT && role == Role.GUARD) {
      if (Objects.equals(lastGuard.get(voter), target))
        throw new IllegalArgumentException(
            "You cannot protect the same player on consecutive nights");
      guards.put(voter, target);
      return "Protection chosen. You may change it before dawn.";
    }
    if (phase == Phase.WITCH && role == Role.WITCH) {
      if (!poisonUsed.add(voter))
        throw new IllegalArgumentException("Your poison potion has already been used");
      poisons.put(voter, target);
      return "Poison potion used. Your target will die at dawn.";
    }
    if (voter.equals(target)) throw new IllegalArgumentException("Select another living player");
    if (phase == Phase.NIGHT && role == Role.SEER) {
      if (!visions.add(voter))
        throw new IllegalArgumentException("You already used your vision tonight");
      return "Your vision: " + target + " is " + roles.get(target) + ".";
    }
    if (phase != Phase.DAY_VOTE && (phase != Phase.NIGHT || role != Role.WEREWOLF))
      throw new IllegalArgumentException("You cannot vote during this phase");
    if (phase == Phase.NIGHT && roles.get(target) == Role.WEREWOLF)
      throw new IllegalArgumentException("Wolves cannot target another wolf");
    ballots.put(voter, target);
    return "Vote recorded. You may change it until the phase ends.";
  }

  /** Practice participants act through the same rules as humans, without revealing secrets. */
  public synchronized void simulateTurn(Set<UUID> bots, Random random) {
    Phase initial = phase;
    List<UUID> actors = new ArrayList<>(bots);
    Collections.shuffle(actors, random);
    for (UUID bot : actors) {
      if (phase != initial) return;
      if (!alive.contains(bot)
          && !(phase == Phase.HUNTER_SHOT && Objects.equals(hunters.peekFirst(), bot))) continue;
      if (phase == Phase.WITCH) {
        if (abilities(bot).containsKey("heal") && random.nextBoolean()) use(bot, "heal");
        if (random.nextInt(4) != 0) continue;
      }
      int choices = phase == Phase.CUPID ? 2 : 1;
      for (int choice = 0; choice < choices && phase == initial; choice++) {
        List<UUID> targets = new ArrayList<>(alive);
        Collections.shuffle(targets, random);
        for (UUID target : targets) {
          try {
            select(bot, target);
            break;
          } catch (IllegalArgumentException unavailable) {
            /* Try the next legal target. */
          }
        }
      }
    }
  }

  public synchronized void remove(UUID p) {
    eliminate(p, false);
    hunters.remove(p);
    ballots.remove(p);
    ballots.values().removeIf(p::equals);
    guards.remove(p);
    poisons.remove(p);
    if (phase == Phase.HUNTER_SHOT) resumeAfterHunters();
    else checkWinner();
  }

  private UUID majority() {
    Map<UUID, Integer> totals = new HashMap<>();
    ballots.values().stream()
        .filter(alive::contains)
        .forEach(p -> totals.merge(p, 1, Integer::sum));
    int max = totals.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    List<UUID> top =
        totals.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
    return top.size() == 1 ? top.getFirst() : null;
  }

  private void eliminate(UUID p, boolean shot) {
    if (p == null || !alive.remove(p)) return;
    if (shot && roles.get(p) == Role.HUNTER) hunters.addLast(p);
    UUID partner = lovers.get(p);
    if (partner != null) eliminate(partner, true);
  }

  private void nightDeaths() {
    if (heals.isEmpty()) eliminate(nightVictim, true);
    for (UUID target : poisons.values()) eliminate(target, true);
    lastGuard.clear();
    lastGuard.putAll(guards);
    guards.clear();
    poisons.clear();
    heals.clear();
    nightVictim = null;
    continueWith(Phase.DISCUSSION);
  }

  private void continueWith(Phase next) {
    resume = next;
    if (!hunters.isEmpty()) phase = Phase.HUNTER_SHOT;
    else {
      phase = next;
      checkWinner();
    }
  }

  private void resumeAfterHunters() {
    if (hunters.isEmpty()) {
      phase = resume;
      checkWinner();
    }
  }

  public synchronized void advance() {
    switch (phase) {
      case FINISHED -> {
        return;
      }
      case CUPID -> phase = Phase.DISCUSSION;
      case DISCUSSION -> phase = Phase.DAY_VOTE;
      case DAY_VOTE -> {
        eliminate(majority(), true);
        ballots.clear();
        visions.clear();
        continueWith(Phase.NIGHT);
      }
      case NIGHT -> {
        nightVictim = majority();
        ballots.clear();
        if (guards.containsValue(nightVictim)) nightVictim = null;
        boolean witches =
            alive.stream()
                .anyMatch(
                    p ->
                        roles.get(p) == Role.WITCH
                            && (!healUsed.contains(p) || !poisonUsed.contains(p)));
        if (witches) phase = Phase.WITCH;
        else nightDeaths();
      }
      case WITCH -> nightDeaths();
      case HUNTER_SHOT -> {
        hunters.pollFirst();
        resumeAfterHunters();
      }
    }
  }

  private void checkWinner() {
    if (!hunters.isEmpty()) return;
    if (alive.isEmpty()) {
      winner = "DRAW";
      phase = Phase.FINISHED;
      return;
    }
    if (alive.size() == 2) {
      UUID a = alive.iterator().next(), b = lovers.get(a);
      if (b != null
          && alive.contains(b)
          && (roles.get(a) == Role.WEREWOLF) != (roles.get(b) == Role.WEREWOLF)) {
        winner = "LOVERS";
        phase = Phase.FINISHED;
        return;
      }
    }
    long wolves = alive.stream().filter(p -> roles.get(p) == Role.WEREWOLF).count();
    if (wolves == 0) {
      winner = "VILLAGE";
      phase = Phase.FINISHED;
    } else if (wolves >= alive.size() - wolves) {
      winner = "WEREWOLVES";
      phase = Phase.FINISHED;
    }
  }
}

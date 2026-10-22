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
    GUARD,
    SEER,
    NIGHT,
    WITCH,
    HUNTER_SHOT,
    FINISHED
  }

  /** Opening words of the lovers' notice; the GUI dresses only these notices as a love title. */
  static final String LOVE_NOTICE = "Cupid's arrow struck you";

  /** Automatic cast: each special role joins once the table reaches this many players. */
  private static final List<Map.Entry<Role, Integer>> UNLOCK =
      List.of(
          Map.entry(Role.WITCH, 6),
          Map.entry(Role.HUNTER, 7),
          Map.entry(Role.CUPID, 8),
          Map.entry(Role.GUARD, 10));

  private final Map<UUID, Role> roles = new LinkedHashMap<>();
  private final Set<UUID> alive = new LinkedHashSet<>(),
      visions = new HashSet<>(),
      healUsed = new HashSet<>(),
      poisonUsed = new HashSet<>();
  private final Map<UUID, UUID> ballots = new HashMap<>(),
      lovers = new HashMap<>(),
      guards = new HashMap<>(),
      lastGuard = new HashMap<>(),
      poisons = new HashMap<>(),
      poisonAims = new HashMap<>();
  private final Set<UUID> heals = new HashSet<>();
  private final Deque<UUID> hunters = new ArrayDeque<>();
  private final int discussion, vote, night;
  private final GameRules.Werewolf settings;
  private UUID firstLover, nightVictim;
  private boolean victimGuarded;
  private final Map<UUID, String> notices = new HashMap<>();
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
        for (var unlock : UNLOCK)
          if (players.size() >= unlock.getValue() && deck.size() < players.size())
            deck.add(unlock.getKey());
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
    // Like the card game, the story opens at night: Cupid first, then the usual night turns.
    phase = roles.containsValue(Role.CUPID) ? Phase.CUPID : firstNightPhase();
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
      case GUARD, SEER, NIGHT -> night;
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

  private static String loveNotice(UUID partner) {
    return LOVE_NOTICE
        + ": you are in love with "
        + partner
        + ". If either of you dies, both die. Only you two know it.";
  }

  @Override
  public synchronized Map<UUID, String> drainNotices() {
    Map<UUID, String> out = Map.copyOf(notices);
    notices.clear();
    return out;
  }

  public synchronized String help(UUID p) {
    String bond =
        lovers.containsKey(p)
            ? " You are in love with " + lovers.get(p) + "; if either dies, both die."
            : "";
    return (switch (roles.getOrDefault(p, Role.VILLAGER)) {
          case CUPID -> "Opening night: select two living players to bind as lovers.";
          case HUNTER -> "If eliminated, choose one living player for your final shot.";
          case WITCH ->
              "Potion phase: while you hold the healing potion, you see the wolves' victim and can"
                  + " save them; click a head twice to poison. Each potion works once per game.";
          case GUARD ->
              "At night, protect one player, including yourself. Never the same target twice in a"
                  + " row. Your choice is final.";
          case SEER -> "At night, inspect one other living player.";
          case WEREWOLF ->
              "At night, vote with the pack for a non-wolf. The hunt ends as soon as every wolf"
                  + " picks the same victim. There is no night chat.";
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
        && alive.contains(nightVictim)
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
      notices.put(firstLover, loveNotice(target));
      notices.put(target, loveNotice(firstLover));
      phase = firstNightPhase();
      checkWinner(); // Binding the last two players as a mixed couple ends the story at once.
      return "The lovers are bound. Only they know their bond.";
    }
    if (phase == Phase.GUARD && role == Role.GUARD) {
      if (Objects.equals(lastGuard.get(voter), target))
        throw new IllegalArgumentException(
            "You cannot protect the same player on consecutive nights");
      guards.put(voter, target);
      advance();
      return "Protection chosen: " + target + " is safe from the wolves tonight.";
    }
    if (phase == Phase.WITCH && role == Role.WITCH) {
      if (poisonUsed.contains(voter))
        throw new IllegalArgumentException("Your poison potion has already been used");
      if (!target.equals(poisonAims.put(voter, target)))
        return "Click " + target + " again to confirm the poison. It cannot be undone.";
      poisonAims.remove(voter);
      poisonUsed.add(voter);
      poisons.put(voter, target);
      return "Poison potion used. Your target will die at dawn.";
    }
    if (voter.equals(target)) throw new IllegalArgumentException("Select another living player");
    if (phase == Phase.SEER && role == Role.SEER) {
      if (!visions.add(voter))
        throw new IllegalArgumentException("You already used your vision tonight");
      String vision = "Your vision: " + target + " is " + roles.get(target) + ".";
      advance();
      return vision;
    }
    if (phase != Phase.DAY_VOTE && (phase != Phase.NIGHT || role != Role.WEREWOLF))
      throw new IllegalArgumentException("You cannot vote during this phase");
    if (phase == Phase.NIGHT && roles.get(target) == Role.WEREWOLF)
      throw new IllegalArgumentException("Wolves cannot target another wolf");
    ballots.put(voter, target);
    if (phase == Phase.NIGHT && packAgrees()) {
      advance();
      return "The pack agrees: " + target + " is tonight's prey.";
    }
    return phase == Phase.NIGHT
        ? "Vote recorded. The hunt ends once every wolf picks the same victim."
        : "Vote recorded. You may change it until the phase ends.";
  }

  /** Every living wolf has voted, and all for the same victim. */
  private boolean packAgrees() {
    List<UUID> pack = alive.stream().filter(p -> roles.get(p) == Role.WEREWOLF).toList();
    return !pack.isEmpty()
        && pack.stream().allMatch(ballots::containsKey)
        && pack.stream().map(ballots::get).distinct().count() == 1;
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
        if (roles.get(bot) != Role.WITCH) continue;
        if (abilities(bot).containsKey("heal") && random.nextBoolean()) use(bot, "heal");
        List<UUID> targets = new ArrayList<>(alive);
        targets.remove(bot);
        if (poisonUsed.contains(bot) || targets.isEmpty() || random.nextInt(4) != 0) continue;
        UUID target = targets.get(random.nextInt(targets.size()));
        select(bot, target);
        select(bot, target); // The second click confirms the poison.
        continue;
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

  /** The arena's time limit ran out: nobody wins and every menu unlocks. */
  @Override
  public synchronized void finish() {
    phase = Phase.FINISHED;
  }

  public synchronized void remove(UUID p) {
    eliminate(p, false);
    hunters.remove(p);
    guards.remove(p);
    poisons.remove(p);
    poisonAims.remove(p);
    if (p.equals(firstLover)) firstLover = null;
    if (phase == Phase.HUNTER_SHOT) resumeAfterHunters();
    // A departing lover can take a hunter down with them: let them shoot now, then carry on.
    else if (!hunters.isEmpty() && phase != Phase.FINISHED) continueWith(phase);
    else checkWinner();
    skipUnavailable();
    if (phase == Phase.NIGHT && packAgrees()) advance();
  }

  private void skipUnavailable() {
    if (phase == Phase.CUPID && !hasLiving(Role.CUPID)) phase = firstNightPhase();
    if (phase == Phase.GUARD && !hasLiving(Role.GUARD))
      phase = hasLiving(Role.SEER) ? Phase.SEER : Phase.NIGHT;
    if (phase == Phase.SEER && !hasLiving(Role.SEER)) phase = Phase.NIGHT;
    // A living witch is always called, even with empty flasks, so her turn reveals nothing.
    if (phase == Phase.WITCH && !hasLiving(Role.WITCH)) nightDeaths();
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
    // Only the living vote, and only for the living.
    ballots.remove(p);
    ballots.values().removeIf(p::equals);
    if (shot && roles.get(p) == Role.HUNTER) hunters.addLast(p);
    UUID partner = lovers.get(p);
    if (partner != null) eliminate(partner, true);
  }

  private void nightDeaths() {
    if (heals.isEmpty() && !victimGuarded) eliminate(nightVictim, true);
    for (UUID target : poisons.values()) eliminate(target, true);
    lastGuard.clear();
    lastGuard.putAll(guards);
    guards.clear();
    poisons.clear();
    poisonAims.clear();
    heals.clear();
    nightVictim = null;
    victimGuarded = false;
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
      skipUnavailable();
      if (phase == Phase.NIGHT && packAgrees()) advance();
    }
  }

  public synchronized void advance() {
    switch (phase) {
      case FINISHED -> {
        return;
      }
      case CUPID -> {
        if (lovers.isEmpty())
          for (UUID cupid : alive)
            if (roles.get(cupid) == Role.CUPID)
              notices.put(cupid, "Time ran out before you chose two lovers: nobody is in love.");
        firstLover = null;
        phase = firstNightPhase();
      }
      case DISCUSSION -> phase = Phase.DAY_VOTE;
      case DAY_VOTE -> {
        eliminate(majority(), true);
        ballots.clear();
        visions.clear();
        continueWith(firstNightPhase());
      }
      case GUARD -> phase = hasLiving(Role.SEER) ? Phase.SEER : Phase.NIGHT;
      case SEER -> phase = Phase.NIGHT;
      case NIGHT -> {
        // The witch is shown whoever the pack chose, even if the guard will save them.
        nightVictim = majority();
        ballots.clear();
        victimGuarded = nightVictim != null && guards.containsValue(nightVictim);
        if (hasLiving(Role.WITCH)) phase = Phase.WITCH;
        else nightDeaths();
      }
      case WITCH -> nightDeaths();
      case HUNTER_SHOT -> {
        hunters.pollFirst();
        resumeAfterHunters();
      }
    }
  }

  private boolean hasLiving(Role role) {
    return alive.stream().anyMatch(p -> roles.get(p) == role);
  }

  private Phase firstNightPhase() {
    return hasLiving(Role.GUARD) ? Phase.GUARD : hasLiving(Role.SEER) ? Phase.SEER : Phase.NIGHT;
  }

  /** Participants entitled to private phase instructions; the pending hunter may be dead. */
  public synchronized Set<UUID> actors() {
    if (phase == Phase.HUNTER_SHOT) {
      UUID hunter = hunters.peekFirst();
      return hunter == null ? Set.of() : Set.of(hunter);
    }
    if (phase == Phase.DISCUSSION || phase == Phase.DAY_VOTE) return Set.copyOf(alive);
    Role active =
        switch (phase) {
          case CUPID -> Role.CUPID;
          case GUARD -> Role.GUARD;
          case SEER -> Role.SEER;
          case NIGHT -> Role.WEREWOLF;
          case WITCH -> Role.WITCH;
          default -> null;
        };
    Set<UUID> result = new HashSet<>();
    for (UUID p : alive) if (roles.get(p) == active) result.add(p);
    return Set.copyOf(result);
  }

  public synchronized boolean sleeping(UUID p) {
    if (!alive.contains(p)) return false;
    return switch (phase) {
      case CUPID -> roles.get(p) != Role.CUPID;
      case GUARD -> roles.get(p) != Role.GUARD;
      case SEER -> roles.get(p) != Role.SEER;
      case NIGHT -> roles.get(p) != Role.WEREWOLF;
      case WITCH -> roles.get(p) != Role.WITCH;
      case HUNTER_SHOT -> !Objects.equals(hunters.peekFirst(), p);
      default -> false;
    };
  }

  public synchronized Set<UUID> winners() {
    Set<UUID> result = new HashSet<>();
    for (UUID p : roles.keySet())
      if (winner.equals("LOVERS")
          ? alive.contains(p) && lovers.containsKey(p)
          : winner.equals("VILLAGE")
              ? roles.get(p) != Role.WEREWOLF
              : winner.equals("WEREWOLVES") && roles.get(p) == Role.WEREWOLF) result.add(p);
    return Set.copyOf(result);
  }

  private void checkWinner() {
    if (phase == Phase.FINISHED || !hunters.isEmpty()) return;
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
    } else if (wolves == alive.size()) {
      // As in the card game, the wolves win only once every villager is dead.
      winner = "WEREWOLVES";
      phase = Phase.FINISHED;
    }
  }
}

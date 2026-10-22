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
      poisons = new HashMap<>();
  private final Set<UUID> heals = new HashSet<>(), abstained = new HashSet<>();

  /** Every vision a seer had, oldest first: the menu keeps showing them on later nights. */
  private final Map<UUID, Map<UUID, Role>> seen = new HashMap<>();

  /**
   * Heads picked in a menu before a confirming button (Cupid's two lovers, the hunter's target, the
   * witch's poison target). They belong to the phase they were picked in.
   */
  private final Map<UUID, List<UUID>> aims = new HashMap<>();

  private Phase aimPhase;
  private int dawns;
  private final Deque<UUID> hunters = new ArrayDeque<>();
  private final int discussion, vote, night;
  private final GameRules.Werewolf settings;
  private UUID firstLover, nightVictim;
  /** The last day vote's outcome, until the presentation reads it. */
  private Verdict verdict;
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

  /**
   * Names the current turn: it changes whenever someone new must act, including one hunter's shot
   * followed by another's, where the phase name stays the same.
   */
  public synchronized String turnKey() {
    return phase + ":" + dawns + ":" + (phase == Phase.HUNTER_SHOT ? hunters.peekFirst() : "");
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
              "Potion phase: while you hold the life potion, you see the wolves' victim and can"
                  + " save them with LIFE. Pick a head, then DEATH to poison it. Each potion works"
                  + " once per game; SLEEP ends your turn.";
          case GUARD ->
              "At night, protect one player, or yourself with PROTECT ME. Never the same player two"
                  + " nights in a row. Your choice is final.";
          case SEER -> "At night, inspect one other living player.";
          case WEREWOLF ->
              "At night, vote with the pack for a non-wolf. The hunt ends as soon as every wolf"
                  + " picks the same victim. There is no night chat.";
          default -> "Discuss by day, then vote to identify the wolves, or skip the vote.";
        })
        + bond;
  }

  /**
   * Buttons the player can press right now, in menu order: skip (day vote), heal / poison / sleep
   * (witch), bind (Cupid, once two lovers are picked), shoot (hunter, once a target is picked) and
   * protect-self (guard).
   */
  public synchronized Map<String, String> abilities(UUID p) {
    Map<String, String> out = new LinkedHashMap<>();
    Role role = roles.get(p);
    boolean living = alive.contains(p);
    switch (phase) {
      case DAY_VOTE -> {
        if (living) out.put("skip", abstained.contains(p) ? "Vote after all" : "Skip the vote");
      }
      case WITCH -> {
        if (!living || role != Role.WITCH) break;
        if (!healUsed.contains(p)
            && nightVictim != null
            && alive.contains(nightVictim)
            && !heals.contains(p)) out.put("heal", "Use the life potion · save " + nightVictim);
        if (!poisonUsed.contains(p) && !aims(p).isEmpty())
          out.put("poison", "Use the death potion on " + aims(p).getFirst());
        out.put("sleep", "Go back to sleep");
      }
      case CUPID -> {
        if (living && role == Role.CUPID && lovers.isEmpty() && aims(p).size() == 2)
          out.put("bind", "Bind " + aims(p).get(0) + " and " + aims(p).get(1));
      }
      case HUNTER_SHOT -> {
        if (Objects.equals(hunters.peekFirst(), p) && !aims(p).isEmpty())
          out.put("shoot", "Shoot " + aims(p).getFirst());
      }
      case GUARD -> {
        if (living && role == Role.GUARD && !Objects.equals(lastGuard.get(p), p))
          out.put("protect-self", "Protect yourself tonight");
      }
      default -> {}
    }
    return out;
  }

  public synchronized String use(UUID p, String ability) {
    if (!abilities(p).containsKey(ability))
      throw new IllegalArgumentException(
          switch (ability) {
            case "heal" -> "Life potion unavailable";
            case "poison" -> "Pick a head to poison first";
            case "bind" -> "Pick two lovers first";
            case "shoot" -> "Pick your target first";
            default -> "This action is unavailable now";
          });
    switch (ability) {
      case "heal" -> {
        heals.add(p);
        healUsed.add(p);
        return "Life potion used. The wolves' victim will survive this night.";
      }
      case "poison" -> {
        UUID target = aims.remove(p).getFirst();
        return poison(p, target);
      }
      case "sleep" -> {
        aims.remove(p);
        advance();
        return "You go back to sleep.";
      }
      case "bind" -> {
        List<UUID> pair = aims.remove(p);
        firstLover = null;
        select(p, pair.get(0));
        return select(p, pair.get(1));
      }
      case "shoot" -> {
        return select(p, aims.remove(p).getFirst());
      }
      case "protect-self" -> {
        return select(p, p);
      }
      default -> { // skip
        ballots.remove(p);
        if (abstained.remove(p)) return "You may vote again: click a head.";
        abstained.add(p);
        return "You skip this vote. Click a head if you change your mind.";
      }
    }
  }

  /**
   * Picks or unpicks a head before a confirming button: Cupid's lovers (two), the hunter's target
   * and the witch's poison target (one each; a new pick replaces the old one).
   */
  public synchronized String aim(UUID p, UUID target) {
    if (!alive.contains(target)) throw new IllegalArgumentException("Select a living player");
    Role role = roles.get(p);
    int max;
    if (phase == Phase.CUPID && role == Role.CUPID && alive.contains(p) && lovers.isEmpty())
      max = 2;
    else if (phase == Phase.HUNTER_SHOT && Objects.equals(hunters.peekFirst(), p)) max = 1;
    else if (phase == Phase.WITCH && role == Role.WITCH && alive.contains(p)) {
      if (poisonUsed.contains(p))
        throw new IllegalArgumentException("Your death potion has already been used");
      max = 1;
    } else throw new IllegalArgumentException("You cannot pick a player during this phase");
    if (aimPhase != phase) {
      aims.clear();
      aimPhase = phase;
    }
    List<UUID> picks = aims.computeIfAbsent(p, k -> new ArrayList<>());
    if (picks.remove(target)) return "Removed " + target + ".";
    if (picks.size() >= max) {
      if (max > 1)
        throw new IllegalArgumentException("Two lovers are already picked: click one to remove it");
      picks.clear();
    }
    picks.add(target);
    return switch (phase) {
      case CUPID ->
          picks.size() == 2
              ? "Both lovers picked: press BIND to unite them."
              : "First lover picked: " + target + ". Pick a second one.";
      case HUNTER_SHOT -> "Target: " + target + ". Press SHOOT to fire.";
      default -> "Target: " + target + ". Press DEATH to poison them.";
    };
  }

  /** Heads this player picked in the current phase, in pick order. */
  public synchronized List<UUID> aims(UUID p) {
    return aimPhase == phase ? List.copyOf(aims.getOrDefault(p, List.of())) : List.of();
  }

  private String poison(UUID witch, UUID target) {
    poisonUsed.add(witch);
    poisons.put(witch, target);
    return "Death potion used. " + target + " will die at dawn.";
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
        throw new IllegalArgumentException("Your death potion has already been used");
      return poison(voter, target);
    }
    if (voter.equals(target)) throw new IllegalArgumentException("Select another living player");
    if (phase == Phase.SEER && role == Role.SEER) {
      if (!visions.add(voter))
        throw new IllegalArgumentException("You already used your vision tonight");
      seen.computeIfAbsent(voter, k -> new LinkedHashMap<>()).put(target, roles.get(target));
      String vision = "Your vision: " + target + " is " + roles.get(target) + ".";
      advance();
      return vision;
    }
    if (phase != Phase.DAY_VOTE && (phase != Phase.NIGHT || role != Role.WEREWOLF))
      throw new IllegalArgumentException("You cannot vote during this phase");
    if (phase == Phase.NIGHT && roles.get(target) == Role.WEREWOLF)
      throw new IllegalArgumentException("Wolves cannot target another wolf");
    abstained.remove(voter);
    if (target.equals(ballots.get(voter))) {
      ballots.remove(voter); // A second click on the same head takes the vote back.
      return "Vote withdrawn.";
    }
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

  /** The arena's time limit ran out: nobody wins and every menu unlocks. */
  @Override
  public synchronized void finish() {
    phase = Phase.FINISHED;
  }

  public synchronized void remove(UUID p) {
    if (phase == Phase.FINISHED) return; // The result (and the winners' list) is settled.
    eliminate(p, false);
    hunters.remove(p);
    guards.remove(p);
    poisons.remove(p);
    aims.remove(p);
    abstained.remove(p);
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
    aims.values().forEach(picks -> picks.remove(p));
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
    heals.clear();
    dawns++;
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
        // Two picked lovers are bound when time runs out, as if Cupid had pressed BIND.
        for (UUID cupid : alive)
          if (roles.get(cupid) == Role.CUPID && abilities(cupid).containsKey("bind")) {
            use(cupid, "bind");
            return;
          }
        if (lovers.isEmpty())
          for (UUID cupid : alive)
            if (roles.get(cupid) == Role.CUPID)
              notices.put(cupid, "Time ran out before you chose two lovers: nobody is in love.");
        firstLover = null;
        phase = firstNightPhase();
      }
      case DISCUSSION -> phase = Phase.DAY_VOTE;
      case DAY_VOTE -> {
        verdict = new Verdict(majority());
        eliminate(verdict.lynched(), true);
        ballots.clear();
        abstained.clear();
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
        // A picked target is shot when time runs out, as if the hunter had pressed SHOOT.
        UUID hunter = hunters.peekFirst();
        if (hunter != null && abilities(hunter).containsKey("shoot")) {
          use(hunter, "shoot");
          return;
        }
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

  /** Nights so far: night N is followed by day N. */
  public synchronized int round() {
    return night() ? dawns + 1 : Math.max(1, dawns);
  }

  /** The player's current ballot (day vote, or the wolves' hunt), or null. */
  public synchronized UUID ballot(UUID voter) {
    return ballots.get(voter);
  }

  public synchronized boolean abstained(UUID voter) {
    return abstained.contains(voter);
  }

  /** The other living wolves, known only to a wolf. */
  public synchronized List<UUID> pack(UUID wolf) {
    if (roles.get(wolf) != Role.WEREWOLF) return List.of();
    return alive.stream().filter(p -> !p.equals(wolf) && roles.get(p) == Role.WEREWOLF).toList();
  }

  /** What this seer has seen so far, oldest first (player -> role). */
  public synchronized Map<UUID, String> visions(UUID seer) {
    Map<UUID, String> out = new LinkedHashMap<>();
    seen.getOrDefault(seer, Map.of()).forEach((p, r) -> out.put(p, r.name()));
    return out;
  }

  /** The wolves' victim, shown to the witch during her turn while she holds the life potion. */
  public synchronized UUID victim(UUID witch) {
    return phase == Phase.WITCH
            && roles.get(witch) == Role.WITCH
            && !healUsed.contains(witch)
            && nightVictim != null
            && alive.contains(nightVictim)
        ? nightVictim
        : null;
  }

  public synchronized boolean lifePotion(UUID witch) {
    return roles.get(witch) == Role.WITCH && !healUsed.contains(witch);
  }

  public synchronized boolean deathPotion(UUID witch) {
    return roles.get(witch) == Role.WITCH && !poisonUsed.contains(witch);
  }

  /** Whom this guard protected last night, who cannot be protected again tonight. */
  public synchronized UUID lastProtected(UUID guard) {
    return lastGuard.get(guard);
  }

  /** A counted day vote: whom the village eliminated, or null when no one had a majority. */
  public record Verdict(UUID lynched) {}

  /**
   * The day vote counted since the last call, or null when the day vote ended some other way (the
   * time limit, a departure). Read once, so the village hears each verdict a single time.
   */
  public synchronized Verdict takeVerdict() {
    Verdict taken = verdict;
    verdict = null;
    return taken;
  }

  /** This player's lover, known only to the two of them. */
  public synchronized UUID partner(UUID p) {
    return lovers.get(p);
  }

  /** How many of each role were dealt, in enum order. */
  public synchronized Map<String, Integer> composition() {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (Role role : Role.values()) {
      int n = (int) roles.values().stream().filter(role::equals).count();
      if (n > 0) out.put(role.name(), n);
    }
    return out;
  }

  /**
   * True while the village sleeps. A hunter's shot belongs to the phase it interrupts: taken at
   * dawn or during the day it happens in daylight, during the night it stays secret.
   */
  public synchronized boolean night() {
    return switch (phase) {
      case CUPID, GUARD, SEER, NIGHT, WITCH -> true;
      case HUNTER_SHOT -> resume != Phase.DISCUSSION && resume != Phase.DAY_VOTE;
      default -> false;
    };
  }

  public synchronized boolean sleeping(UUID p) {
    if (!alive.contains(p)) return false;
    return switch (phase) {
      case CUPID -> roles.get(p) != Role.CUPID;
      case GUARD -> roles.get(p) != Role.GUARD;
      case SEER -> roles.get(p) != Role.SEER;
      case NIGHT -> roles.get(p) != Role.WEREWOLF;
      case WITCH -> roles.get(p) != Role.WITCH;
      case HUNTER_SHOT -> night() && !Objects.equals(hunters.peekFirst(), p);
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

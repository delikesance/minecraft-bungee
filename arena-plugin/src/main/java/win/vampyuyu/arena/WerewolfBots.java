package win.vampyuyu.arena;

import java.util.*;

/**
 * Practice players for Werewolf. Each bot wakes at a random moment of its turn and acts through
 * the same calls as a human click, so every rule still applies. A bot only uses what its role may
 * know: its own role, its lover, its pack, its visions, the witch's victim and the public ballots.
 * When the timer runs out, every bot that has not acted yet acts at once, the pack lines up behind
 * one victim and a tied day vote is settled, so a practice game never stalls on a bot.
 */
final class WerewolfBots {
  /** One thing a bot did, for the server log; {@code bot} is null for a move of several bots. */
  record Action(UUID bot, String role, String what) {}

  private final Random random;
  private final Map<UUID, Long> wakeAt = new HashMap<>();
  private final Set<UUID> done = new HashSet<>();
  private String turn = "";

  WerewolfBots(Random random) {
    this.random = random;
  }

  /**
   * Lets the bots whose moment has come act. With {@code deadline}, every bot still owing an action
   * acts now. Returns what they did, oldest first.
   */
  synchronized List<Action> think(
      WerewolfGame game, Set<UUID> bots, long now, long endsAt, boolean deadline) {
    List<Action> log = new ArrayList<>();
    if (!game.winner().isEmpty() || game.phase().equals("FINISHED")) return log;
    String key = game.turnKey();
    if (!key.equals(turn)) {
      turn = key;
      done.clear();
      wakeAt.clear();
      long span = Math.max(0, endsAt - now);
      for (UUID bot : game.actors())
        if (bots.contains(bot))
          wakeAt.put(bot, now + (long) (span * (0.15 + 0.5 * random.nextDouble())));
    }
    List<UUID> order = new ArrayList<>(wakeAt.keySet());
    order.sort(Comparator.comparing(wakeAt::get));
    for (UUID bot : order) {
      if (!key.equals(game.turnKey())) return log; // A bot's action ended the turn.
      if (done.contains(bot) || (!deadline && wakeAt.get(bot) > now)) continue;
      done.add(bot);
      try {
        act(game, bot, log);
      } catch (RuntimeException refused) {
        // A refused bot only loses its turn: the timer still moves the game on.
        log.add(new Action(bot, game.role(bot), "was refused: " + refused.getMessage()));
      }
    }
    if (!key.equals(game.turnKey())) return log;
    try {
      switch (game.phase()) {
        case "NIGHT" -> followPack(game, bots, log, deadline);
        case "DAY_VOTE" -> {
          if (deadline) settleTie(game, bots, log);
        }
        default -> {}
      }
    } catch (RuntimeException refused) {
      log.add(new Action(null, "BOTS", "was refused: " + refused.getMessage()));
    }
    return log;
  }

  private void act(WerewolfGame game, UUID bot, List<Action> log) {
    String role = game.role(bot);
    switch (game.phase()) {
      case "CUPID" -> {
        if (!role.equals("CUPID")) return;
        List<UUID> pool = new ArrayList<>(game.alive());
        Collections.shuffle(pool, random);
        // Cupid may fall for someone himself, now and then.
        if (random.nextInt(3) == 0) {
          pool.remove(bot);
          pool.addFirst(bot);
        }
        if (pool.size() < 2) return;
        game.select(bot, pool.get(0));
        game.select(bot, pool.get(1));
        log.add(new Action(bot, role, "binds " + pool.get(0) + " and " + pool.get(1)));
      }
      case "GUARD" -> {
        if (!role.equals("GUARD")) return;
        List<UUID> pool = new ArrayList<>(game.alive());
        pool.remove(game.lastProtected(bot));
        if (pool.isEmpty()) return;
        UUID target =
            pool.contains(bot) && random.nextInt(4) == 0
                ? bot
                : pick(without(new ArrayList<>(pool), bot), pool);
        game.select(bot, target);
        log.add(new Action(bot, role, "protects " + target));
      }
      case "SEER" -> {
        if (!role.equals("SEER")) return;
        Set<UUID> seen = game.visions(bot).keySet();
        List<UUID> pool = without(new ArrayList<>(game.alive()), bot);
        List<UUID> unseen = new ArrayList<>(pool);
        unseen.removeAll(seen);
        UUID target = pick(unseen, pool);
        if (target == null) return;
        String vision = game.select(bot, target);
        log.add(new Action(bot, role, "inspects " + target + " (" + vision + ")"));
      }
      case "NIGHT" -> {
        if (!role.equals("WEREWOLF")) return;
        UUID target = packChoice(game, bot);
        if (target == null) target = pick(prey(game, bot), List.of());
        if (target == null) return;
        vote(game, bot, target, log, "hunts");
      }
      case "WITCH" -> {
        if (!role.equals("WITCH")) return;
        witch(game, bot, log);
      }
      case "DAY_VOTE" -> dayVote(game, bot, log);
      case "HUNTER_SHOT" -> {
        List<UUID> pool = new ArrayList<>(game.alive());
        pool.remove(game.partner(bot));
        UUID target = pick(pool, new ArrayList<>(game.alive()));
        if (target == null) return;
        game.select(bot, target);
        log.add(new Action(bot, role, "shoots " + target));
      }
      default -> {}
    }
  }

  private void witch(WerewolfGame game, UUID bot, List<Action> log) {
    UUID victim = game.victim(bot);
    boolean healed = false;
    // She always saves herself, and saves someone else three times out of four.
    if (victim != null
        && game.abilities(bot).containsKey("heal")
        && (victim.equals(bot) || random.nextInt(4) != 0)) {
      game.use(bot, "heal");
      healed = true;
      log.add(new Action(bot, "WITCH", "saves " + victim));
    }
    if (game.deathPotion(bot) && random.nextInt(100) < poisonOdds(game)) {
      List<UUID> pool = without(new ArrayList<>(game.alive()), bot);
      pool.remove(game.partner(bot));
      if (!healed) pool.remove(victim); // Already dying: the potion would be wasted.
      UUID target = pick(pool, List.of());
      if (target != null) {
        game.select(bot, target);
        log.add(new Action(bot, "WITCH", "poisons " + target));
      }
    }
    game.use(bot, "sleep");
    log.add(new Action(bot, "WITCH", "goes back to sleep"));
  }

  /** The fewer players are left, the more each poison matters: she uses it more willingly. */
  private static int poisonOdds(WerewolfGame game) {
    return game.alive().size() <= 5 ? 45 : 20;
  }

  private void dayVote(WerewolfGame game, UUID bot, List<Action> log) {
    if (!game.alive().contains(bot)) return;
    List<UUID> pool = suspects(game, bot);
    UUID known = knownWolf(game, bot);
    if (known != null) {
      vote(game, bot, known, log, "votes against");
      return;
    }
    boolean wolf = game.role(bot).equals("WEREWOLF");
    if (pool.isEmpty() || random.nextInt(100) < (wolf ? 5 : 12)) {
      if (!game.abstained(bot)) {
        game.use(bot, "skip");
        log.add(new Action(bot, game.role(bot), "skips the vote"));
      }
      return;
    }
    UUID target = null;
    if (wolf)
      for (UUID mate : game.pack(bot))
        if (pool.contains(game.ballot(mate)) && random.nextInt(10) < 7) {
          target = game.ballot(mate);
          break;
        }
    UUID leader = leader(game, bot);
    if (target == null && leader != null && pool.contains(leader) && random.nextInt(100) < 55)
      target = leader;
    if (target == null) target = pick(pool, List.of());
    vote(game, bot, target, log, "votes against");
  }

  /** Whom this bot could vote out: never itself, its lover, its pack or a villager it saw. */
  private static List<UUID> suspects(WerewolfGame game, UUID bot) {
    List<UUID> pool = without(new ArrayList<>(game.alive()), bot);
    pool.remove(game.partner(bot));
    pool.removeAll(game.pack(bot));
    game.visions(bot)
        .forEach(
            (p, role) -> {
              if (!role.equals("WEREWOLF")) pool.remove(p);
            });
    return pool;
  }

  /** A living wolf this seer has seen, unless it is the seer's own lover. */
  private static UUID knownWolf(WerewolfGame game, UUID bot) {
    for (var vision : game.visions(bot).entrySet())
      if (vision.getValue().equals("WEREWOLF")
          && game.alive().contains(vision.getKey())
          && !vision.getKey().equals(game.partner(bot))) return vision.getKey();
    return null;
  }

  /** The single most-voted player right now, or null on a tie or an empty vote. */
  private static UUID leader(WerewolfGame game, UUID viewer) {
    List<UUID> top = top(game.votes(viewer));
    return top.size() == 1 ? top.getFirst() : null;
  }

  private static List<UUID> top(Map<UUID, Integer> totals) {
    int max = totals.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    if (max == 0) return List.of();
    return totals.entrySet().stream()
        .filter(e -> e.getValue() == max)
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
  }

  /**
   * When the vote closes on a tie, bots move to one of the leaders until one stands out, as a real
   * table would rather than let the day pass with no one eliminated.
   */
  private void settleTie(WerewolfGame game, Set<UUID> bots, List<Action> log) {
    List<UUID> voters = new ArrayList<>(bots);
    voters.retainAll(game.alive());
    Collections.shuffle(voters, random);
    for (UUID bot : voters) {
      List<UUID> top = new ArrayList<>(top(game.votes(bot)));
      if (top.size() < 2) return;
      UUID known = knownWolf(game, bot);
      if (known != null && !top.contains(known)) continue; // A seer never lets her wolf go.
      top.retainAll(suspects(game, bot));
      if (top.isEmpty()) continue;
      UUID target = top.contains(known) ? known : top.get(random.nextInt(top.size()));
      if (target.equals(game.ballot(bot))) continue; // Already on the leader that wins.
      vote(game, bot, target, log, "breaks the tie against");
    }
  }

  /** The pack's current choice: a human wolf's pick comes first, then the pack's favourite. */
  private UUID packChoice(WerewolfGame game, UUID bot) {
    Map<UUID, Integer> picks = new HashMap<>();
    for (UUID mate : game.pack(bot)) {
      UUID ballot = game.ballot(mate);
      if (ballot != null && !ballot.equals(game.partner(bot))) picks.merge(ballot, 1, Integer::sum);
    }
    List<UUID> top = top(picks);
    return top.isEmpty() ? null : top.getFirst();
  }

  /**
   * Bot wolves never split the pack: they follow a human wolf's pick as soon as it changes, and at
   * the deadline every bot wolf joins the pack's favourite so the hunt does not end on a tie.
   */
  private void followPack(WerewolfGame game, Set<UUID> bots, List<Action> log, boolean deadline) {
    String key = game.turnKey();
    UUID human = null;
    for (UUID wolf : game.actors())
      if (!bots.contains(wolf) && game.ballot(wolf) != null) {
        human = game.ballot(wolf);
        break;
      }
    for (UUID bot : game.actors()) {
      if (!key.equals(game.turnKey())) return;
      if (!bots.contains(bot) || !done.contains(bot)) continue;
      UUID target = human != null ? human : deadline ? packChoice(game, bot) : null;
      if (target == null || target.equals(game.partner(bot))) continue;
      if (!target.equals(game.ballot(bot))) vote(game, bot, target, log, "follows the pack onto");
    }
  }

  /** Votes for a target without ever undoing a ballot: a second click on a head withdraws it. */
  private static void vote(
      WerewolfGame game, UUID bot, UUID target, List<Action> log, String verb) {
    if (target == null || target.equals(game.ballot(bot))) return;
    game.select(bot, target);
    log.add(new Action(bot, game.role(bot), verb + " " + target));
  }

  /** Living players outside the pack a wolf may hunt, sparing its lover when it can. */
  private static List<UUID> prey(WerewolfGame game, UUID wolf) {
    List<UUID> pool = without(new ArrayList<>(game.alive()), wolf);
    pool.removeAll(game.pack(wolf));
    if (pool.size() > 1) pool.remove(game.partner(wolf));
    return pool;
  }

  private static List<UUID> without(List<UUID> pool, UUID p) {
    pool.remove(p);
    return pool;
  }

  /** A random entry of {@code first}, or of {@code fallback} when it is empty, or null. */
  private UUID pick(List<UUID> first, List<UUID> fallback) {
    List<UUID> pool = first.isEmpty() ? fallback : first;
    return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
  }
}

package win.vampyuyu.arena;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class PracticeGameTest {
  private static final Map<String, Integer> FULL_CAST =
      Map.of("WEREWOLF", 2, "SEER", 1, "WITCH", 1, "HUNTER", 1, "CUPID", 1, "GUARD", 1);

  private static List<UUID> ids(int seed, int n) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) ids.add(new UUID(seed, i + 1));
    return ids;
  }

  private static WerewolfGame game(List<UUID> ids, int seed, Map<String, Integer> cast) {
    return new WerewolfGame(
        ids, 5, 5, 5, new Random(seed), new GameRules.Werewolf(cast, 5, 5, 5));
  }

  /**
   * Plays one turn like the arena clock: bots wake during a 10-second turn, then the deadline
   * makes the rest act, and the game advances unless a bot already ended the turn.
   */
  private static List<WerewolfBots.Action> turn(
      WerewolfGame game, WerewolfBots brain, Set<UUID> bots) {
    List<WerewolfBots.Action> log = new ArrayList<>();
    String key = game.turnKey();
    for (long t = 0; t < 10_000 && key.equals(game.turnKey()); t += 1000)
      log.addAll(brain.think(game, bots, t, 10_000, false));
    if (key.equals(game.turnKey())) log.addAll(brain.think(game, bots, 10_000, 10_000, true));
    if (key.equals(game.turnKey())) game.advance();
    return log;
  }

  /** Plays a whole game and returns every bot action; fails if the game does not end. */
  private static List<WerewolfBots.Action> play(WerewolfGame game, Set<UUID> bots, int seed) {
    WerewolfBots brain = new WerewolfBots(new Random(seed));
    List<WerewolfBots.Action> log = new ArrayList<>();
    for (int turn = 0; turn < 200 && game.winner().isEmpty(); turn++)
      log.addAll(turn(game, brain, bots));
    assertFalse("The game should end, seed " + seed, game.winner().isEmpty());
    for (WerewolfBots.Action action : log)
      assertFalse(action.toString(), action.what().startsWith("was refused"));
    return log;
  }

  @Test
  public void everyTableFinishesWithAnIdleHumanAndNoRefusedAction() {
    for (int seed = 0; seed < 200; seed++) {
      int size = 4 + seed % 13;
      List<UUID> ids = ids(seed, size);
      Set<UUID> bots = new HashSet<>(ids);
      bots.remove(ids.getFirst());
      // Half the tables use the automatic cast, the other half every special role.
      Map<String, Integer> cast = seed % 2 == 0 || size < 8 ? Map.of() : FULL_CAST;
      play(game(ids, seed, cast), bots, seed);
    }
  }

  @Test
  public void aTableOfBotsAloneFinishes() {
    for (int seed = 0; seed < 100; seed++) {
      List<UUID> ids = ids(seed, 9);
      play(game(ids, seed, FULL_CAST), new HashSet<>(ids), seed);
    }
  }

  @Test
  public void everyRoleActsAndBotsSometimesSkipTheVote() {
    Set<String> seen = new HashSet<>();
    for (int seed = 0; seed < 100; seed++) {
      List<UUID> ids = ids(seed, 10);
      for (WerewolfBots.Action action : play(game(ids, seed, FULL_CAST), new HashSet<>(ids), seed))
        seen.add(action.role() + " " + action.what().split(" ")[0]);
    }
    for (String expected :
        List.of(
            "CUPID binds",
            "GUARD protects",
            "SEER inspects",
            "WEREWOLF hunts",
            "WITCH saves",
            "WITCH poisons",
            "WITCH goes",
            "HUNTER shoots",
            "VILLAGER votes",
            "VILLAGER skips"))
      assertTrue("Missing: " + expected + " in " + seen, seen.contains(expected));
  }

  @Test
  public void botWolvesNeverSplitThePack() {
    for (int seed = 0; seed < 100; seed++) {
      List<UUID> ids = ids(seed, 12);
      var game = game(ids, seed, Map.of("WEREWOLF", 3, "SEER", 1));
      var brain = new WerewolfBots(new Random(seed));
      Set<UUID> bots = new HashSet<>(ids);
      while (!game.phase().equals("NIGHT")) turn(game, brain, bots);
      int before = game.alive().size();
      turn(game, brain, bots);
      // The pack agreed, so someone died at dawn (no guard, no witch to save them).
      assertEquals("seed " + seed, before - 1, game.alive().size());
    }
  }

  @Test
  public void botWolvesFollowAHumanWolf() {
    for (int seed = 0; seed < 50; seed++) {
      List<UUID> ids = ids(seed, 12);
      var game = game(ids, seed, Map.of("WEREWOLF", 3, "SEER", 1));
      List<UUID> wolves =
          ids.stream().filter(p -> game.role(p).equals("WEREWOLF")).toList();
      UUID human = wolves.getFirst();
      Set<UUID> bots = new HashSet<>(ids);
      bots.remove(human);
      var brain = new WerewolfBots(new Random(seed));
      while (!game.phase().equals("NIGHT")) turn(game, brain, bots);
      // The bots pick first, then the human chooses someone else: they follow at once.
      brain.think(game, bots, 0, 10_000, false);
      brain.think(game, bots, 9_000, 10_000, false);
      UUID prey =
          ids.stream()
              .filter(p -> !game.role(p).equals("WEREWOLF"))
              .filter(p -> !p.equals(game.ballot(wolves.get(1))))
              .findFirst()
              .orElseThrow();
      game.select(human, prey);
      brain.think(game, bots, 9_500, 10_000, false);
      assertNotEquals("The pack agreed: the hunt is over, seed " + seed, "NIGHT", game.phase());
      assertFalse(game.alive().contains(prey));
    }
  }

  @Test
  public void aTiedDayVoteIsSettledAtTheDeadline() {
    for (int seed = 0; seed < 50; seed++) {
      List<UUID> ids = ids(seed, 8);
      var game = game(ids, seed, Map.of("WEREWOLF", 1, "SEER", 1));
      Set<UUID> bots = new HashSet<>(ids);
      bots.remove(ids.get(0));
      bots.remove(ids.get(1));
      var brain = new WerewolfBots(new Random(seed));
      while (!game.phase().equals("DAY_VOTE")) turn(game, brain, bots);
      List<UUID> alive = new ArrayList<>(game.alive());
      UUID human = ids.get(0), other = ids.get(1);
      if (!alive.contains(human) || !alive.contains(other)) continue;
      // Two humans back two different players; the bots must not leave the day tied.
      alive.removeAll(List.of(human, other));
      game.select(human, alive.get(0));
      game.select(other, alive.get(1));
      brain.think(game, bots, 0, 10_000, false);
      brain.think(game, bots, 10_000, 10_000, false);
      brain.think(game, bots, 10_000, 10_000, true);
      Map<UUID, Integer> totals = game.votes(human);
      int max = Collections.max(totals.values());
      assertEquals(
          "seed " + seed + " " + totals,
          1,
          totals.values().stream().filter(v -> v == max).count());
    }
  }

  @Test
  public void botsKeepTheirSecretsInTheDayVote() {
    for (int seed = 0; seed < 100; seed++) {
      List<UUID> ids = ids(seed, 10);
      var game = game(ids, seed, FULL_CAST);
      var brain = new WerewolfBots(new Random(seed));
      Set<UUID> bots = new HashSet<>(ids);
      for (int turn = 0; turn < 200 && game.winner().isEmpty(); turn++) {
        boolean day = game.phase().equals("DAY_VOTE");
        String key = game.turnKey();
        brain.think(game, bots, 10_000, 10_000, true);
        if (day && key.equals(game.turnKey()))
          for (UUID voter : game.alive()) {
            UUID ballot = game.ballot(voter);
            if (ballot == null) continue;
            assertNotEquals("Lovers spare each other", game.partner(voter), ballot);
            assertFalse("Wolves spare the pack", game.pack(voter).contains(ballot));
          }
        if (key.equals(game.turnKey())) game.advance();
      }
    }
  }

  @Test
  public void aSeerVotesAgainstTheWolfSheSaw() {
    for (int seed = 0; seed < 50; seed++) {
      List<UUID> ids = ids(seed, 8);
      var game = game(ids, seed, Map.of("WEREWOLF", 1, "SEER", 1));
      UUID seer = ids.stream().filter(p -> game.role(p).equals("SEER")).findFirst().orElseThrow();
      UUID wolf =
          ids.stream().filter(p -> game.role(p).equals("WEREWOLF")).findFirst().orElseThrow();
      var brain = new WerewolfBots(new Random(seed));
      assertEquals("SEER", game.phase());
      game.select(seer, wolf); // A lucky first vision.
      Set<UUID> bots = new HashSet<>(ids);
      while (!game.phase().equals("DAY_VOTE") && game.winner().isEmpty())
        turn(game, brain, bots);
      if (!game.alive().contains(seer)) continue;
      brain.think(game, bots, 10_000, 10_000, true);
      assertEquals(wolf, game.ballot(seer));
    }
  }

  @Test
  public void aDeadBotHunterShootsAndEndsTheirTurn() {
    var ids = ids(1, 8);
    var game = game(ids, 3, Map.of("WEREWOLF", 1, "HUNTER", 1, "CUPID", 1));
    UUID cupid = ids.stream().filter(p -> game.role(p).equals("CUPID")).findFirst().orElseThrow();
    UUID hunter =
        ids.stream().filter(p -> game.role(p).equals("HUNTER")).findFirst().orElseThrow();
    UUID wolf =
        ids.stream().filter(p -> game.role(p).equals("WEREWOLF")).findFirst().orElseThrow();
    String opening = game.turnKey();
    game.select(cupid, hunter);
    game.select(cupid, cupid);
    assertNotEquals(opening, game.turnKey());
    while (!game.phase().equals("NIGHT")) game.advance();
    game.select(wolf, cupid); // The hunt ends: Cupid dies at dawn and takes the hunter with him.
    assertEquals("HUNTER_SHOT", game.phase());
    String shot = game.turnKey();
    var brain = new WerewolfBots(new Random(1));
    brain.think(game, Set.of(hunter), 10_000, 10_000, true);
    assertNotEquals(shot, game.turnKey());
  }
}

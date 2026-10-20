package win.vampyuyu.arena;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class ExpandedGameTest {
  private List<UUID> players(int n) {
    return java.util.stream.IntStream.range(0, n).mapToObj(i -> new UUID(0, i + 1)).toList();
  }

  private WerewolfGame game(int n, Map<String, Integer> cast) {
    return new WerewolfGame(
        players(n), 5, 5, 5, new Random(42), new GameRules.Werewolf(cast, 5, 5, 5));
  }

  private UUID who(WerewolfGame g, String role) {
    return g.alive().stream().filter(p -> g.role(p).equals(role)).findFirst().orElseThrow();
  }

  private void phase(WerewolfGame g, String phase) {
    for (int i = 0; i < 12 && !g.phase().equals(phase); i++) g.advance();
    assertEquals(phase, g.phase());
  }

  @Test
  public void automaticCastIncludesAllSpecialRoles() {
    var g = new WerewolfGame(players(8), 5, 5, 5, new Random(2), GameRules.Werewolf.defaults());
    for (String role : List.of("CUPID", "HUNTER", "WITCH", "GUARD", "SEER", "WEREWOLF", "VILLAGER"))
      assertTrue(players(8).stream().anyMatch(p -> g.role(p).equals(role)));
  }

  @Test
  public void cupidLoversShareFateAndOnlyKnowTheirOwnBond() {
    var g = game(6, Map.of("WEREWOLF", 1, "CUPID", 1));
    UUID cupid = who(g, "CUPID"), wolf = who(g, "WEREWOLF"), villager = who(g, "VILLAGER");
    g.select(cupid, wolf);
    g.select(cupid, villager);
    assertEquals("DISCUSSION", g.phase());
    assertTrue(g.help(wolf).contains(villager.toString()));
    assertFalse(g.help(cupid).contains(villager.toString()));
    g.remove(villager);
    assertFalse(g.alive().contains(wolf));
    assertEquals("VILLAGE", g.winner());
  }

  @Test
  public void hunterGetsFinalShotBeforeWinnerResolution() {
    var g = game(4, Map.of("WEREWOLF", 1, "HUNTER", 1));
    UUID hunter = who(g, "HUNTER"), wolf = who(g, "WEREWOLF");
    g.advance();
    for (UUID p : g.alive()) if (!p.equals(hunter)) g.select(p, hunter);
    g.advance();
    assertEquals("HUNTER_SHOT", g.phase());
    assertFalse(g.alive().contains(hunter));
    assertEquals("", g.winner());
    g.select(hunter, wolf);
    assertEquals("VILLAGE", g.winner());
  }

  @Test
  public void hunterShotTimesOutWithoutBlockingGame() {
    var g = game(4, Map.of("WEREWOLF", 1, "HUNTER", 1));
    UUID hunter = who(g, "HUNTER");
    g.advance();
    for (UUID p : g.alive()) if (!p.equals(hunter)) g.select(p, hunter);
    g.advance();
    g.advance();
    assertEquals("NIGHT", g.phase());
  }

  @Test
  public void witchHealingAndPoisonResolveTogetherAtDawn() {
    var g = game(5, Map.of("WEREWOLF", 1, "WITCH", 1));
    UUID witch = who(g, "WITCH"), wolf = who(g, "WEREWOLF"), victim = who(g, "VILLAGER");
    phase(g, "NIGHT");
    g.select(wolf, victim); // The sole wolf completes the vote immediately.
    assertEquals("WITCH", g.phase());
    assertTrue(g.alive().contains(victim));
    g.use(witch, "heal");
    assertThrows(IllegalArgumentException.class, () -> g.use(witch, "heal"));
    g.select(witch, wolf);
    assertThrows(IllegalArgumentException.class, () -> g.select(witch, victim));
    g.advance();
    assertTrue(g.alive().contains(victim));
    assertFalse(g.alive().contains(wolf));
    assertEquals("VILLAGE", g.winner());
  }

  @Test
  public void guardPreventsAttackAndMustAlternate() {
    var g = game(6, Map.of("WEREWOLF", 1, "GUARD", 1));
    UUID guard = who(g, "GUARD"), wolf = who(g, "WEREWOLF"), victim = who(g, "VILLAGER");
    phase(g, "GUARD");
    g.select(guard, victim);
    g.advance();
    assertEquals("NIGHT", g.phase());
    g.select(wolf, victim);
    assertTrue(g.alive().contains(victim));
    phase(g, "GUARD");
    assertThrows(IllegalArgumentException.class, () -> g.select(guard, victim));
    g.select(guard, guard);
  }

  @Test
  public void mixedLoversHaveTheirOwnWinCondition() {
    var g = game(4, Map.of("WEREWOLF", 1, "CUPID", 1));
    UUID cupid = who(g, "CUPID"), wolf = who(g, "WEREWOLF");
    g.select(cupid, wolf);
    g.select(cupid, cupid);
    for (UUID p : Set.copyOf(g.alive())) if (!p.equals(cupid) && !p.equals(wolf)) g.remove(p);
    assertEquals("LOVERS", g.winner());
  }

  private GameRules.Freeplay rules(
      String objective, int target, List<GameRules.Checkpoint> points) {
    return new GameRules.Freeplay(
        objective,
        target,
        2,
        false,
        false,
        -1,
        "WORLD",
        Map.of(),
        Map.of(),
        points,
        "Test objective");
  }

  @Test
  public void scoringRejectsWrongEventsAndFinishesAtTarget() {
    var ids = players(2);
    var g = new FreeplayGame(ids, rules("PVP", 4, List.of()));
    g.event(ids.get(0), "BLOCK_BREAK");
    assertEquals(0, g.scores().get(ids.get(0)).intValue());
    g.event(ids.get(0), "PVP");
    g.event(ids.get(0), "PVP");
    assertEquals(ids.get(0).toString(), g.winner());
    g.event(ids.get(1), "PVP");
    assertEquals(0, g.scores().get(ids.get(1)).intValue());
  }

  @Test
  public void raceRequiresOrderedCheckpointsAndLaps() {
    var ids = players(2);
    var g =
        new FreeplayGame(
            ids,
            rules(
                "RACE",
                2,
                List.of(
                    new GameRules.Checkpoint(0, 65, 0, 1), new GameRules.Checkpoint(5, 65, 0, 1))));
    g.visit(ids.get(0), 5, 65, 0);
    assertEquals(0, g.scores().get(ids.get(0)).intValue());
    for (int lap = 0; lap < 2; lap++) {
      g.visit(ids.get(0), 0, 65, 0);
      g.visit(ids.get(0), 0, 65, 0);
      g.visit(ids.get(0), 5, 65, 0);
    }
    assertEquals(ids.get(0).toString(), g.winner());
  }

  @Test
  public void timedScoringSelectsUniqueLeaderOrDraw() {
    var ids = players(2);
    var g = new FreeplayGame(ids, rules("BLOCK_PLACE", 10, List.of()));
    g.event(ids.get(0), "BLOCK_PLACE");
    g.finish();
    assertEquals(ids.get(0).toString(), g.winner());
    var tie = new FreeplayGame(ids, rules("BLOCK_BREAK", 10, List.of()));
    tie.finish();
    assertEquals("DRAW", tie.winner());
  }
}

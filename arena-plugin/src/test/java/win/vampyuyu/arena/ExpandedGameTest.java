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
    var g = new WerewolfGame(players(10), 5, 5, 5, new Random(2), GameRules.Werewolf.defaults());
    for (String role : List.of("CUPID", "HUNTER", "WITCH", "GUARD", "SEER", "WEREWOLF", "VILLAGER"))
      assertTrue(players(10).stream().anyMatch(p -> g.role(p).equals(role)));
  }

  private Map<String, Long> cast(int n) {
    var g = new WerewolfGame(players(n), 5, 5, 5, new Random(n), GameRules.Werewolf.defaults());
    return players(n).stream()
        .collect(java.util.stream.Collectors.groupingBy(g::role, java.util.stream.Collectors.counting()));
  }

  @Test
  public void automaticCastGrowsWithTheTableAndKeepsPlainVillagers() {
    assertEquals(Map.of("WEREWOLF", 1L, "SEER", 1L, "VILLAGER", 2L), cast(4));
    assertEquals(Map.of("WEREWOLF", 1L, "SEER", 1L, "WITCH", 1L, "VILLAGER", 3L), cast(6));
    assertEquals(
        Map.of("WEREWOLF", 2L, "SEER", 1L, "WITCH", 1L, "HUNTER", 1L, "CUPID", 1L, "VILLAGER", 2L),
        cast(8));
    assertEquals(3L, cast(10).get("VILLAGER").longValue());
  }

  @Test
  public void theStoryOpensAtNight() {
    var withCupid = new WerewolfGame(players(8), 5, 5, 5, new Random(1), GameRules.Werewolf.defaults());
    assertEquals("CUPID", withCupid.phase());
    var g = game(6, Map.of("WEREWOLF", 1, "SEER", 1));
    assertEquals("SEER", g.phase());
    UUID wolf = who(g, "WEREWOLF"), victim = who(g, "VILLAGER");
    g.advance();
    g.select(wolf, victim);
    assertEquals("DISCUSSION", g.phase());
    assertFalse("the first debate is about a real victim", g.alive().contains(victim));
  }

  @Test
  public void cupidRunningOutOfTimeIsToldAndBindsNobody() {
    var g = game(6, Map.of("WEREWOLF", 1, "CUPID", 1));
    UUID cupid = who(g, "CUPID"), wolf = who(g, "WEREWOLF");
    g.select(cupid, wolf);
    g.advance();
    assertEquals("NIGHT", g.phase());
    var notices = g.drainNotices();
    assertEquals(Set.of(cupid), notices.keySet());
    assertFalse(notices.get(cupid).startsWith(WerewolfGame.LOVE_NOTICE));
    assertFalse(g.help(wolf).contains("in love"));
  }

  @Test
  public void wolvesWinOnlyOnceNoVillagerIsLeft() {
    var g = game(5, Map.of("WEREWOLF", 2));
    var villagers = g.alive().stream().filter(p -> g.role(p).equals("VILLAGER")).toList();
    g.remove(villagers.get(0));
    g.remove(villagers.get(1));
    assertEquals("two wolves against one villager play on", "", g.winner());
    g.remove(villagers.get(2));
    assertEquals("WEREWOLVES", g.winner());
  }

  @Test
  public void aLoverWhoLeavesBeforeTheSecondArrowIsNotBound() {
    var g = game(6, Map.of("WEREWOLF", 1, "CUPID", 1));
    UUID cupid = who(g, "CUPID"), wolf = who(g, "WEREWOLF");
    var villagers = g.alive().stream().filter(p -> g.role(p).equals("VILLAGER")).toList();
    g.select(cupid, villagers.get(0));
    g.remove(villagers.get(0));
    assertTrue(g.select(cupid, villagers.get(1)).startsWith("First lover"));
    g.select(cupid, wolf);
    assertTrue(g.help(wolf).contains(villagers.get(1).toString()));
  }

  @Test
  public void aHunterDraggedDownByADepartingLoverShootsAtOnce() {
    var g = game(6, Map.of("WEREWOLF", 2, "CUPID", 1, "HUNTER", 1));
    UUID cupid = who(g, "CUPID"), hunter = who(g, "HUNTER"), villager = who(g, "VILLAGER");
    g.select(cupid, hunter);
    g.select(cupid, villager);
    String night = g.phase();
    g.remove(villager);
    assertEquals("HUNTER_SHOT", g.phase());
    g.select(hunter, g.alive().stream().filter(p -> g.role(p).equals("WEREWOLF")).findFirst().orElseThrow());
    assertEquals(night, g.phase());
  }

  @Test
  public void aWolfWhoDiesMidHuntNoLongerVotes() {
    var g = game(7, Map.of("WEREWOLF", 2, "CUPID", 1));
    UUID cupid = who(g, "CUPID");
    var wolves = g.alive().stream().filter(p -> g.role(p).equals("WEREWOLF")).sorted().toList();
    var villagers = g.alive().stream().filter(p -> g.role(p).equals("VILLAGER")).sorted().toList();
    g.select(cupid, wolves.get(0));
    g.select(cupid, villagers.get(0));
    assertEquals("NIGHT", g.phase());
    g.select(wolves.get(0), villagers.get(1));
    g.select(wolves.get(1), villagers.get(2));
    g.remove(villagers.get(0)); // Their lover, the first wolf, dies of grief.
    assertEquals("the living pack agrees", "DISCUSSION", g.phase());
    assertTrue(g.alive().contains(villagers.get(1)));
    assertFalse(g.alive().contains(villagers.get(2)));
  }

  @Test
  public void aHuntResumedAfterAShotEndsWhenTheLivingPackAgrees() {
    var g = game(8, Map.of("WEREWOLF", 2, "CUPID", 1, "HUNTER", 1));
    UUID cupid = who(g, "CUPID"), hunter = who(g, "HUNTER");
    var wolves = g.alive().stream().filter(p -> g.role(p).equals("WEREWOLF")).sorted().toList();
    var villagers = g.alive().stream().filter(p -> g.role(p).equals("VILLAGER")).sorted().toList();
    g.select(cupid, hunter);
    g.select(cupid, villagers.get(0));
    g.select(wolves.get(0), villagers.get(1));
    g.select(wolves.get(1), villagers.get(2));
    g.remove(villagers.get(0));
    g.select(hunter, wolves.get(0));
    assertEquals("DISCUSSION", g.phase());
    assertFalse(g.alive().contains(villagers.get(2)));
    assertTrue(g.alive().contains(villagers.get(1)));
  }

  @Test
  public void aBallotOnAVictimWhoDiesOfGriefDoesNotEndTheHunt() {
    var g = game(8, Map.of("WEREWOLF", 2, "CUPID", 1));
    UUID cupid = who(g, "CUPID");
    var wolves = g.alive().stream().filter(p -> g.role(p).equals("WEREWOLF")).sorted().toList();
    var villagers = g.alive().stream().filter(p -> g.role(p).equals("VILLAGER")).sorted().toList();
    g.select(cupid, wolves.get(1));
    g.select(cupid, villagers.get(0));
    g.select(wolves.get(0), villagers.get(0));
    g.remove(wolves.get(1)); // Their lover, the pack's target, dies of grief.
    assertEquals("the surviving wolf picks again", "NIGHT", g.phase());
    g.select(wolves.get(0), villagers.get(1));
    assertFalse(g.alive().contains(villagers.get(1)));
  }

  @Test
  public void theWitchIsNotOfferedAHealForAVictimWhoIsAlreadyGone() {
    var g = game(7, Map.of("WEREWOLF", 1, "WITCH", 1));
    UUID wolf = who(g, "WEREWOLF"), witch = who(g, "WITCH"), victim = who(g, "VILLAGER");
    g.select(wolf, victim);
    assertEquals("WITCH", g.phase());
    g.remove(victim);
    assertTrue(g.abilities(witch).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> g.use(witch, "heal"));
  }

  @Test
  public void bindingTheLastTwoPlayersAsAMixedCoupleEndsTheGame() {
    var g = game(5, Map.of("WEREWOLF", 1, "CUPID", 1));
    UUID cupid = who(g, "CUPID"), wolf = who(g, "WEREWOLF");
    for (UUID p : Set.copyOf(g.alive())) if (!p.equals(cupid) && !p.equals(wolf)) g.remove(p);
    assertEquals("CUPID", g.phase());
    g.select(cupid, cupid);
    g.select(cupid, wolf);
    assertEquals("LOVERS", g.winner());
  }

  @Test
  public void runningOutOfTimeEndsTheGameWithoutAWinner() {
    var g = game(5, Map.of("WEREWOLF", 1));
    g.finish();
    assertEquals("FINISHED", g.phase());
    g.remove(who(g, "WEREWOLF"));
    assertEquals("", g.winner());
  }

  @Test
  public void cupidLoversShareFateAndOnlyKnowTheirOwnBond() {
    var g = game(6, Map.of("WEREWOLF", 1, "CUPID", 1));
    UUID cupid = who(g, "CUPID"), wolf = who(g, "WEREWOLF"), villager = who(g, "VILLAGER");
    g.select(cupid, wolf);
    g.select(cupid, villager);
    assertEquals("NIGHT", g.phase()); // The first night goes on once the lovers are bound.
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
    phase(g, "DAY_VOTE");
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
    phase(g, "DAY_VOTE");
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
    assertTrue(g.select(witch, victim).contains("again to confirm"));
    g.select(witch, wolf); // Aiming elsewhere asks for a fresh confirmation...
    assertTrue(g.alive().contains(wolf));
    g.select(witch, wolf); // ...and the second click on the same head pours it.
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
    assertEquals("GUARD", g.phase());
    g.select(guard, victim);
    assertEquals("the guard's choice ends the turn", "NIGHT", g.phase());
    g.select(wolf, victim);
    assertTrue(g.alive().contains(victim));
    phase(g, "GUARD");
    assertThrows(IllegalArgumentException.class, () -> g.select(guard, victim));
    g.select(guard, guard);
  }

  @Test
  public void witchIsShownTheVictimEvenWhenTheGuardSavesThem() {
    var g = game(7, Map.of("WEREWOLF", 1, "GUARD", 1, "WITCH", 1));
    UUID guard = who(g, "GUARD"), wolf = who(g, "WEREWOLF"), witch = who(g, "WITCH");
    UUID victim = who(g, "VILLAGER");
    g.select(guard, victim);
    g.select(wolf, victim);
    assertEquals("WITCH", g.phase());
    assertTrue(g.abilities(witch).containsKey("heal"));
    g.advance();
    assertTrue(g.alive().contains(victim));
  }

  @Test
  public void bindingLoversQueuesAPrivateNoticeForEachOfThemOnce() {
    var g = game(6, Map.of("WEREWOLF", 1, "CUPID", 1));
    UUID cupid = who(g, "CUPID"), wolf = who(g, "WEREWOLF"), villager = who(g, "VILLAGER");
    g.select(cupid, wolf);
    assertTrue("nothing is announced after the first pick", g.drainNotices().isEmpty());
    g.select(cupid, villager);
    var notices = g.drainNotices();
    assertEquals(Set.of(wolf, villager), notices.keySet());
    assertTrue(notices.get(wolf).contains(villager.toString()));
    assertTrue(notices.get(villager).contains(wolf.toString()));
    assertFalse("the Cupid is not told unless chosen", notices.containsKey(cupid));
    assertTrue("delivered once", g.drainNotices().isEmpty());
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

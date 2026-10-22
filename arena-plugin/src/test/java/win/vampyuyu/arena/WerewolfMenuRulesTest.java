package win.vampyuyu.arena;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

/** Rules behind the locked menus, the role reveal and the wake/sleep announcements. */
public class WerewolfMenuRulesTest {
  private List<UUID> ids() {
    return java.util.stream.IntStream.range(0, 10).mapToObj(i -> new UUID(2, i + 1)).toList();
  }

  private WerewolfGame game(Map<String, Integer> roles) {
    return new WerewolfGame(ids(), 10, 10, 10, new Random(7), new GameRules.Werewolf(roles, 5, 5, 5));
  }

  private UUID role(WerewolfGame g, String role) {
    return ids().stream().filter(p -> g.role(p).equals(role)).findFirst().orElseThrow();
  }

  private void phase(WerewolfGame g, String phase) {
    for (int i = 0; i < 20 && !g.phase().equals(phase); i++) g.advance();
    assertEquals(phase, g.phase());
  }

  @Test
  public void discussionNeverLocksAMenu() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    phase(g, "DISCUSSION");
    for (UUID p : ids()) assertFalse(WerewolfPresentation.menuRequired(g, p));
  }

  @Test
  public void theDayVoteLocksEveryLivingPlayerButNotTheDead() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    UUID dead = role(g, "VILLAGER");
    g.remove(dead);
    phase(g, "DAY_VOTE");
    for (UUID p : g.alive()) assertTrue(WerewolfPresentation.menuRequired(g, p));
    assertFalse(WerewolfPresentation.menuRequired(g, dead));
  }

  @Test
  public void atNightOnlyTheWakingRoleHasALockedMenu() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1, "GUARD", 1, "WITCH", 1));
    phase(g, "NIGHT");
    for (UUID p : ids())
      assertEquals(g.role(p).equals("WEREWOLF"), WerewolfPresentation.menuRequired(g, p));
    phase(g, "SEER");
    for (UUID p : ids())
      assertEquals(g.role(p).equals("SEER"), WerewolfPresentation.menuRequired(g, p));
  }

  @Test
  public void aDeadHunterStillGetsTheLockedShotMenu() {
    var g = game(Map.of("WEREWOLF", 2, "HUNTER", 1));
    UUID hunter = role(g, "HUNTER");
    phase(g, "DAY_VOTE");
    for (UUID voter : g.alive()) if (!voter.equals(hunter)) g.select(voter, hunter);
    g.advance();
    assertEquals("HUNTER_SHOT", g.phase());
    assertFalse(g.alive().contains(hunter));
    assertTrue(WerewolfPresentation.menuRequired(g, hunter));
    for (UUID p : ids()) if (!p.equals(hunter)) assertFalse(WerewolfPresentation.menuRequired(g, p));
  }

  @Test
  public void aFinishedGameLocksNothing() {
    var g = game(Map.of("WEREWOLF", 1));
    g.finish();
    for (UUID p : ids()) assertFalse(WerewolfPresentation.menuRequired(g, p));
  }

  @Test
  public void theRoleOfAnEliminatedPlayerStaysKnownSoItCanBeRevealed() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    UUID seer = role(g, "SEER");
    g.remove(seer);
    assertFalse(g.alive().contains(seer));
    assertEquals("SEER", g.role(seer));
    assertEquals("Mia has died — they were the Seer", WerewolfPresentation.deathLine("Mia", g.role(seer)));
  }

  @Test
  public void deathLinesUseTheRightArticle() {
    assertEquals("Sam has died — they were a Werewolf", WerewolfPresentation.deathLine("Sam", "WEREWOLF"));
    assertEquals("Eli has died — they were a Villager", WerewolfPresentation.deathLine("Eli", "VILLAGER"));
    assertEquals("Zoé has died — they were the Witch", WerewolfPresentation.deathLine("Zoé", "WITCH"));
  }

  @Test
  public void everyNightPhaseNamesItsRoleAndTheDayPhasesNameNobody() {
    assertEquals("The Werewolves wake up", WerewolfPresentation.wakeLine("NIGHT"));
    assertEquals("The Werewolves go back to sleep", WerewolfPresentation.sleepLine("NIGHT"));
    assertEquals("The Seer wakes up", WerewolfPresentation.wakeLine("SEER"));
    assertEquals("The Seer goes back to sleep", WerewolfPresentation.sleepLine("SEER"));
    assertEquals("The Witch wakes up", WerewolfPresentation.wakeLine("WITCH"));
    assertEquals("WEREWOLF", WerewolfPresentation.turnRole("NIGHT"));
    for (String day : List.of("DISCUSSION", "DAY_VOTE", "FINISHED", "")) {
      assertNull(WerewolfPresentation.turnRole(day));
      assertNull(WerewolfPresentation.wakeLine(day));
      assertNull(WerewolfPresentation.sleepLine(day));
    }
  }

  @Test
  public void wolvesCannotVoteDuringTheDayOrTargetEachOther() {
    var g = game(Map.of("WEREWOLF", 2));
    phase(g, "NIGHT");
    List<UUID> wolves = ids().stream().filter(p -> g.role(p).equals("WEREWOLF")).toList();
    assertThrows(IllegalArgumentException.class, () -> g.select(wolves.get(0), wolves.get(1)));
    UUID villager = role(g, "VILLAGER");
    assertThrows(IllegalArgumentException.class, () -> g.select(villager, wolves.get(0)));
  }
}

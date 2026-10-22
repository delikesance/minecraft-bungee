package win.vampyuyu.arena;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import java.util.*;
import org.junit.Test;

public class WerewolfPresentationTest {
  private List<UUID> ids() {
    return java.util.stream.IntStream.range(0, 10).mapToObj(i -> new UUID(1, i + 1)).toList();
  }

  private WerewolfGame game(Map<String, Integer> roles) {
    return new WerewolfGame(
        ids(), 10, 10, 10, new Random(42), new GameRules.Werewolf(roles, 5, 5, 5));
  }

  private UUID role(WerewolfGame g, String role) {
    return ids().stream().filter(p -> g.role(p).equals(role)).findFirst().orElseThrow();
  }

  private void phase(WerewolfGame g, String phase) {
    for (int i = 0; i < 15 && !g.phase().equals(phase); i++) g.advance();
    assertEquals(phase, g.phase());
  }

  @Test
  public void privateInstructionsAndTimersOnlyReachActiveRoles() {
    var g = game(Map.of("WEREWOLF", 2, "GUARD", 1, "SEER", 1, "WITCH", 1));
    UUID observer = role(g, "VILLAGER");
    g.remove(observer);
    for (String phase : List.of("GUARD", "SEER", "NIGHT", "WITCH")) {
      phase(g, phase);
      for (UUID viewer : ids()) {
        var view = WerewolfPresentation.view(g, viewer);
        assertEquals(g.actors().contains(viewer), view.actor());
        if (!view.actor()) {
          assertEquals("The village sleeps", view.phase());
          assertEquals("The village sleeps", view.instruction());
          assertFalse(view.timerVisible());
        } else {
          assertTrue(view.timerVisible());
          assertNotEquals("The village sleeps", view.phase());
        }
      }
    }
  }

  @Test
  public void deadHunterGetsPrivateInstructionsAndOthersSleep() {
    var g = game(Map.of("WEREWOLF", 1, "HUNTER", 1));
    UUID hunter = role(g, "HUNTER");
    phase(g, "DAY_VOTE");
    for (UUID p : g.alive()) if (!p.equals(hunter)) g.select(p, hunter);
    g.advance();
    assertEquals("HUNTER_SHOT", g.phase());
    assertFalse(g.alive().contains(hunter));
    assertEquals(Set.of(hunter), g.actors());
    assertTrue(WerewolfPresentation.view(g, hunter).timerVisible());
    assertEquals("Hunter's final shot", WerewolfPresentation.view(g, hunter).phase());
    for (UUID p : g.alive()) assertFalse(WerewolfPresentation.view(g, p).timerVisible());
  }

  @Test
  public void absentRolesDoNotCreateWakeupsAndCupidIsPrivate() {
    var g = game(Map.of("WEREWOLF", 1, "CUPID", 1));
    UUID cupid = role(g, "CUPID");
    assertEquals(Set.of(cupid), g.actors());
    assertFalse(WerewolfPresentation.view(g, role(g, "WEREWOLF")).timerVisible());
    phase(g, "NIGHT");
    assertTrue(g.actors().stream().allMatch(p -> g.role(p).equals("WEREWOLF")));
  }

  @Test
  public void oneDuskPerNightAndOneDawnPerDay() {
    assertTrue(WerewolfPresentation.dusk(false, true));
    assertFalse(WerewolfPresentation.dusk(true, true));
    assertFalse(WerewolfPresentation.dusk(false, false));
    assertTrue(WerewolfPresentation.dawn(true, false, "DISCUSSION"));
    assertTrue("a hunter shooting at dawn", WerewolfPresentation.dawn(true, false, "HUNTER_SHOT"));
    assertFalse(WerewolfPresentation.dawn(true, true, "WITCH"));
    assertFalse(WerewolfPresentation.dawn(false, false, "DAY_VOTE"));
    assertFalse(WerewolfPresentation.dawn(true, false, "FINISHED"));
  }

  @Test
  public void aHunterShotKeepsTheTimeOfDayItInterrupts() {
    var g = game(Map.of("WEREWOLF", 1, "HUNTER", 1));
    UUID hunter = role(g, "HUNTER"), wolf = role(g, "WEREWOLF");
    assertTrue(g.night());
    g.select(wolf, hunter); // Killed at night, the hunter shoots at dawn, in daylight.
    assertEquals("HUNTER_SHOT", g.phase());
    assertFalse(g.night());
    for (UUID p : g.alive()) assertFalse(g.sleeping(p));
    g.advance();
    assertEquals("DISCUSSION", g.phase());
  }

  @Test
  public void oldSettingsDefaultToEnabledAndDisabledSurvivesRoundTrip() {
    Gson gson = new Gson();
    var legacy =
        gson.fromJson(
            "{roles:{},cupidSeconds:5,witchSeconds:5,hunterSeconds:5}", GameRules.Werewolf.class);
    assertTrue(legacy.ambienceEnabled());
    var disabled = new GameRules.Werewolf(Map.of(), 5, 5, 5, false);
    assertFalse(gson.fromJson(gson.toJson(disabled), GameRules.Werewolf.class).ambienceEnabled());
  }

  @Test
  public void staleTransitionsAndOldGamesAreRejectedEvenIfPhaseRepeats() {
    assertTrue(WerewolfPresentation.current("game", 3, "game", 3));
    assertFalse(WerewolfPresentation.current("game", 4, "game", 3));
    assertFalse(WerewolfPresentation.current("new-game", 3, "game", 3));
  }

  @Test
  public void publicDayAndResultsHaveNoSecretRoleInstructions() {
    var g = game(Map.of("WEREWOLF", 1));
    phase(g, "DISCUSSION");
    assertEquals("Day · Discussion", WerewolfPresentation.view(g, role(g, "VILLAGER")).phase());
    assertTrue(WerewolfPresentation.view(g, role(g, "WEREWOLF")).timerVisible());
    assertNull(WerewolfPresentation.awakening("DISCUSSION"));
    assertNull(WerewolfPresentation.awakening("FINISHED"));
    assertEquals("The village wins", WerewolfPresentation.result("VILLAGE"));
  }
}

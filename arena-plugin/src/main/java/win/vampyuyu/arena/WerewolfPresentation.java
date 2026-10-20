package win.vampyuyu.arena;

import java.util.*;

/** Viewer-specific text policy, shared by announcements, HUD and compass. No Bukkit access. */
final class WerewolfPresentation {
  record View(String phase, String instruction, boolean actor, boolean timerVisible) {}

  record Cue(String title, String subtitle) {}

  static boolean secret(String phase) {
    return Set.of("CUPID", "GUARD", "SEER", "NIGHT", "WITCH", "HUNTER_SHOT").contains(phase);
  }

  static boolean dusk(boolean wasNight, String phase) {
    return !wasNight && secret(phase);
  }

  static boolean dawn(boolean wasNight, String phase) {
    return wasNight && phase.equals("DISCUSSION");
  }

  static View view(WerewolfGame game, UUID viewer) {
    synchronized (game) {
      String phase = game.phase();
      boolean actor = game.actors().contains(viewer);
      if (secret(phase) && !actor)
        return new View("The village sleeps", "The village sleeps", false, false);
      String label =
          switch (phase) {
            case "DISCUSSION" -> "Day · Discussion";
            case "DAY_VOTE" -> "Day · Voting";
            case "CUPID" -> "Cupid's choice";
            case "GUARD" -> "Guard's turn";
            case "SEER" -> "Seer's turn";
            case "NIGHT" -> "Wolf hunt";
            case "WITCH" -> "Potions";
            case "HUNTER_SHOT" -> "Hunter's final shot";
            default -> "Game over";
          };
      String instruction =
          actor
              ? switch (phase) {
                case "DISCUSSION" -> "Discuss with the village";
                case "DAY_VOTE" -> "Choose a player with your compass";
                case "CUPID" -> "Choose two lovers";
                case "GUARD" -> "Protect one player";
                case "SEER" -> "Inspect one player";
                case "NIGHT" -> "Choose your victim";
                case "WITCH" -> "Use your potions";
                case "HUNTER_SHOT" -> "Choose your final target";
                default -> "The game has ended";
              }
              : phase.equals("FINISHED") ? "The game has ended" : "Observe the village";
      return new View(label, instruction, actor, !phase.equals("FINISHED"));
    }
  }

  static Cue awakening(String phase) {
    return switch (phase) {
      case "CUPID" -> new Cue("Cupid, bind two hearts", "Choose two lovers with your compass");
      case "GUARD" -> new Cue("Guard, awaken", "Choose someone to protect");
      case "SEER" -> new Cue("Seer, awaken", "Discover one player's secret");
      case "NIGHT" -> new Cue("Wolves, awaken", "Gather and choose your victim");
      case "WITCH" -> new Cue("Witch, awaken", "Your potions may change their fate");
      case "HUNTER_SHOT" -> new Cue("Hunter, take your final shot", "Choose your last target");
      default -> null;
    };
  }

  static String result(String winner) {
    return switch (winner) {
      case "VILLAGE" -> "The village wins";
      case "WEREWOLVES" -> "The wolves win";
      case "LOVERS" -> "Love conquers all";
      case "DRAW" -> "The story ends in a draw";
      default -> "Time is up";
    };
  }

  static boolean current(String event, long epoch, String expectedEvent, long expectedEpoch) {
    return Objects.equals(event, expectedEvent) && epoch == expectedEpoch;
  }
}

package win.vampyuyu.arena;

import java.util.*;

/** Viewer-specific text policy, shared by announcements, HUD and compass. No Bukkit access. */
final class WerewolfPresentation {
  record View(String phase, String instruction, boolean actor, boolean timerVisible) {}

  record Cue(String title, String subtitle) {}


  /** True when this player must act now: their menu opens by itself and cannot be closed. */
  static boolean menuRequired(WerewolfGame game, UUID id) {
    synchronized (game) {
      String phase = game.phase();
      return !phase.equals("DISCUSSION") && !phase.equals("FINISHED") && game.actors().contains(id);
    }
  }

  /** Role that acts during a night phase, or null outside the night. */
  static String turnRole(String phase) {
    return switch (phase) {
      case "CUPID" -> "CUPID";
      case "GUARD" -> "GUARD";
      case "SEER" -> "SEER";
      case "NIGHT" -> "WEREWOLF";
      case "WITCH" -> "WITCH";
      case "HUNTER_SHOT" -> "HUNTER";
      default -> null;
    };
  }

  static String roleLabel(String role) {
    return role.equals("WEREWOLF")
        ? "Werewolves"
        : role.charAt(0) + role.substring(1).toLowerCase(Locale.ROOT);
  }

  static String deathLine(String name, String role) {
    return name + " has died — they were "
        + (role.equals("WEREWOLF") ? "a Werewolf" : "the " + roleLabel(role)).replace("the Villager", "a Villager");
  }

  static String wakeLine(String phase) {
    String role = turnRole(phase);
    return role == null ? null : "The " + roleLabel(role) + " wake" + (role.equals("WEREWOLF") ? "" : "s") + " up";
  }

  static String sleepLine(String phase) {
    String role = turnRole(phase);
    return role == null
        ? null
        : "The " + roleLabel(role) + " go" + (role.equals("WEREWOLF") ? "" : "es") + " back to sleep";
  }

  static boolean dusk(boolean wasNight, boolean night) {
    return !wasNight && night;
  }

  static boolean dawn(boolean wasNight, boolean night, String phase) {
    return wasNight && !night && !phase.equals("FINISHED");
  }

  static View view(WerewolfGame game, UUID viewer) {
    synchronized (game) {
      String phase = game.phase();
      boolean actor = game.actors().contains(viewer);
      if (game.night() && !actor)
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
                case "DAY_VOTE" -> "Choose a player in your menu";
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
      case "CUPID" -> new Cue("Cupid, bind two hearts", "Choose two lovers in your menu");
      case "GUARD" -> new Cue("Guard, awaken", "Choose someone to protect");
      case "SEER" -> new Cue("Seer, awaken", "Discover one player's secret");
      case "NIGHT" -> new Cue("Wolves, awaken", "Vote for your victim in the menu");
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

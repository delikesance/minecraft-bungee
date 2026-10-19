package win.vampyuyu.arena;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.UUID;

public final class WerewolfPreset implements ArenaPreset {
  public String id() {
    return "werewolf";
  }

  public GamePreset create(ArenaConfig c, Collection<UUID> participants) {
    return new WerewolfGame(
        participants,
        c.discussionSeconds(),
        c.voteSeconds(),
        c.nightSeconds(),
        new SecureRandom(),
        c.werewolf());
  }
}

package win.vampyuyu.arena;

import java.util.*;
import java.util.concurrent.*;

public final class ArenaSession {
  public final ArenaConfig config;
  public final Map<UUID, String> players = new ConcurrentHashMap<>();
  public final Set<UUID> bots = ConcurrentHashMap.newKeySet();
  public volatile GamePreset game;
  public volatile long startedAt, phaseEndsAt;
  public volatile String observedPhase = "WAITING";
  public volatile boolean stopping;

  ArenaSession(ArenaConfig config) {
    this.config = config;
  }

  public String phase() {
    return stopping ? "RESTORING" : game == null ? "WAITING" : game.phase();
  }
}

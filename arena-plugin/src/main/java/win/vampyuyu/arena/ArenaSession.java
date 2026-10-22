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
  final Map<UUID, ArenaConfig.Spawn> homes = new ConcurrentHashMap<>();
  final Map<UUID, String> roster = new ConcurrentHashMap<>();
  final Set<UUID> abandoned = ConcurrentHashMap.newKeySet();
  volatile CompletableFuture<Void> presentation = CompletableFuture.completedFuture(null);
  volatile boolean resultQueued, finishing, practice;
  volatile String eventId;
  volatile long ambienceEpoch, nextHeartbeatAt;

  /** The role cards are dealt face down and turned over at this time. */
  volatile long cardsTurnAt;

  volatile boolean ambienceNight, ambienceFinale;
  volatile String ambiencePhase = "";
  final Set<UUID> revealed = ConcurrentHashMap.newKeySet();
  volatile CompletableFuture<Void> stopFuture = CompletableFuture.completedFuture(null);

  ArenaSession(ArenaConfig config) {
    this.config = config;
  }

  public String phase() {
    return stopping || finishing ? "RESTORING" : game == null ? "WAITING" : game.phase();
  }
}

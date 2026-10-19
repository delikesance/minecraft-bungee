package win.vampyuyu.arena;

import java.nio.file.*;
import java.util.*;
import redis.clients.jedis.*;

/** Dedicated subscription connection, bounded I/O timeouts, reconnect and ban resync. */
public final class RedisManager implements RedisService {
  private final NexusArenaEngine plugin;
  private final JedisPool pool;
  private volatile Jedis subscriber;
  private volatile boolean running = true, connected;
  private final Thread thread;

  RedisManager(NexusArenaEngine plugin) throws Exception {
    this.plugin = plugin;
    String secretFile =
        System.getenv()
            .getOrDefault("ARENA_REDIS_PASSWORD_FILE", "/run/secrets/arena_redis_password");
    String password = Files.readString(Path.of(secretFile)).trim();
    if (password.length() < 32)
      throw new IllegalArgumentException("Redis password must contain at least 32 characters");
    var cfg =
        DefaultJedisClientConfig.builder()
            .password(password)
            .connectionTimeoutMillis(2000)
            .socketTimeoutMillis(3000)
            .build();
    var poolConfig = new org.apache.commons.pool2.impl.GenericObjectPoolConfig<Jedis>();
    poolConfig.setMaxTotal(4);
    poolConfig.setMaxWait(java.time.Duration.ofSeconds(2));
    pool =
        new JedisPool(
            poolConfig,
            new HostAndPort(System.getenv().getOrDefault("ARENA_REDIS_HOST", "redis"), 6379),
            cfg);
    thread =
        Thread.ofPlatform()
            .daemon()
            .name("nexus-redis-subscription")
            .unstarted(
                () -> {
                  while (running) {
                    try (Jedis jedis =
                        new Jedis(
                            System.getenv().getOrDefault("ARENA_REDIS_HOST", "redis"),
                            6379,
                            2000,
                            0)) {
                      subscriber = jedis;
                      jedis.auth(password);
                      jedis.subscribe(
                          new JedisPubSub() {
                            @Override
                            public void onSubscribe(String channel, int count) {
                              if (count == 2) {
                                connected = true;
                                plugin.io(
                                    () -> {
                                      plugin.bridge.syncBans();
                                      return null;
                                    });
                              }
                            }

                            @Override
                            public void onMessage(String channel, String message) {
                              if (message.length() <= 8192) plugin.bridge.receive(channel, message);
                            }
                          },
                          "server:moderation",
                          "server:announcements");
                    } catch (Exception e) {
                      if (running)
                        plugin
                            .getLogger()
                            .warning(
                                "Arena Redis unavailable; reconnecting (local games remain"
                                    + " usable)");
                    } finally {
                      connected = false;
                      subscriber = null;
                    }
                    if (running)
                      try {
                        Thread.sleep(3000);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                      }
                  }
                });
  }

  void start() {
    thread.start();
  }

  @Override
  public boolean connected() {
    return connected;
  }

  @Override
  public void publish(String channel, String payload) {
    try (Jedis jedis = pool.getResource()) {
      jedis.publish(channel, payload);
    }
  }

  @Override
  public void putTransfer(String uuid, String payload) {
    try (Jedis jedis = pool.getResource()) {
      jedis.setex("nexus:transfer:" + uuid, 30, payload);
    }
  }

  @Override
  public String takeTransfer(String uuid) {
    try (Jedis jedis = pool.getResource()) {
      return jedis.getDel("nexus:transfer:" + uuid);
    }
  }

  @Override
  public void ban(String uuid, String reason) {
    try (Jedis jedis = pool.getResource()) {
      jedis.hset("nexus:bans", uuid, reason);
    }
  }

  @Override
  public void pardon(String uuid) {
    try (Jedis jedis = pool.getResource()) {
      jedis.hdel("nexus:bans", uuid);
    }
  }

  @Override
  public Map<String, String> bans() {
    try (Jedis jedis = pool.getResource()) {
      return jedis.hgetAll("nexus:bans");
    }
  }

  @Override
  public void close() {
    running = false;
    Jedis sub = subscriber;
    if (sub != null)
      try {
        sub.close();
      } catch (Exception ignored) {
      }
    thread.interrupt();
    pool.close();
  }
}

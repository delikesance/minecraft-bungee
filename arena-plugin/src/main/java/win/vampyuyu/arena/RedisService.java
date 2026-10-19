package win.vampyuyu.arena;

import java.util.Map;

public interface RedisService extends AutoCloseable {
  boolean connected();

  void publish(String channel, String payload);

  void putTransfer(String uuid, String payload);

  String takeTransfer(String uuid);

  void ban(String uuid, String reason);

  void pardon(String uuid);

  Map<String, String> bans();
}

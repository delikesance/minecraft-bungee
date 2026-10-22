package win.vampyuyu.arena;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.junit.Test;

public class WerewolfLogsTest {
  @Test
  public void eachGameGetsAFileThatIsListedAndServedByNameOnly() throws Exception {
    Path dir = Files.createTempDirectory("werewolf-logs");
    var logs = new WerewolfLogs(dir, Logger.getAnonymousLogger(), false);
    UUID alice = new UUID(0, 1);
    var journal = logs.open("village 1", "abcd1234-0000", Map.of(alice, "Alice"));
    journal.log("DEATH", alice + " was eaten");
    var listed = logs.list();
    assertEquals(1, listed.size());
    String name = (String) listed.getFirst().get("name");
    assertTrue(name, name.endsWith("_village-1_abcd1234.log"));
    assertEquals(true, listed.getFirst().get("live"));
    String text = Files.readString(logs.file(name));
    assertTrue(text, text.contains("[abcd1234]") && text.contains("Alice was eaten"));
    journal.close();
    assertEquals(false, logs.list().getFirst().get("live"));
    assertThrows(IllegalArgumentException.class, () -> logs.file("../" + name));
    assertThrows(IllegalArgumentException.class, () -> logs.file("notes.txt"));
    assertThrows(NoSuchElementException.class, () -> logs.file("missing.log"));
  }
}

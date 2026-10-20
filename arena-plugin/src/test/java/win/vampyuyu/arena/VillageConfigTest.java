package win.vampyuyu.arena;

import static org.junit.Assert.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class VillageConfigTest {
  private final ArenaConfig.Spawn home = new ArenaConfig.Spawn(0, 65, 0, 0, 0);

  @Test
  public void requiresEnoughHousesAndMeetingPointsAtStart() {
    var village = new ArenaConfig.Village(true, Map.of("home", home), home, home, List.of());
    village.requireCapacity(1);
    assertThrows(IllegalArgumentException.class, () -> village.requireCapacity(2));
    var incomplete = new ArenaConfig.Village(true, Map.of("home", home), null, home, List.of());
    assertThrows(IllegalArgumentException.class, () -> incomplete.requireCapacity(1));
    ArenaConfig.Village.defaults().requireCapacity(45);
  }

  @Test
  public void rejectsDuplicatePositionsAndDoors() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArenaConfig.Village(
                true,
                Map.of("one", home, "two", new ArenaConfig.Spawn(0, 65, 0, 90, 0)),
                home,
                home,
                List.of()));
    var door = new ArenaConfig.Door(1, 65, 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArenaConfig.Village(true, Map.of(), home, home, List.of(door, door)));
  }

  @Test
  public void oldJsonLoadsWithDefaultsAndNewFieldsRoundTrip() throws Exception {
    Gson gson = new Gson();
    String json = Files.readString(Path.of("src/main/resources/presets/werewolf.json"));
    ArenaConfig old = gson.fromJson(json, ArenaConfig.class);
    assertFalse(old.village().enabled());
    assertEquals(10, old.rewards().participation());
    assertEquals(5, old.rewards().victory());
    JsonObject edited = JsonParser.parseString(json).getAsJsonObject();
    edited.add(
        "village",
        gson.toJsonTree(
            new ArenaConfig.Village(
                true, Map.of("one", home), home, home, List.of(new ArenaConfig.Door(1, 65, 1)))));
    var c = gson.fromJson(edited, ArenaConfig.class);
    var roundtrip = gson.fromJson(gson.toJson(c), ArenaConfig.class);
    assertEquals(c.village(), roundtrip.village());
    edited
        .getAsJsonObject("village")
        .add("meeting", gson.toJsonTree(new ArenaConfig.Spawn(1000, 65, 0, 0, 0)));
    assertThrows(RuntimeException.class, () -> gson.fromJson(edited, ArenaConfig.class));
  }
}

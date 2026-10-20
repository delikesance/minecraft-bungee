package win.vampyuyu.network;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class WarpStoreTest {
  private WarpStore.Warp warp(String name, UUID owner) {
    return new WarpStore.Warp(name, "", owner, "Player", "world", 1, 65, 2, 90, 0);
  }

  @Test
  public void quotaOwnershipAndCaseInsensitiveUniquenessSurviveRestart() throws Exception {
    Path path = Files.createTempDirectory("warps-test").resolve("warps.json");
    UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
    var store = new WarpStore(path);
    store.create(warp("Castle", owner), false);
    assertThrows(IllegalArgumentException.class, () -> store.create(warp("Second", owner), false));
    assertThrows(IllegalArgumentException.class, () -> store.create(warp("castle", other), false));
    assertThrows(
        IllegalArgumentException.class, () -> store.edit("castle", other, false, "stolen", false));
    store.limit(owner, 2);
    store.create(warp("Garden", owner), false);
    store.limit(owner, 0);
    var reloaded = new WarpStore(path);
    assertEquals(2, reloaded.all().size());
    assertThrows(
        IllegalArgumentException.class, () -> reloaded.create(warp("Third", owner), false));
    reloaded.edit("Castle", owner, false, "My castle", false);
    assertEquals("My castle", new WarpStore(path).get("CASTLE").description());
    reloaded.edit("Garden", other, true, "", true);
    assertEquals(1, new WarpStore(path).all().size());
    reloaded.create(warp("Admin", owner), true);
  }

  @Test
  public void rejectsInvalidNamesAndDescriptions() throws Exception {
    var store = new WarpStore(Files.createTempDirectory("warps-test").resolve("warps.json"));
    UUID owner = UUID.randomUUID();
    for (String name : List.of("", "a b", "../castle", "église", "a".repeat(33)))
      assertThrows(IllegalArgumentException.class, () -> store.create(warp(name, owner), true));
    store.create(warp("Valid_Name-1", owner), false);
    assertThrows(
        IllegalArgumentException.class,
        () -> store.edit("Valid_Name-1", owner, false, "a".repeat(241), false));
  }
}

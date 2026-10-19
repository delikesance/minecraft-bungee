package win.vampyuyu.arena;

import static org.junit.Assert.*;

import org.junit.Test;

public class ArenaConfigTest {
  @Test
  public void exactBoundsIncludeVerticalConstraint() {
    var b = new ArenaConfig.Bounds(-20, -64, -20, 20, 320, 20);
    assertTrue(b.contains(0, 65, 0));
    assertFalse(b.contains(21, 65, 0));
    assertFalse(b.contains(0, 320, 0));
    assertFalse(b.contains(0, -65, 0));
    assertFalse(b.contains(19.9, 65, 0));
  }

  @Test
  public void rejectNonFiniteAndUndersizedCorners() {
    assertThrows(
        IllegalArgumentException.class, () -> new ArenaConfig.Bounds(0, 0, 0, Double.NaN, 20, 20));
    assertThrows(
        IllegalArgumentException.class, () -> new ArenaConfig.Bounds(20, 0, 0, 18, 20, 20));
  }

  @Test
  public void normalizeEitherCornerWithoutRoundingNegativeCoordinates() {
    var b = new ArenaConfig.Bounds(1315, 320, -887, 1241, -64, -982);
    assertEquals(new ArenaConfig.Bounds(1241, -64, -982, 1315, 320, -887), b);
    assertTrue(b.contains(1286.5, 91, -944.5));
    assertEquals(-20.75, new ArenaConfig.Bounds(10.5, 20, 5.25, -20.75, 0, -30.5).minX(), 0);
  }

  @Test
  public void overlappingAndAdjacentZones() {
    var b = new ArenaConfig.Bounds(0, 0, 0, 20, 30, 20);
    assertTrue(b.overlaps(new ArenaConfig.Bounds(10, 0, 10, 30, 30, 30)));
    assertFalse(b.overlaps(new ArenaConfig.Bounds(20, 0, 0, 40, 30, 20)));
  }
}

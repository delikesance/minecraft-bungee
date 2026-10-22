package win.vampyuyu.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HeightPackTest {
    @Test
    public void raisesTheTopToTheLargestLegalHeightKeepingMinY() {
        assertEquals(2096, HeightPack.heightFor(-64));
        assertEquals(2096 - 64 + 64, HeightPack.heightFor(-64) + 0);
        assertTrue(-64 + HeightPack.heightFor(-64) <= HeightPack.TOP);
        assertEquals(0, HeightPack.heightFor(-64) % 16);
    }

    @Test
    public void neverExceedsTheVanillaHeightCap() {
        assertEquals(4064, HeightPack.heightFor(-2032));
    }
}

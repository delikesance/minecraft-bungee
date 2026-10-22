package win.vampyuyu.network;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class RoleAccessTest {
    @Test
    public void grantsOnlyTheExplicitLimitedCommands() {
        assertEquals(Set.of("minecraft.command.ban", "minecraft.command.kick", "minecraft.command.pardon", "vampyuyu.fly"),
                RoleAccess.permissionsFor(List.of("ban", "kick", "pardon", "fly")));
        assertTrue(RoleAccess.permissionsFor(List.of("op", "deop", "gamemode", "execute", "*", "minecraft.command.op", "minecraft:op", "fly other")).isEmpty());
        assertTrue(RoleAccess.permissionsFor(null).isEmpty());
    }

    @Test
    public void creativeBuilderToolsAreExactNodesWithoutWildcardsOrOperatorCommands() {
        assertTrue(RoleAccess.CREATIVE_BUILDER.contains("minecraft.command.give"));
        assertTrue(RoleAccess.CREATIVE_BUILDER.contains("minecraft.debugstick"));
        for (String node : RoleAccess.CREATIVE_BUILDER) {
            assertFalse(node, node.contains("*"));
            assertFalse(node, node.matches(".*\\.(op|deop|gamemode|execute|fill|clone|setblock)$"));
        }
    }
}

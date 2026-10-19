package win.vampyuyu.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.Test;
import static org.junit.Assert.*;

public class NetworkDisplayTest {
    @Test
    public void boldRoleDoesNotLeakIntoUsernameOrFollowingChat() {
        TextColor color = TextColor.color(0xd6c7bd);
        Component display = NetworkDisplay.formatName("Yuyu's Bat", "garder500", color);
        assertEquals(TextDecoration.State.NOT_SET, display.decoration(TextDecoration.BOLD));
        Component prefix = display.children().get(0);
        Component username = display.children().get(1);
        assertEquals("[Yuyu's Bat] ", ((TextComponent) prefix).content());
        assertEquals(TextDecoration.State.TRUE, prefix.decoration(TextDecoration.BOLD));
        assertEquals("garder500", ((TextComponent) username).content());
        assertEquals(TextDecoration.State.FALSE, username.decoration(TextDecoration.BOLD));
        assertEquals(color, prefix.color());
        assertEquals(color, username.color());
        Component chat = display.append(Component.text(" message"));
        assertEquals(TextDecoration.State.NOT_SET, chat.decoration(TextDecoration.BOLD));
    }
}

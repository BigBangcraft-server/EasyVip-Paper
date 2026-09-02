package br.com.pedrodalben.easyvip.action;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ActionExecutorSecurityTest {
    @Test
    void serverCommandFailsClosedWhenBukkitIsUnavailable() {
        Map<String, Object> action = Map.of("type", "run_server_command", "command", "give {player} stone");

        try (MockedStatic<Bukkit> mocked = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            mocked.when(Bukkit::getServer).thenReturn(null);

            assertFalse(ActionExecutor.execute(ActionContext.offline(UUID.randomUUID(), "Pedro", "test"),
                    List.of(action), Map.of()));
        }
    }
}

package br.com.pedrodalben.easyvip.action;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ActionExecutorPlaceholderTest {

    @Test
    void resolvesPercentAndBracePlaceholders() {
        Map<String, String> context = Map.of(
                "player", "Pedro",
                "vip_name", "Master Ball",
                "duration", "30d"
        );

        assertEquals("Pedro ativou Master Ball por 30d.",
                ActionExecutor.resolvePlaceholders("%player% ativou %vip_name% por %duration%.", context));
        assertEquals("Pedro ativou Master Ball por 30d.",
                ActionExecutor.resolvePlaceholders("{player} ativou {vip_name} por {duration}.", context));
    }

    @Test
    void asyncExecutorKeepsCompatibilityFallback() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Pedro");
        when(player.isOnline()).thenReturn(true);

        assertTrue(ActionExecutor.executeAsync(null, player,
                List.of(Map.of("type", "send_message", "message", "ok")), Map.of())
                .toCompletableFuture().join());
        verify(player).sendMessage(any(net.kyori.adventure.text.Component.class));
        assertFalse(ActionExecutor.executeAsync(null, null, List.of(), Map.of())
                .toCompletableFuture().join());
    }
}

package br.com.pedrodalben.easyvip.action;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

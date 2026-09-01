package br.com.pedrodalben.easyvip.action;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RandomPoolServiceTest {

    @BeforeEach
    void setUp() {
        EasyVipConfig.pools.list.clear();
    }

    @AfterEach
    void tearDown() {
        EasyVipConfig.pools.list.clear();
    }

    @Test
    void resolvesRandomPlaceholdersAndTemporaryVariables() {
        EasyVipConfig.RandomPoolDefinition pool = new EasyVipConfig.RandomPoolDefinition();
        pool.values.add("Lucario");
        EasyVipConfig.pools.list.put("shiny_pokemon", pool);

        Map<String, String> context = Map.of(
                "player", "Pedro",
                "var.pokemon", "Lucario"
        );

        assertEquals("givepokemon Pedro Lucario shiny and Lucario",
                ActionExecutor.resolvePlaceholders(
                        "givepokemon %player% %random(shiny_pokemon)% shiny and $pokemon",
                        context
                ));
    }

    @Test
    void picksWeightedEntriesUsingWeights() {
        EasyVipConfig.RandomPoolDefinition pool = new EasyVipConfig.RandomPoolDefinition();

        EasyVipConfig.RandomPoolEntry weak = new EasyVipConfig.RandomPoolEntry();
        weak.value = "Pikachu";
        weak.weight = 0.0d;
        pool.weighted.add(weak);

        EasyVipConfig.RandomPoolEntry strong = new EasyVipConfig.RandomPoolEntry();
        strong.value = "Lucario";
        strong.weight = 10.0d;
        pool.weighted.add(strong);

        EasyVipConfig.pools.list.put("weighted", pool);

        assertEquals("Lucario", RandomPoolService.pick("weighted", new Random(0L)));
    }
}

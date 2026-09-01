package br.com.pedrodalben.easyvip.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingVariantSelectionTest {

    @Test
    void expiresAfterConfiguredTimeout() {
        PendingVariantSelection selection = new PendingVariantSelection(UUID.randomUUID(), "starter", List.of("a", "b"));
        selection.setTimestamp(System.currentTimeMillis() - 90_000L);

        assertTrue(selection.isExpired(60));
        assertFalse(selection.isExpired(120));
        assertFalse(selection.isExpired(0));
    }
}

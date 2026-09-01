package br.com.pedrodalben.easyvip.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniqueCodeGeneratorTest {

    @Test
    void retriesUntilCandidateIsAvailable() {
        AtomicInteger attempts = new AtomicInteger();
        String code = UniqueCodeGenerator.generate("AB", 1, "VIP-", candidate -> attempts.incrementAndGet() >= 3, 10);

        assertTrue(code.startsWith("VIP-"));
        assertEquals(5, code.length());
        assertTrue(attempts.get() >= 3);
    }

    @Test
    void failsAfterMaxAttempts() {
        assertThrows(IllegalStateException.class, () ->
                UniqueCodeGenerator.generate("AB", 1, "", candidate -> false, 2));
    }
}

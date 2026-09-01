package br.com.pedrodalben.easyvip.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VipServiceChanceTest {

    @Test
    void chanceRuleTreatsBoundsAsExpected() {
        assertTrue(VipService.chanceSucceeded(100.0d, 99.9d));
        assertTrue(VipService.chanceSucceeded(50.0d, 49.999d));
        assertFalse(VipService.chanceSucceeded(50.0d, 50.0d));
        assertFalse(VipService.chanceSucceeded(0.0d, 0.0d));
    }
}

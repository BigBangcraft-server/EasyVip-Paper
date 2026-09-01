package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.util.DurationParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DurationFormattingAndMessagesTest {

    @BeforeEach
    void setUp() {
        EasyVipConfig.messages.durationPermanent = "permanente";
    }

    @Test
    void testFormatDurationPermanent() {
        assertEquals("permanente", DurationParser.formatDuration(-1));
    }

    @Test
    void testFormatDurationDays() {
        long thirtyDays = 30L * 24 * 60 * 60 * 1000;
        assertEquals("30d", DurationParser.formatDuration(thirtyDays));
    }

    @Test
    void testFormatDurationHoursAndMinutes() {
        long complex = (1L * 60 * 60 * 1000) + (30L * 60 * 1000);
        assertEquals("1h 30m", DurationParser.formatDuration(complex));
    }

    @Test
    void testFormatDurationJustMinutes() {
        long minutes = 15L * 60 * 1000;
        assertEquals("15m", DurationParser.formatDuration(minutes));
    }

    @Test
    void testFormatDurationJustSeconds() {
        long seconds = 45L * 1000;
        assertEquals("45s", DurationParser.formatDuration(seconds));
    }

    @Test
    void testFormatDurationCombination() {
        long combo = (5L * 24 * 60 * 60 * 1000) + (12L * 60 * 60 * 1000) + (30L * 60 * 1000) + (15L * 1000);
        assertEquals("5d 12h 30m 15s", DurationParser.formatDuration(combo));
    }
}

package br.com.pedrodalben.easyvip.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationParserTest {

    @Test
    void parsesCompositeDurations() {
        long millis = DurationParser.parseDurationMillis("1d2h30m15s");
        assertEquals(((1L * 24 * 60 * 60) + (2L * 60 * 60) + (30L * 60) + 15L) * 1000L, millis);
    }

    @Test
    void handlesPermanentDuration() {
        assertEquals(-1L, DurationParser.parseDurationMillis("permanent"));
    }
}

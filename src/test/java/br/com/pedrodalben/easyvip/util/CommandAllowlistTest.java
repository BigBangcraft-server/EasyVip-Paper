package br.com.pedrodalben.easyvip.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandAllowlistTest {

    @Test
    void allowsOnlyConfiguredPrefixesWhenEnabled() {
        List<String> prefixes = List.of("ftbranks ", "give ");

        assertTrue(CommandAllowlist.isAllowed("ftbranks add player vip", true, prefixes));
        assertTrue(CommandAllowlist.isAllowed("FTBRANKS add player vip", true, prefixes));
        assertTrue(CommandAllowlist.isAllowed("  ftbranks   add   player   vip  ", true, prefixes));
        assertTrue(CommandAllowlist.isAllowed("give @p minecraft:diamond", true, prefixes));
        assertFalse(CommandAllowlist.isAllowed("op @p", true, prefixes));
        assertFalse(CommandAllowlist.isAllowed("minecraft:op Pedro", true, prefixes));
        assertFalse(CommandAllowlist.isAllowed("ftbranks add Pedro vip; op Pedro", true, prefixes));
        assertFalse(CommandAllowlist.isAllowed("", true, prefixes));
    }

    @Test
    void disabledAllowlistPermitsCommands() {
        assertTrue(CommandAllowlist.isAllowed("op @p", false, List.of()));
    }
}

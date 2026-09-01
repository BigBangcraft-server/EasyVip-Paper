package br.com.pedrodalben.easyvip.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextUtilTest {

    @Test
    void colorizeTranslatesAmpersands() {
        String input = "&aHello &eWorld &c!";
        String output = TextUtil.colorize(input);
        assertEquals("§aHello §eWorld §c!", output);
    }

    @Test
    void colorizeTranslatesHexColors() {
        String input = "&#FF00AAHex text";
        String output = TextUtil.colorize(input);
        assertEquals("§x§f§f§0§0§a§aHex text", output);
    }

    @Test
    void toComponentHandlesEmptyAndNull() {
        assertNotNull(TextUtil.toComponent(null));
        assertNotNull(TextUtil.toComponent(""));
        assertNotNull(TextUtil.toComponent("&6Test"));
    }
}

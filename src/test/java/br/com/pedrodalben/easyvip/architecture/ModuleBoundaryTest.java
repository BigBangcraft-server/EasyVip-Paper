package br.com.pedrodalben.easyvip.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ModuleBoundaryTest {
    @Test
    void apiSourcesRemainIndependentFromPlatformAndTransportApis() throws IOException {
        Path apiRoot = Path.of("src/main/java/br/com/pedrodalben/easyvip/api");
        try (Stream<Path> files = Files.walk(apiRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = Files.readString(path);
                    assertFalse(source.contains("org.bukkit"), path + " imports Bukkit");
                    assertFalse(source.contains("com.velocitypowered"), path + " imports Velocity");
                    assertFalse(source.contains("java.sql"), path + " imports SQL");
                    assertFalse(source.contains("redis.clients"), path + " imports Redis");
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }
}

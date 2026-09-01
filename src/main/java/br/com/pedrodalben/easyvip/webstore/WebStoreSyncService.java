package br.com.pedrodalben.easyvip.webstore;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class WebStoreSyncService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static Path logFile;

    private WebStoreSyncService() {
    }

    public static void init(Path configDir) {
        Path dataDir = configDir.resolve("data");
        try {
            Files.createDirectories(dataDir);
            logFile = dataDir.resolve("webstore_sync.log");
            log("WebStore sync log initialized");
        } catch (IOException e) {
            System.err.println("[EasyVip-WebStore] Failed to create log file: " + e.getMessage());
        }
    }

    public static boolean isEnabled() {
        WebStoreConfig cfg = EasyVipConfig.webstore;
        return cfg.enabled && cfg.apiUrl != null && !cfg.apiUrl.isBlank()
                && cfg.apiToken != null && !cfg.apiToken.isBlank();
    }

    public static void syncPlayer(UUID uuid, String username, String ipAddress) {
        if (!isEnabled()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                int status = sendPlayerSyncWithRetry(uuid, username, ipAddress);
                if (status == 200 || status == 201) {
                    log("SYNC_OK | " + username + " | " + uuid + " | HTTP " + status);
                }
            } catch (Exception e) {
                log("SYNC_FAIL | " + username + " | " + uuid + " | " + e.getMessage());
                PersistenceManager.log("WebStore", "sync_failed",
                        "Player " + username + " (" + uuid + "): " + e.getMessage());
            }
        });
    }

    public static CompletableFuture<Integer> syncPlayerAsync(UUID uuid, String username, String ipAddress) {
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(0);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                int status = sendPlayerSyncWithRetry(uuid, username, ipAddress);
                if (status == 200 || status == 201) {
                    log("SYNC_OK | " + username + " | " + uuid + " | HTTP " + status);
                }
                return status;
            } catch (Exception e) {
                log("SYNC_FAIL | " + username + " | " + uuid + " | " + e.getMessage());
                PersistenceManager.log("WebStore", "sync_failed",
                        "Player " + username + " (" + uuid + "): " + e.getMessage());
                return -1;
            }
        });
    }

    private static int sendPlayerSyncWithRetry(UUID uuid, String username, String ipAddress) throws Exception {
        WebStoreConfig cfg = EasyVipConfig.webstore;
        int maxAttempts = Math.max(1, cfg.retryMaxAttempts);
        int delaySeconds = Math.max(1, cfg.retryDelaySeconds);

        String json = buildSyncPayload(uuid, username, ipAddress);

        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(cfg.playersSyncEndpoint()))
                        .header("Authorization", "Bearer " + cfg.apiToken)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                switch (status) {
                    case 200:
                    case 201:
                        log("SYNC_ATTEMPT | " + username + " | " + uuid + " | HTTP " + status + " | attempt " + attempt);
                        return status;

                    case 401:
                        log("SYNC_401 | " + username + " | " + uuid + " | Token invalido — verifique MINECRAFT_API_TOKEN");
                        PersistenceManager.log("WebStore", "sync_401",
                                "Player " + username + " (" + uuid + "): token invalido");
                        return status;

                    case 422:
                        log("SYNC_422 | " + username + " | " + uuid + " | Payload invalido: " + response.body());
                        PersistenceManager.log("WebStore", "sync_422",
                                "Player " + username + " (" + uuid + "): " + response.body());
                        return status;

                    case 500:
                    case 502:
                    case 503:
                    case 504:
                        log("SYNC_RETRY | " + username + " | " + uuid + " | HTTP " + status
                                + " | attempt " + attempt + "/" + maxAttempts);
                        lastException = new RuntimeException("HTTP " + status + " — " + response.body());
                        break;

                    default:
                        log("SYNC_UNEXPECTED | " + username + " | " + uuid + " | HTTP " + status);
                        return status;
                }

                if (attempt < maxAttempts) {
                    TimeUnit.SECONDS.sleep(delaySeconds * (long) Math.pow(2, attempt - 1));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log("SYNC_NET_ERR | " + username + " | " + uuid + " | " + e.getMessage()
                        + " | attempt " + attempt + "/" + maxAttempts);
                lastException = e;
                if (attempt < maxAttempts) {
                    TimeUnit.SECONDS.sleep(delaySeconds * (long) Math.pow(2, attempt - 1));
                }
            }
        }

        throw lastException != null
                ? new RuntimeException("Sync failed after " + maxAttempts + " tentativas. Ultimo erro: " + lastException.getMessage())
                : new RuntimeException("Sync failed after " + maxAttempts + " tentativas");
    }

    private static String buildSyncPayload(UUID uuid, String username, String ipAddress) {
        String serverId = resolveServerId();
        String canonicalUsername = username == null ? "" : username.toLowerCase(Locale.ROOT);
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"minecraft_uuid\": \"").append(escapeJson(uuid.toString())).append("\",\n");
        json.append("  \"username\": \"").append(escapeJson(username)).append("\",\n");
        json.append("  \"canonical_username\": \"").append(escapeJson(canonicalUsername)).append("\",\n");
        json.append("  \"server_id\": \"").append(escapeJson(serverId)).append("\",\n");
        json.append("  \"identity_status\": \"observed\"");
        if (ipAddress != null && !ipAddress.isBlank()) {
            json.append(",\n  \"ip_address\": \"").append(escapeJson(ipAddress)).append("\"");
        }
        json.append("\n}");
        return json.toString();
    }

    private static String resolveServerId() {
        if (EasyVipConfig.webstore.serverId != null && !EasyVipConfig.webstore.serverId.isBlank()) {
            return EasyVipConfig.webstore.serverId;
        }
        if (EasyVipConfig.fulfillment.serverId != null && !EasyVipConfig.fulfillment.serverId.isBlank()) {
            return EasyVipConfig.fulfillment.serverId;
        }
        return "";
    }

    public static void registerChallenge(UUID uuid, String code) {
        if (!isEnabled()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                sendChallenge(uuid, code);
            } catch (Exception e) {
                log("CHALLENGE_FAIL | " + uuid + " | " + e.getMessage());
            }
        });
    }

    private static void sendChallenge(UUID uuid, String code) throws Exception {
        WebStoreConfig cfg = EasyVipConfig.webstore;
        String codeDigest = sha256(code);

        String json = "{\n"
                + "  \"minecraft_uuid\": \"" + escapeJson(uuid.toString()) + "\",\n"
                + "  \"code_digest\": \"" + escapeJson(codeDigest) + "\"\n"
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.challengesEndpoint()))
                .header("Authorization", "Bearer " + cfg.apiToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 201) {
            log("CHALLENGE_OK | " + uuid);
            PersistenceManager.log("WebStore", "challenge_registered",
                    "Challenge registered for " + uuid);
        } else if (status == 401) {
            log("CHALLENGE_401 | " + uuid + " | Token invalido");
            PersistenceManager.log("WebStore", "challenge_401",
                    "Challenge 401 for " + uuid);
        } else {
            log("CHALLENGE_ERR | " + uuid + " | HTTP " + status + " | " + response.body());
            PersistenceManager.log("WebStore", "challenge_error",
                    "Challenge HTTP " + status + " for " + uuid + ": " + response.body());
        }
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static synchronized void log(String message) {
        if (logFile == null) return;

        String line = "[" + LocalDateTime.now().format(TIMESTAMP) + "] " + message + System.lineSeparator();
        try {
            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[EasyVip-WebStore] Failed to write log: " + e.getMessage());
        }

        if (EasyVipConfig.common.debug) {
            System.out.println("[EasyVip-WebStore] " + message);
        }
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

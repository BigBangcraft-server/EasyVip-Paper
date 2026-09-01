package br.com.pedrodalben.easyvip.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class KeySecurity {

    private static final Pattern CODE_WORD_BOUNDARY = Pattern.compile("(?<=[A-Z0-9])(?=[A-Z0-9])");

    private KeySecurity() {
    }

    public static String maskKey(String code) {
        if (code == null || code.isEmpty()) {
            return "***";
        }
        String normalized = code.trim();
        if (normalized.length() <= 4) {
            return normalized + "•".repeat(Math.max(0, 4 - normalized.length()) + 4);
        }
        String prefix = normalized.substring(0, 4);
        return prefix + "•".repeat(Math.max(4, normalized.length() - 4));
    }

    public static String fingerprintKey(String code) {
        if (code == null) {
            return "sha256:null";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(24);
            sb.append("sha256:");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "sha256:unavailable";
        }
    }

    public static String describeKeyForLog(String code) {
        return maskKey(code) + " [fp:" + fingerprintKey(code) + "]";
    }

    public static String sanitizeAuditDetails(String details) {
        if (details == null) {
            return null;
        }
        // Best-effort masking for common log patterns that include the raw code.
        String sanitized = details.replaceAll("(?i)(activation[_ -]?key\\s*[:=]\\s*)([A-Z0-9_-]{4,})", "$1***MASKED***");
        sanitized = sanitized.replaceAll("(?i)(key\\s*[:=]\\s*)([A-Z0-9_-]{4,})", "$1***MASKED***");
        sanitized = sanitized.replaceAll("(?i)EVIP-[A-Z0-9_-]{4,}", "***MASKED***");
        return sanitized;
    }

    public static boolean looksLikeKeyCode(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.length() >= 4 && trimmed.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }
}

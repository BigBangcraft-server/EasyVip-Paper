package br.com.pedrodalben.easyvip.util;

import java.util.List;
import java.util.Locale;

public final class CommandAllowlist {

    private CommandAllowlist() {
    }

    public static boolean isAllowed(String command, boolean enabled, List<String> allowedPrefixes) {
        String normalized = normalize(command);
        if (normalized == null) {
            return false;
        }
        if (!enabled) {
            return true;
        }
        String lowerCommand = normalized.toLowerCase(Locale.ROOT);
        for (String prefix : allowedPrefixes) {
            String normalizedPrefix = normalize(prefix);
            if (normalizedPrefix == null) {
                continue;
            }
            String lowerPrefix = normalizedPrefix.toLowerCase(Locale.ROOT);
            if (lowerCommand.equals(lowerPrefix) || lowerCommand.startsWith(lowerPrefix + " ")) {
                return true;
            }
        }
        return false;
    }

    public static String normalize(String command) {
        if (command == null) {
            return null;
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.indexOf(';') >= 0 || trimmed.indexOf('&') >= 0 || trimmed.indexOf('|') >= 0
                || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            return null;
        }
        String collapsed = trimmed.replaceAll("\\s+", " ");
        if (collapsed.startsWith("/")) {
            collapsed = collapsed.substring(1).trim();
        }
        return collapsed.isEmpty() ? null : collapsed;
    }
}

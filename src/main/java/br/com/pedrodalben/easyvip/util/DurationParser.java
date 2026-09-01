package br.com.pedrodalben.easyvip.util;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;

public final class DurationParser {

    private DurationParser() {
    }

    public static String formatDuration(long durationMillis) {
        if (durationMillis == -1) {
            return EasyVipConfig.messages.durationPermanent;
        }
        if (durationMillis <= 0) {
            return "0s";
        }
        long seconds = (durationMillis / 1000L) % 60L;
        long minutes = (durationMillis / (60L * 1000L)) % 60L;
        long hours = (durationMillis / (60L * 60L * 1000L)) % 24L;
        long days = durationMillis / (24L * 60L * 60L * 1000L);

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d");
        }
        if (hours > 0) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(hours).append("h");
        }
        if (minutes > 0) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(minutes).append("m");
        }
        if (seconds > 0 || sb.length() == 0) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(seconds).append("s");
        }
        return sb.toString();
    }

    public static long parseDurationMillis(String durationStr) {
        if (durationStr == null || durationStr.equalsIgnoreCase("permanent")) {
            return -1;
        }
        durationStr = durationStr.toLowerCase().trim();
        long millis = 0;
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < durationStr.length(); i++) {
            char c = durationStr.charAt(i);
            if (Character.isDigit(c)) {
                num.append(c);
            } else {
                if (num.length() == 0) continue;
                long val = Long.parseLong(num.toString());
                num.setLength(0);
                switch (c) {
                    case 's': millis += val * 1000L; break;
                    case 'm': millis += val * 60L * 1000L; break;
                    case 'h': millis += val * 60L * 60L * 1000L; break;
                    case 'd': millis += val * 24L * 60L * 60L * 1000L; break;
                    case 'w': millis += val * 7L * 24L * 60L * 60L * 1000L; break;
                    case 'M': millis += val * 30L * 24L * 60L * 60L * 1000L; break;
                    case 'y': millis += val * 365L * 24L * 60L * 60L * 1000L; break;
                }
            }
        }
        if (num.length() > 0) {
            millis += Long.parseLong(num.toString()) * 24L * 60L * 60L * 1000L;
        }
        return millis;
    }
}

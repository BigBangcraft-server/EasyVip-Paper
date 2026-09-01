package br.com.pedrodalben.easyvip.util;

import java.security.SecureRandom;
import java.util.function.Predicate;

public final class UniqueCodeGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UniqueCodeGenerator() {
    }

    public static String generate(String charset, int length, String prefix, Predicate<String> isAvailable, int maxAttempts) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String candidate = generateCandidate(charset, length, prefix);
            if (isAvailable.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique key code after " + maxAttempts + " attempts");
    }

    public static String generateCandidate(String charset, int length, String prefix) {
        if (charset == null || charset.isEmpty()) {
            throw new IllegalArgumentException("charset must not be null or empty");
        }
        long distinct = charset.chars().distinct().count();
        if (distinct < 2) {
            throw new IllegalArgumentException("charset must contain at least 2 distinct characters, got: " + distinct);
        }
        if (length < 1) {
            throw new IllegalArgumentException("length must be at least 1, got: " + length);
        }
        StringBuilder sb = new StringBuilder(prefix != null ? prefix : "");
        for (int i = 0; i < length; i++) {
            sb.append(charset.charAt(RANDOM.nextInt(charset.length())));
        }
        return sb.toString();
    }
}

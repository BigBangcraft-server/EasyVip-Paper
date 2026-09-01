package br.com.pedrodalben.easyvip.webstore;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FulfillmentKeyConfig {

    public static final class KeyEntry {
        public String secretEnv;
        public String secret;

        public String resolveSecret() {
            if (secret != null && !secret.isBlank()) {
                return secret;
            }
            if (secretEnv != null && !secretEnv.isBlank()) {
                String value = System.getenv(secretEnv);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return null;
        }
    }

    public KeyEntry current = new KeyEntry();
    public final Map<String, KeyEntry> keys = new LinkedHashMap<>();
}

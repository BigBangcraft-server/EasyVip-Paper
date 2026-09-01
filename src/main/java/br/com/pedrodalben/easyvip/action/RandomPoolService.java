package br.com.pedrodalben.easyvip.action;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RandomPoolService {

    private static final Pattern RANDOM_PLACEHOLDER = Pattern.compile("%random\\(([^)%]+)\\)%|\\{random\\(([^}]+)\\)\\}");

    private RandomPoolService() {
    }

    public static boolean hasPool(String poolName) {
        if (poolName == null) {
            return false;
        }
        return EasyVipConfig.pools.list.containsKey(poolName.trim());
    }

    public static String pick(String poolName) {
        return pick(poolName, ThreadLocalRandom.current());
    }

    public static String pick(String poolName, java.util.Random random) {
        if (poolName == null || random == null) {
            return "";
        }

        EasyVipConfig.RandomPoolDefinition pool = EasyVipConfig.pools.list.get(poolName.trim());
        if (pool == null) {
            return "";
        }

        String weighted = pickWeighted(pool.weighted, random);
        if (!weighted.isEmpty()) {
            return weighted;
        }

        List<String> values = pool.values;
        if (values == null || values.isEmpty()) {
            return "";
        }

        String candidate = values.get(random.nextInt(values.size()));
        return candidate != null ? candidate : "";
    }

    public static String resolveRandomPlaceholders(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String result = text;
        for (int i = 0; i < 10; i++) {
            Matcher matcher = RANDOM_PLACEHOLDER.matcher(result);
            if (!matcher.find()) {
                break;
            }

            matcher.reset();
            StringBuffer sb = new StringBuffer();
            boolean replaced = false;
            while (matcher.find()) {
                replaced = true;
                String poolName = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                String replacement = pick(poolName);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            result = sb.toString();
            if (!replaced) {
                break;
            }
        }
        return result;
    }

    private static String pickWeighted(List<EasyVipConfig.RandomPoolEntry> entries, java.util.Random random) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }

        double totalWeight = 0.0d;
        for (EasyVipConfig.RandomPoolEntry entry : entries) {
            if (entry == null || entry.value == null || entry.value.isBlank()) {
                continue;
            }
            if (entry.weight > 0.0d) {
                totalWeight += entry.weight;
            }
        }

        if (totalWeight <= 0.0d) {
            return "";
        }

        double roll = random.nextDouble(totalWeight);
        double cursor = 0.0d;
        for (EasyVipConfig.RandomPoolEntry entry : entries) {
            if (entry == null || entry.value == null || entry.value.isBlank() || entry.weight <= 0.0d) {
                continue;
            }
            cursor += entry.weight;
            if (roll < cursor) {
                return entry.value;
            }
        }

        return "";
    }
}

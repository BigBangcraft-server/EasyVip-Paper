package br.com.pedrodalben.easyvip.config;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class TomlWriter {

    private TomlWriter() {
    }

    public static void writeFile(Path path, Map<String, Object> map) throws IOException {
        String content = write(map);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    public static String write(Map<String, Object> map) {
        StringWriter sw = new StringWriter();
        try {
            writeMap("", map, sw);
        } catch (IOException e) {
            // StringWriter does not throw IOException
            throw new RuntimeException(e);
        }
        return sw.toString();
    }

    private static void writeMap(String prefix, Map<String, Object> map, Writer writer) throws IOException {
        // 1. Write primitive values first
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object val = entry.getValue();
            if (!(val instanceof Map) && !(val instanceof List)) {
                writer.write(entry.getKey() + " = " + formatValue(val) + "\n");
            } else if (val instanceof List) {
                List<?> list = (List<?>) val;
                if (list.isEmpty() || !(list.get(0) instanceof Map)) {
                    writer.write(entry.getKey() + " = " + formatValue(val) + "\n");
                }
            }
        }

        // 2. Write sub-tables
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Map) {
                String nextPrefix = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                writer.write("\n[" + nextPrefix + "]\n");
                @SuppressWarnings("unchecked")
                Map<String, Object> subMap = (Map<String, Object>) val;
                writeMap(nextPrefix, subMap, writer);
            }
        }

        // 3. Write list of tables
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof List) {
                List<?> list = (List<?>) val;
                if (!list.isEmpty() && list.get(0) instanceof Map) {
                    String nextPrefix = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                    for (Object item : list) {
                        writer.write("\n[[" + nextPrefix + "]]\n");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> subMap = (Map<String, Object>) item;
                        writeMap(nextPrefix, subMap, writer);
                    }
                }
            }
        }
    }

    private static String formatValue(Object val) {
        if (val == null) {
            return "\"\"";
        }
        if (val instanceof String) {
            return "\"" + escape((String) val) + "\"";
        }
        if (val instanceof Boolean) {
            return val.toString();
        }
        if (val instanceof Number) {
            return val.toString();
        }
        if (val instanceof List) {
            List<?> list = (List<?>) val;
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatValue(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escape(val.toString()) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

package br.com.pedrodalben.easyvip.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class TomlParser {

    private TomlParser() {
    }

    public static Map<String, Object> parseFile(Path path) throws IOException, IllegalArgumentException {
        String content = Files.readString(path);
        return parse(content);
    }

    public static Map<String, Object> parse(String content) throws IllegalArgumentException {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> currentSection = root;

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();

                // Skip comments and empty lines
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Array of tables: [[section.list]]
                if (line.startsWith("[[") && line.endsWith("]]")) {
                    String pathStr = line.substring(2, line.length() - 2).trim();
                    if (pathStr.isEmpty()) {
                        throw new IllegalArgumentException("Line " + lineNumber + ": Empty list of tables header");
                    }
                    String[] parts = pathStr.split("\\.");
                    Map<String, Object> parent = navigateToParent(root, parts, lineNumber);
                    String lastKey = parts[parts.length - 1];

                    Object existing = parent.get(lastKey);
                    List<Map<String, Object>> list;
                    if (existing instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> castList = (List<Map<String, Object>>) existing;
                        list = castList;
                    } else if (existing == null) {
                        list = new ArrayList<>();
                        parent.put(lastKey, list);
                    } else {
                        throw new IllegalArgumentException("Line " + lineNumber + ": Key '" + lastKey + "' is already defined as a non-list");
                    }

                    Map<String, Object> newElement = new LinkedHashMap<>();
                    list.add(newElement);
                    currentSection = newElement;
                    continue;
                }

                // Single table: [section]
                if (line.startsWith("[") && line.endsWith("]")) {
                    String pathStr = line.substring(1, line.length() - 1).trim();
                    if (pathStr.isEmpty()) {
                        throw new IllegalArgumentException("Line " + lineNumber + ": Empty table header");
                    }
                    String[] parts = pathStr.split("\\.");
                    Map<String, Object> parent = navigateToParent(root, parts, lineNumber);
                    String lastKey = parts[parts.length - 1];

                    Object existing = parent.get(lastKey);
                    Map<String, Object> table;
                    if (existing instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> castMap = (Map<String, Object>) existing;
                        table = castMap;
                    } else if (existing == null) {
                        table = new LinkedHashMap<>();
                        parent.put(lastKey, table);
                    } else {
                        throw new IllegalArgumentException("Line " + lineNumber + ": Key '" + lastKey + "' is already defined as a non-table");
                    }

                    currentSection = table;
                    continue;
                }

                // Key-value pair
                int eqIdx = line.indexOf('=');
                if (eqIdx <= 0) {
                    throw new IllegalArgumentException("Line " + lineNumber + ": Invalid key-value format. Expected 'key = value'");
                }

                String key = line.substring(0, eqIdx).trim();
                String valStr = line.substring(eqIdx + 1).trim();

                if (key.isEmpty()) {
                    throw new IllegalArgumentException("Line " + lineNumber + ": Key cannot be empty");
                }

                // Strip inline comment if any (only outside quotes)
                valStr = stripInlineComment(valStr);

                Object parsedValue = parseValue(valStr, lineNumber);
                currentSection.put(key, parsedValue);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read string reader", e);
        }

        return root;
    }

    private static Map<String, Object> navigateToParent(Map<String, Object> root, String[] parts, int lineNumber) {
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Line " + lineNumber + ": Empty segment in table path");
            }
            Object child = current.get(part);
            if (child instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> castMap = (Map<String, Object>) child;
                current = castMap;
            } else if (child == null) {
                Map<String, Object> next = new LinkedHashMap<>();
                current.put(part, next);
                current = next;
            } else if (child instanceof List) {
                // If it's a list, navigate to the last element of the list of tables
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) child;
                if (list.isEmpty()) {
                    throw new IllegalArgumentException("Line " + lineNumber + ": Cannot navigate into empty list '" + part + "'");
                }
                current = list.get(list.size() - 1);
            } else {
                throw new IllegalArgumentException("Line " + lineNumber + ": Segment '" + part + "' conflicts with an existing non-table value");
            }
        }
        return current;
    }

    private static String stripInlineComment(String valStr) {
        if (valStr.startsWith("\"")) {
            // Find the closing quote, then look for a comment char after it
            boolean inQuotes = true;
            for (int i = 1; i < valStr.length(); i++) {
                char c = valStr.charAt(i);
                if (c == '\\' && i + 1 < valStr.length()) {
                    i++; // skip next char
                } else if (c == '"') {
                    inQuotes = false;
                    // Look ahead for comment
                    int commentIdx = valStr.indexOf('#', i + 1);
                    if (commentIdx > 0) {
                        return valStr.substring(0, commentIdx).trim();
                    }
                    break;
                }
            }
            return valStr;
        } else {
            int commentIdx = valStr.indexOf('#');
            if (commentIdx >= 0) {
                return valStr.substring(0, commentIdx).trim();
            }
        }
        return valStr;
    }

    private static Object parseValue(String valStr, int lineNumber) {
        if (valStr.startsWith("\"") && valStr.endsWith("\"")) {
            String rawStr = valStr.substring(1, valStr.length() - 1);
            return unescape(rawStr);
        }

        if (valStr.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (valStr.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }

        // Inline array: [val1, val2]
        if (valStr.startsWith("[") && valStr.endsWith("]")) {
            String inside = valStr.substring(1, valStr.length() - 1).trim();
            if (inside.isEmpty()) {
                return new ArrayList<>();
            }
            List<Object> list = new ArrayList<>();
            List<String> rawItems = splitByCommaIgnoringQuotes(inside);
            for (String raw : rawItems) {
                list.add(parseValue(raw, lineNumber));
            }
            return list;
        }

        // Try number formats
        try {
            if (valStr.contains(".") || valStr.toLowerCase().contains("e")) {
                return Double.parseDouble(valStr);
            }
            return Long.parseLong(valStr);
        } catch (NumberFormatException e) {
            // Fallback to unquoted string if parsing failed
            return valStr;
        }
    }

    private static List<String> splitByCommaIgnoringQuotes(String str) {
        List<String> items = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\\' && i + 1 < str.length() && str.charAt(i + 1) == '"') {
                sb.append(c).append(str.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
            }
            if (c == ',' && !inQuotes) {
                items.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            items.add(sb.toString().trim());
        }
        return items;
    }

    private static String unescape(String value) {
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append('\\').append(c); break;
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                sb.append(c);
            }
        }
        if (escaped) {
            sb.append('\\');
        }
        return sb.toString();
    }
}

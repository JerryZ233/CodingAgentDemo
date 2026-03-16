package com.demo.tools;

/**
 * Utility class for JSON parsing.
 * Provides simple methods to extract values from JSON strings.
 */
public final class JsonUtil {

    private JsonUtil() {
        // Utility class - no instantiation
    }

    /**
     * Extracts a string value from a JSON object.
     * 
     * @param json JSON string like {"key": "value"}
     * @param key The key to extract
     * @return The value, or null if not found
     */
    public static String getString(String json, String key) {
        if (json == null || key == null) return null;
        
        String keyPattern = "\"" + key + "\"";
        int keyPos = json.indexOf(keyPattern);
        if (keyPos == -1) return null;
        
        int colon = json.indexOf(':', keyPos);
        if (colon == -1) return null;
        
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        
        char first = json.charAt(i);
        if (first == '"') {
            return extractQuotedString(json, i);
        } else {
            return extractUnquotedValue(json, i);
        }
    }

    private static String extractQuotedString(String json, int start) {
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        boolean escaped = false;
        
        while (i < json.length()) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(unescapeChar(c));
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    private static String extractUnquotedValue(String json, int start) {
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || c == ']') break;
            end++;
        }
        return json.substring(start, end).trim();
    }

    private static char unescapeChar(char c) {
        switch (c) {
            case 'n': return '\n';
            case 't': return '\t';
            case 'r': return '\r';
            case '"': return '"';
            case '\\': return '\\';
            case '\'': return '\'';
            default: return c;
        }
    }

    /**
     * Unescapes a string (handles \n, \t, \r, \", \\, etc.)
     */
    public static String unescape(String s) {
        if (s == null) return null;
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '\'': sb.append('\''); break;
                    default: sb.append(next); break;
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

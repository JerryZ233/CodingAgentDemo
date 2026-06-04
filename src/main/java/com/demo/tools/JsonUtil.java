package com.demo.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }

            JsonObject object = root.getAsJsonObject();
            JsonElement value = object.get(key);
            if (value == null || value.isJsonNull()) {
                return null;
            }
            if (value.isJsonPrimitive()) {
                return value.getAsString();
            }
            return value.toString();
        } catch (Exception e) {
            return null;
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

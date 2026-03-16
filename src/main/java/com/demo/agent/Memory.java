package com.demo.agent;

import com.demo.model.Message;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages persistent storage for conversation data.
 * 
 * This class handles all file I/O operations for saving and loading
 * conversation history to/from JSON files.
 */
public class Memory {
    
    /**
     * Saves a list of messages to a JSON file.
     * Creates parent directories if they don't exist.
     * 
     * @param messages The messages to save
     * @param path The file path to save to
     */
    public void save(List<Message> messages, String path) {
        try {
            java.io.File file = new java.io.File(path);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(path))) {
                String json = toJson(messages);
                writer.write(json);
            }
        } catch (IOException e) {
            System.err.println("Failed to save to file: " + e.getMessage());
        }
    }
    
    /**
     * Loads messages from a JSON file.
     * 
     * @param path The file path to load from
     * @return List of messages, or empty list if file doesn't exist
     */
    public List<Message> load(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return fromJson(sb.toString());
        } catch (IOException e) {
            System.err.println("Failed to load from file: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Deletes a file.
     * 
     * @param path The file path to delete
     */
    public void delete(String path) {
        try {
            java.io.File file = new java.io.File(path);
            if (file.exists()) {
                file.delete();
            }
        } catch (Exception e) {
            System.err.println("Failed to delete file: " + e.getMessage());
        }
    }
    
    /**
     * Converts a list of messages to JSON format.
     * 
     * @param messages The messages to convert
     * @return JSON string representation
     */
    private String toJson(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"messages\": [\n");
        
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            sb.append("    {\"role\": \"").append(escapeJson(msg.getRole()))
              .append("\", \"content\": \"").append(escapeJson(msg.getContent()))
              .append("\"}");
            
            if (i < messages.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        
        sb.append("  ]\n");
        sb.append("}");
        
        return sb.toString();
    }
    
    /**
     * Parses JSON and creates a list of messages.
     * 
     * @param json The JSON string to parse
     * @return List of messages
     */
    private List<Message> fromJson(String json) {
        List<Message> messages = new ArrayList<>();
        
        if (json == null || json.isEmpty()) {
            return messages;
        }
        
        // Simple parsing - find all message objects
        int messagesStart = json.indexOf("\"messages\"");
        if (messagesStart == -1) return messages;
        
        int arrayStart = json.indexOf('[', messagesStart);
        int arrayEnd = json.lastIndexOf(']');
        if (arrayStart == -1 || arrayEnd == -1) return messages;
        
        String arrayContent = json.substring(arrayStart + 1, arrayEnd);
        
        // Parse each message object
        int pos = 0;
        while (pos < arrayContent.length()) {
            int objStart = arrayContent.indexOf('{', pos);
            if (objStart == -1) break;
            
            int objEnd = arrayContent.indexOf('}', objStart);
            if (objEnd == -1) break;
            
            String objContent = arrayContent.substring(objStart, objEnd + 1);
            
            String role = extractJsonValue(objContent, "role");
            String content = extractJsonValue(objContent, "content");
            
            if (role != null && content != null) {
                messages.add(new Message(role, unescapeJson(content)));
            }
            
            pos = objEnd + 1;
        }
        
        return messages;
    }
    
    /**
     * Extracts a value from a JSON object, handling escape sequences.
     * 
     * @param json The JSON object string
     * @param key The key to extract
     * @return The value, or null if not found
     */
    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;
        
        int colonIndex = json.indexOf(":", keyIndex);
        if (colonIndex == -1) return null;
        
        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart >= json.length()) return null;
        
        if (json.charAt(valueStart) == '"') {
            // Handle escaped quotes properly
            StringBuilder sb = new StringBuilder();
            int i = valueStart + 1;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    // Escape sequence - include both characters
                    sb.append(c);
                    sb.append(json.charAt(i + 1));
                    i += 2;
                } else if (c == '"') {
                    // End of string
                    return sb.toString();
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }
        
        return null;
    }
    
    /**
     * Escapes special characters for JSON strings.
     * 
     * @param s The string to escape
     * @return The escaped string
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
    
    /**
     * Unescapes a JSON string by processing escape sequences character by character.
     * 
     * @param s The string to unescape
     * @return The unescaped string
     */
    private String unescapeJson(String s) {
        if (s == null) return "";
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(next); break;
                }
                i++; // Skip the next character
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

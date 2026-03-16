package com.demo.tools;

import com.demo.model.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileWriteTool extends WriteFileTool {

    @Override
    public ToolResult execute(String args) {
        if (args == null) {
            return ToolResult.error(getName(), "Null arguments");
        }
        try {
            String pathStr = extractValue(args, "path");
            String content = extractValue(args, "content");

            if (pathStr == null) {
                return ToolResult.error(getName(), "Missing 'path' field in arguments");
            }
            if (content == null) {
                content = "";
            }

            Path path = Path.of(pathStr);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(path, content);

            return ToolResult.success(getName(), "File written: " + pathStr);
        } catch (Exception ex) {
            return ToolResult.error(getName(), "Error writing file: " + ex.getMessage());
        }
    }

    private String extractValue(String json, String key) {
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
            i++;
            StringBuilder sb = new StringBuilder();
            boolean escape = false;
            for (; i < json.length(); i++) {
                char c = json.charAt(i);
                if (escape) {
                    sb.append(c);
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                    continue;
                }
                if (c == '"') break;
                sb.append(c);
            }
            return sb.toString();
        } else {
            int j = i;
            while (j < json.length()) {
                char c = json.charAt(j);
                if (c == ',' || c == '}' || c == ']') break;
                j++;
            }
            return json.substring(i, j).trim();
        }
    }
}

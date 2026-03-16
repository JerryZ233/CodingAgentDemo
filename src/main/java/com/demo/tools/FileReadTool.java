package com.demo.tools;

import com.demo.model.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

public class FileReadTool extends ReadFileTool {

    @Override
    public ToolResult execute(String args) {
        String path = extractPathFromJson(args);
        if (path == null || path.isEmpty()) {
            return ToolResult.error(getName(), "Missing 'path' in arguments");
        }

        try {
            String content = Files.readString(Path.of(path));
            return ToolResult.success(getName(), content);
        } catch (java.nio.file.NoSuchFileException e) {
            return ToolResult.error(getName(), "File not found: " + path);
        } catch (IOException e) {
            return ToolResult.error(getName(), "I/O error reading file: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error(getName(), "Error reading file: " + e.getMessage());
        }
    }

    private String extractPathFromJson(String json) {
        if (json == null) return null;
        int idx = json.indexOf("\"path\"");
        if (idx < 0) idx = json.indexOf("'path'");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        char c = json.charAt(i);
        if (c == '"' || c == '\'') {
            char quote = c;
            int start = i + 1;
            int end = json.indexOf(quote, start);
            if (end < 0) return null;
            return json.substring(start, end);
        } else {
            int start = i;
            int end = start;
            while (end < json.length() && !Character.isWhitespace(json.charAt(end)) && json.charAt(end) != ',') end++;
            return json.substring(start, end);
        }
    }
}

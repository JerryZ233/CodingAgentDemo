package com.demo.tools;

import com.demo.model.ToolResult;
import java.io.File;

public class FileListTool extends ListFilesTool {

    @Override
    public ToolResult execute(String args) {
        String path = extractPathFromJson(args);
        if (path == null || path.trim().isEmpty()) {
            return ToolResult.error(getName(), "Invalid or missing 'path' in arguments");
        }

        File dir = new File(path);
        try {
            if (!dir.exists()) {
                return ToolResult.error(getName(), "Path does not exist: " + path);
            }
            if (!dir.isDirectory()) {
                return ToolResult.error(getName(), "Path is not a directory: " + path);
            }
            if (!dir.canRead()) {
                return ToolResult.error(getName(), "Permission denied reading directory: " + path);
            }

            File[] children = dir.listFiles();
            if (children == null) {
                return ToolResult.error(getName(), "Unable to read directory listing: " + path);
            }

            String listing = formatListing(children);
            return ToolResult.success(getName(), listing);
        } catch (SecurityException se) {
            return ToolResult.error(getName(), "Permission denied: " + se.getMessage());
        } catch (Exception e) {
            return ToolResult.error(getName(), "Error listing directory: " + e.getMessage());
        }
    }

    private String extractPathFromJson(String json) {
        String key = "\"path\"";
        int idx = json.indexOf(key);
        if (idx == -1) {
            key = "'path'";
            idx = json.indexOf(key);
        }
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx);
        if (colon == -1) return null;
        String rest = json.substring(colon + 1).trim();
        if (rest.startsWith("\"") && rest.endsWith("\"") && rest.length() >= 2) {
            return rest.substring(1, rest.length() - 1);
        }
        if (rest.startsWith("'") && rest.endsWith("'") && rest.length() >= 2) {
            return rest.substring(1, rest.length() - 1);
        }
        int comma = rest.indexOf(',');
        if (comma != -1) {
            return rest.substring(0, comma).trim();
        }
        return rest.trim();
    }

    private String formatListing(File[] children) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            String name = f.getName();
            if (f.isDirectory()) {
                name += "/";
            }
            sb.append(name);
            if (i < children.length - 1) sb.append("\n");
        }
        return sb.toString();
    }
}

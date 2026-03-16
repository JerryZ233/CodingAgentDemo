package com.demo.tools;

import com.demo.model.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

public class FileWriteTool extends WriteFileTool {

    /**
     * Workspace root - files can only be written within this directory
     */
    private final Path workspaceRoot;

    /**
     * Allowed file extensions (whitelist)
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        ".java", ".kt", ".scala", ".py", ".js", ".ts", ".jsx", ".tsx",
        ".html", ".css", ".scss", ".json", ".xml", ".yaml", ".yml",
        ".md", ".txt", ".log", ".sh", ".bat", ".ps1", ".sql",
        ".gradle", ".kts", ".properties", ".env"
    );

    /**
     * Dangerous path patterns to block
     */
    private static final Set<String> BLOCKED_PATTERNS = Set.of(
        "..", "~", "$", "Windows\\System32", "Windows\\SysWOW64",
        "/etc/", "/usr/", "/bin/", "/sbin/", "/var/", "/root/",
        ".ssh", ".git/config", "credentials", "secrets", "keys"
    );

    public FileWriteTool() {
        // Set workspace to project root
        this.workspaceRoot = Paths.get(System.getProperty("user.dir", "."))
            .toAbsolutePath().normalize();
    }

    @Override
    public ToolResult execute(String args) {
        if (args == null) {
            return ToolResult.error(getName(), "Null arguments");
        }

        try {
            String pathStr = extractValue(args, "path");
            String content = extractValue(args, "content");

            if (pathStr == null || pathStr.trim().isEmpty()) {
                return ToolResult.error(getName(), "Missing 'path' field in arguments");
            }

            if (content == null) {
                content = "";
            }

            Path path = Path.of(pathStr);
            Path absolutePath = path.toAbsolutePath().normalize();

            // Security check 1: Workspace confinement
            if (!isWithinWorkspace(absolutePath)) {
                return ToolResult.error(getName(), 
                    "Security: Cannot write outside workspace. Path: " + pathStr);
            }

            // Security check 2: Path traversal prevention
            if (pathStr.contains("..")) {
                return ToolResult.error(getName(), 
                    "Security: Path traversal not allowed");
            }

            // Security check 3: Block dangerous paths
            if (isDangerousPath(absolutePath.toString())) {
                return ToolResult.error(getName(), 
                    "Security: Cannot write to dangerous path: " + pathStr);
            }

            // Security check 4: File extension whitelist
            if (!isAllowedExtension(absolutePath.toString())) {
                return ToolResult.error(getName(), 
                    "Security: File extension not allowed. Allowed: " + ALLOWED_EXTENSIONS);
            }

            // Security check 5: Warn on overwrite
            if (Files.exists(absolutePath)) {
                // For now, allow overwrite but log warning
                System.out.println("WARNING: Overwriting existing file: " + absolutePath);
            }

            // Create parent directories if needed
            Path parent = absolutePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // Write the file
            Files.writeString(absolutePath, content);

            return ToolResult.success(getName(), "File written: " + pathStr);

        } catch (Exception ex) {
            return ToolResult.error(getName(), "Error writing file: " + ex.getMessage());
        }
    }

    /**
     * Check if path is within workspace
     */
    private boolean isWithinWorkspace(Path path) {
        return path.startsWith(workspaceRoot);
    }

    /**
     * Check if path contains dangerous patterns
     */
    private boolean isDangerousPath(String path) {
        String lowerPath = path.toLowerCase();
        for (String pattern : BLOCKED_PATTERNS) {
            if (lowerPath.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if file extension is allowed
     */
    private boolean isAllowedExtension(String path) {
        String lowerPath = path.toLowerCase();
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lowerPath.endsWith(ext)) {
                return true;
            }
        }
        return false;
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

package com.demo.tools;

import com.demo.model.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Tool for reading files from the filesystem.
 * 
 * Security features:
 * - Workspace confinement (restricted to project directory)
 * - Path traversal prevention
 * - Dangerous path blocking
 * - File size limit (max 1MB)
 */
public class FileReadTool implements Tool {

    private final Path workspaceRoot;

    private static final Set<String> BLOCKED_PATTERNS = Set.of(
        "..", "~", "$", "Windows\\System32", "Windows\\SysWOW64",
        "/etc/", "/usr/", "/bin/", "/sbin/", "/var/", "/root/",
        ".ssh", ".git/config", "credentials", "secrets", "keys",
        ".env", "password", "token", "api_key"
    );

    public FileReadTool() {
        this.workspaceRoot = Paths.get(System.getProperty("user.dir", "."))
            .toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Reads content from a file. Input: {\"path\": \"file path\"}";
    }

    @Override
    public ToolResult execute(String args) {
        String path = extractPathFromJson(args);
        if (path == null || path.isEmpty()) {
            return ToolResult.error(getName(), "Missing 'path' in arguments");
        }

        try {
            Path filePath = Path.of(path);
            Path absolutePath = filePath.toAbsolutePath().normalize();

            // Security: Workspace confinement
            if (!isWithinWorkspace(absolutePath)) {
                return ToolResult.error(getName(), 
                    "Security: Cannot read outside workspace. Path: " + path);
            }

            // Security: Path traversal prevention
            if (path.contains("..")) {
                return ToolResult.error(getName(), 
                    "Security: Path traversal not allowed");
            }

            // Security: Block dangerous paths
            if (isDangerousPath(absolutePath.toString())) {
                return ToolResult.error(getName(), 
                    "Security: Cannot read dangerous path: " + path);
            }

            // Security: Must be a file
            if (!Files.isRegularFile(absolutePath)) {
                return ToolResult.error(getName(), "Path is not a file: " + path);
            }

            // Security: File size limit (1MB)
            long fileSize = Files.size(absolutePath);
            if (fileSize > 1024 * 1024) {
                return ToolResult.error(getName(), "File too large (max 1MB): " + path);
            }

            String content = Files.readString(absolutePath);
            return ToolResult.success(getName(), content);

        } catch (java.nio.file.NoSuchFileException e) {
            return ToolResult.error(getName(), "File not found: " + path);
        } catch (java.nio.file.AccessDeniedException e) {
            return ToolResult.error(getName(), "Permission denied: " + path);
        } catch (Exception e) {
            return ToolResult.error(getName(), "Error reading file: " + e.getMessage());
        }
    }

    private boolean isWithinWorkspace(Path path) {
        return path.startsWith(workspaceRoot);
    }

    private boolean isDangerousPath(String path) {
        String lowerPath = path.toLowerCase();
        for (String pattern : BLOCKED_PATTERNS) {
            if (lowerPath.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
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
            int start = i + 1;
            int end = json.indexOf(c, start);
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

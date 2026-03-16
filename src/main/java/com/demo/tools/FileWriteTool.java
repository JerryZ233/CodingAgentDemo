package com.demo.tools;

import com.demo.model.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Tool for writing content to files.
 * 
 * Security features:
 * - Workspace confinement
 * - Path traversal prevention
 * - Dangerous path blocking
 * - File extension whitelist
 */
public class FileWriteTool implements Tool {

    private final Path workspaceRoot;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        ".java", ".kt", ".scala", ".py", ".js", ".ts", ".jsx", ".tsx",
        ".html", ".css", ".scss", ".json", ".xml", ".yaml", ".yml",
        ".md", ".txt", ".log", ".sh", ".bat", ".ps1", ".sql",
        ".gradle", ".kts", ".properties", ".env"
    );

    private static final Set<String> BLOCKED_PATTERNS = Set.of(
        "..", "~", "$", "Windows\\System32", "Windows\\SysWOW64",
        "/etc/", "/usr/", "/bin/", "/sbin/", "/var/", "/root/",
        ".ssh", ".git/config", "credentials", "secrets", "keys"
    );

    public FileWriteTool() {
        this.workspaceRoot = Paths.get(System.getProperty("user.dir", "."))
            .toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "Writes content to a file. Input: {\"path\": \"file path\", \"content\": \"text\"}";
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

            // Security: Workspace confinement
            if (!isWithinWorkspace(absolutePath)) {
                return ToolResult.error(getName(), 
                    "Security: Cannot write outside workspace. Path: " + pathStr);
            }

            // Security: Path traversal prevention
            if (pathStr.contains("..")) {
                return ToolResult.error(getName(), 
                    "Security: Path traversal not allowed");
            }

            // Security: Block dangerous paths
            if (isDangerousPath(absolutePath.toString())) {
                return ToolResult.error(getName(), 
                    "Security: Cannot write to dangerous path: " + pathStr);
            }

            // Security: File extension whitelist
            if (!isAllowedExtension(absolutePath.toString())) {
                return ToolResult.error(getName(), 
                    "Security: File extension not allowed. Allowed: " + ALLOWED_EXTENSIONS);
            }

            // Warn on overwrite
            if (Files.exists(absolutePath)) {
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

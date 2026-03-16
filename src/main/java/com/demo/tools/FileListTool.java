package com.demo.tools;

import com.demo.model.ToolResult;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Tool for listing files in a directory.
 * 
 * Security features:
 * - Workspace confinement
 * - Path traversal prevention
 * - Dangerous path blocking
 * - Max 1000 files limit
 */
public class FileListTool implements Tool {

    private final Path workspaceRoot;

    private static final Set<String> BLOCKED_PATTERNS = Set.of(
        "..", "~", "$", "Windows\\System32", "Windows\\SysWOW64",
        "/etc/", "/usr/", "/bin/", "/sbin/", "/var/", "/root/",
        ".ssh", ".git", "credentials", "secrets"
    );

    public FileListTool() {
        this.workspaceRoot = Paths.get(System.getProperty("user.dir", "."))
            .toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "list_files";
    }

    @Override
    public String getDescription() {
        return "Lists files in a directory. Input: {\"path\": \"directory path\"}";
    }

    @Override
    public ToolResult execute(String args) {
        String path = extractPathFromJson(args);
        if (path == null || path.trim().isEmpty()) {
            return ToolResult.error(getName(), "Invalid or missing 'path' in arguments");
        }

        File dir = new File(path);
        try {
            Path absolutePath = dir.toPath().toAbsolutePath().normalize();

            // Security: Workspace confinement
            if (!isWithinWorkspace(absolutePath)) {
                return ToolResult.error(getName(), 
                    "Security: Cannot list outside workspace. Path: " + path);
            }

            // Security: Path traversal prevention
            if (path.contains("..")) {
                return ToolResult.error(getName(), 
                    "Security: Path traversal not allowed");
            }

            // Security: Block dangerous paths
            if (isDangerousPath(absolutePath.toString())) {
                return ToolResult.error(getName(), 
                    "Security: Cannot list dangerous path: " + path);
            }

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

            // Security: Limit number of files
            if (children.length > 1000) {
                return ToolResult.error(getName(), "Too many files (max 1000): " + path);
            }

            String listing = formatListing(children);
            return ToolResult.success(getName(), listing);
        } catch (SecurityException se) {
            return ToolResult.error(getName(), "Permission denied: " + se.getMessage());
        } catch (Exception e) {
            return ToolResult.error(getName(), "Error listing directory: " + e.getMessage());
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

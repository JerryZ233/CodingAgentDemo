package com.demo.tools;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Utility class for security checks.
 * Provides common security validation methods for file operations.
 */
public final class SecurityUtil {

    private static final Set<String> BLOCKED_PATH_PATTERNS = Set.of(
        "..", "~", "$", "Windows\\System32", "Windows\\SysWOW64",
        "/etc/", "/usr/", "/bin/", "/sbin/", "/var/", "/root/",
        ".ssh", ".git/config", "credentials", "secrets", "keys",
        ".env", "password", "token", "api_key"
    );

    private static final Set<String> BLOCKED_COMMANDS = Set.of(
        "rm -rf", "del /f /s", "format", "mkfs", "dd if=",
        "shutdown", "reboot", "halt", "init 0", "kill -9",
        "curl | sh", "wget | sh", "eval", "exec "
    );

    private static final Set<String> ALLOWED_FILE_EXTENSIONS = Set.of(
        ".java", ".kt", ".scala", ".py", ".js", ".ts", ".jsx", ".tsx",
        ".html", ".css", ".scss", ".json", ".xml", ".yaml", ".yml",
        ".md", ".txt", ".log", ".sh", ".bat", ".ps1", ".sql",
        ".gradle", ".kts", ".properties", ".env"
    );

    private SecurityUtil() {
        // Utility class - no instantiation
    }

    /**
     * Gets the workspace root (project directory).
     */
    public static Path getWorkspaceRoot() {
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /**
     * Checks if a path is within the workspace.
     */
    public static boolean isWithinWorkspace(Path path) {
        return path.startsWith(getWorkspaceRoot());
    }

    /**
     * Checks if a path contains dangerous patterns.
     */
    public static boolean isDangerousPath(String path) {
        if (path == null) return true;
        String lowerPath = path.toLowerCase();
        for (String pattern : BLOCKED_PATH_PATTERNS) {
            if (lowerPath.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks for path traversal attempts.
     */
    public static boolean hasPathTraversal(String path) {
        return path != null && path.contains("..");
    }

    /**
     * Checks if a command contains dangerous patterns.
     */
    public static boolean isDangerousCommand(String command) {
        if (command == null) return false;
        String lowerCmd = command.toLowerCase();
        for (String blocked : BLOCKED_COMMANDS) {
            if (lowerCmd.contains(blocked.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a file extension is allowed for writing.
     */
    public static boolean isAllowedExtension(String path) {
        if (path == null) return false;
        String lowerPath = path.toLowerCase();
        for (String ext : ALLOWED_FILE_EXTENSIONS) {
            if (lowerPath.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the list of allowed file extensions.
     */
    public static Set<String> getAllowedExtensions() {
        return ALLOWED_FILE_EXTENSIONS;
    }

    /**
     * Gets the list of blocked path patterns.
     */
    public static Set<String> getBlockedPatterns() {
        return BLOCKED_PATH_PATTERNS;
    }
}

package com.demo.tools;

import com.demo.config.Config;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
        return getWorkspaceRoot(Config.getInstance());
    }

    public static Path getWorkspaceRoot(Config config) {
        String configuredWorkspace = config.getWorkspaceDir();
        return Paths.get(configuredWorkspace).toAbsolutePath().normalize();
    }

    /**
     * Gets the workspace root after resolving symlinks.
     */
    public static Path getWorkspaceRootRealPath() throws IOException {
        return getWorkspaceRoot().toRealPath();
    }

    public static Path getWorkspaceRootRealPath(Path workspaceRoot) throws IOException {
        return workspaceRoot.toAbsolutePath().normalize().toRealPath();
    }

    /**
     * Resolves user input against the configured workspace.
     */
    public static Path resolveWorkspacePath(String userPath) {
        return resolveWorkspacePath(userPath, getWorkspaceRoot());
    }

    public static Path resolveWorkspacePath(String userPath, Path workspaceRoot) {
        Path path = Path.of(userPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return workspaceRoot.toAbsolutePath().normalize().resolve(path).normalize();
    }

    /**
     * Checks if a path is within the workspace.
     */
    public static boolean isWithinWorkspace(Path path) {
        return isWithinWorkspace(path, getWorkspaceRoot());
    }

    public static boolean isWithinWorkspace(Path path, Path workspaceRoot) {
        try {
            return isWithinWorkspaceRealPath(path.toRealPath(), workspaceRoot);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Checks if an already-real path is within the real workspace root.
     */
    public static boolean isWithinWorkspaceRealPath(Path realPath) throws IOException {
        return isWithinWorkspaceRealPath(realPath, getWorkspaceRoot());
    }

    public static boolean isWithinWorkspaceRealPath(Path realPath, Path workspaceRoot) throws IOException {
        Path workspaceRealPath = getWorkspaceRootRealPath(workspaceRoot);
        return realPath.startsWith(workspaceRealPath);
    }

    /**
     * Resolves an existing file or directory and verifies it does not escape the workspace.
     */
    public static Path resolveExistingWorkspacePath(String userPath) throws IOException {
        return resolveExistingWorkspacePath(userPath, getWorkspaceRoot());
    }

    public static Path resolveExistingWorkspacePath(String userPath, Path workspaceRoot) throws IOException {
        Path absolutePath = resolveWorkspacePath(userPath, workspaceRoot);
        Path realPath = absolutePath.toRealPath();
        if (!isWithinWorkspaceRealPath(realPath, workspaceRoot)) {
            throw new SecurityException("Path escapes workspace");
        }
        return realPath;
    }

    /**
     * Resolves a write target and verifies the target and parent cannot escape the workspace.
     */
    public static Path resolveWritableWorkspacePath(String userPath) throws IOException {
        return resolveWritableWorkspacePath(userPath, getWorkspaceRoot());
    }

    public static Path resolveWritableWorkspacePath(String userPath, Path workspaceRoot) throws IOException {
        Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        Path absolutePath = resolveWorkspacePath(userPath, normalizedWorkspaceRoot);
        if (!absolutePath.startsWith(normalizedWorkspaceRoot)) {
            throw new SecurityException("Path escapes workspace");
        }

        Path parent = absolutePath.getParent();
        if (parent == null) {
            throw new SecurityException("Path has no parent");
        }

        if (Files.exists(absolutePath, LinkOption.NOFOLLOW_LINKS)) {
            if (!isWithinWorkspaceRealPath(absolutePath.toRealPath(), normalizedWorkspaceRoot)) {
                throw new SecurityException("Path escapes workspace");
            }
        }

        ensureParentWithinWorkspace(parent, normalizedWorkspaceRoot);
        return absolutePath;
    }

    /**
     * Creates missing parent directories only after the nearest real ancestor is workspace-confined.
     */
    public static void ensureWritableParentDirectory(Path parent) throws IOException {
        ensureWritableParentDirectory(parent, getWorkspaceRoot());
    }

    public static void ensureWritableParentDirectory(Path parent, Path workspaceRoot) throws IOException {
        Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        ensureParentWithinWorkspace(parent, normalizedWorkspaceRoot);
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(parent);
        }
        if (!isWithinWorkspaceRealPath(parent.toRealPath(), normalizedWorkspaceRoot)) {
            throw new SecurityException("Parent path escapes workspace");
        }
    }

    private static void ensureParentWithinWorkspace(Path parent) throws IOException {
        ensureParentWithinWorkspace(parent, getWorkspaceRoot());
    }

    private static void ensureParentWithinWorkspace(Path parent, Path workspaceRoot) throws IOException {
        Path current = parent.toAbsolutePath().normalize();
        if (!current.startsWith(workspaceRoot)) {
            throw new SecurityException("Parent path escapes workspace");
        }

        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        if (current == null || !isWithinWorkspaceRealPath(current.toRealPath(), workspaceRoot)) {
            throw new SecurityException("Parent path escapes workspace");
        }
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

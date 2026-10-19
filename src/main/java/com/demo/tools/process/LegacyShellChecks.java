package com.demo.tools.process;
import java.util.Set;
final class LegacyShellChecks {
    private static final Set<String> BLOCKED_PATH_PATTERNS = Set.of(
        "windows/system32", "windows/syswow64",
        "/etc/", "/usr/", "/bin/", "/sbin/", "/var/", "/root/"
    );

    private static final Set<String> BLOCKED_PATH_COMPONENTS = Set.of(
        ".ssh", ".env", "credentials", "secrets", "keys", "password", "token", "api_key"
    );

    private static final Set<String> BLOCKED_COMMANDS = Set.of(
        "rm -rf", "del /f /s", "format", "mkfs", "dd if=",
        "shutdown", "reboot", "halt", "init 0", "kill -9",
        "curl | sh", "wget | sh", "eval", "exec "
    );

    public static boolean isDangerousPath(String path) {
        if (path == null) return true;
        String lowerPath = path.toLowerCase().replace('\\', '/');
        for (String pattern : BLOCKED_PATH_PATTERNS) {
            if (lowerPath.contains(pattern)) {
                return true;
            }
        }
        if (lowerPath.contains(".git/config")) {
            return true;
        }
        for (String component : lowerPath.split("/+")) {
            if (BLOCKED_PATH_COMPONENTS.contains(component)) {
                return true;
            }
        }
        return false;
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

}

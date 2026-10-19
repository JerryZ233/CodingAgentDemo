package com.demo.tools.fs;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** File policy only: shell legacy checks intentionally live elsewhere. */
public final class FileAccessPolicy {
    private static final Set<String> SYSTEM_ROOTS = Set.of("etc", "usr", "bin", "sbin", "var", "root");
    private static final Set<String> SENSITIVE = Set.of(".ssh", ".env", "credentials", "secrets", "keys",
            "password", "token", "api_key");
    private static final Set<String> KEYS = Set.of("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519");
    public boolean allowedAbsolute(Path absolute) {
        for (int i = 0; i + 1 < absolute.getNameCount(); i++)
            if (absolute.getName(i).toString().equalsIgnoreCase("windows")
                    && Set.of("system32", "syswow64").contains(absolute.getName(i + 1).toString().toLowerCase(Locale.ROOT)))
                return false;
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
            return true;
        }
        return absolute.getNameCount() == 0 || !SYSTEM_ROOTS.contains(absolute.getName(0).toString());
    }
    public boolean allowed(Path relative, boolean write) {
        for (Path part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (SENSITIVE.contains(name) || KEYS.contains(name) || name.startsWith(".env.")
                    || name.endsWith(".env") || name.equals("config.local.yaml")
                    || name.equals("config.local.yml") || name.endsWith(".pem") || name.endsWith(".key")
                    || name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".jks")
                    || name.startsWith(".agent-write-") || (write && name.equals(".git"))) return false;
        }
        // Repository Git configuration can itself contain remote credentials.
        String path = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return !path.equals(".git/config") && !path.startsWith(".git/config/")
                && !path.endsWith("/.git/config") && !path.contains("/.git/config/");
    }
}

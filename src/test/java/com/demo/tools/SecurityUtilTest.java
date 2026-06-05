package com.demo.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityUtilTest {

    @Test
    @DisplayName("Sensitive path checks use path components instead of broad substrings")
    void sensitivePathChecksUseComponents() {
        assertFalse(SecurityUtil.isDangerousPath("src/tokenizer/TokenParser.java"));
        assertFalse(SecurityUtil.isDangerousPath("docs/passwordless-login.md"));
        assertTrue(SecurityUtil.isDangerousPath(".env"));
        assertTrue(SecurityUtil.isDangerousPath("secrets/config.yaml"));
        assertTrue(SecurityUtil.isDangerousPath(".git/config"));
    }

    @Test
    @DisplayName("Dot env is not an allowed write extension")
    void dotEnvIsNotAllowedWriteExtension() {
        assertFalse(SecurityUtil.isAllowedExtension("local.env"));
        assertTrue(SecurityUtil.isAllowedExtension("application.properties"));
    }
}

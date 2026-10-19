package com.demo.logging;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Quotes untrusted data on one log line, preserving Unicode without translation. */
public final class LogData {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s\"',;}]+");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)([\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|authorization|cookie)[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;}]+)");

    private LogData() { }
    public static void registerSecret(String secret) {
        if (secret != null && !secret.isBlank()) SECRETS.add(secret);
    }
    public static String quote(Object value) {
        if (value == null) return "null";
        String text = String.valueOf(value);
        // Longer secrets first, including JSON-escaped forms from nested payloads.
        for (String secret : SECRETS.stream().sorted((a, b) -> Integer.compare(b.length(), a.length())).toList()) {
            text = text.replace(secret, "[redacted]");
            String escaped = JSON.toJson(secret);
            text = text.replace(escaped.substring(1, escaped.length() - 1), "[redacted]");
        }
        text = BEARER.matcher(text).replaceAll("Bearer [redacted]");
        text = SENSITIVE.matcher(text).replaceAll("$1\"[redacted]\"");
        return JSON.toJson(text);
    }
    public static String exception(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return quote(text);
    }
}

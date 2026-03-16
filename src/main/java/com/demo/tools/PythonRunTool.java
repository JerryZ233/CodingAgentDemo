package com.demo.tools;

import com.demo.model.ToolResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tool for running Python code or scripts.
 * 
 * Input formats:
 * - {"code": "print('hello')"} - run inline code
 * - {"file": "script.py"} - run script file
 */
public class PythonRunTool implements Tool {

    private static final Set<String> BLOCKED_PATTERNS = Set.of(
        "..", "~", "Windows\\System32", "Windows\\SysWOW64",
        "/etc/", "/usr/", "/bin/", "/sbin/", "/var/", "/root/"
    );

    @Override
    public String getName() {
        return "run_python";
    }

    @Override
    public String getDescription() {
        return "Runs Python code or script. Input: {\"code\": \"print('hello')\"} or {\"file\": \"script.py\"}";
    }

    @Override
    public ToolResult execute(String args) {
        String code = getValue(args, "code");
        String file = getValue(args, "file");

        List<String> command = new ArrayList<>();
        if (code != null) {
            String unescaped = unescape(code);
            command.add("python");
            command.add("-c");
            command.add(unescaped);
        } else if (file != null) {
            // Security: Validate file path
            if (isDangerousPath(file)) {
                return ToolResult.error(getName(), "Security: Cannot run dangerous path: " + file);
            }
            command.add("python");
            command.add(file);
        } else {
            return ToolResult.error(getName(), "Invalid input: neither 'code' nor 'file' provided.");
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        StringBuilder output = new StringBuilder();
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return ToolResult.success(getName(), output.toString());
            } else {
                String errorMessage = "Python exited with code " + exitCode;
                if (output.length() > 0) {
                    errorMessage += ". Output: " + output.toString();
                }
                return ToolResult.error(getName(), errorMessage);
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error(getName(), "Execution failed: " + e.getMessage());
        }
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

    private String getValue(String json, String key) {
        if (json == null) return null;
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        char c = json.charAt(i);
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            int end = i + 1;
            boolean escaped = false;
            while (end < json.length()) {
                char ch = json.charAt(end);
                if (escaped) {
                    switch (ch) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '\'': sb.append('\''); break;
                        default: sb.append(ch); break;
                    }
                    escaped = false;
                } else {
                    if (ch == '\\') {
                        escaped = true;
                    } else if (ch == '"') {
                        return sb.toString();
                    } else {
                        sb.append(ch);
                    }
                }
                end++;
            }
            return sb.toString();
        } else {
            int end = i;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(i, end).trim();
        }
    }

    private String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '\'': sb.append('\''); break;
                    default: sb.append(next); break;
                }
                i++;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}

package com.demo.tools.support;

import com.demo.model.ToolResult;
import com.demo.tools.fs.FileAccessException;
import com.google.gson.JsonObject;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;

public final class ToolOutputs {
    private ToolOutputs() { }
    public static ToolResult success(String name, JsonObject data) {
        if (data.toString().length() > ToolLimits.MESSAGE_CHARS)
            return error(name, "RESULT_EXCEEDS_MESSAGE_BUDGET", "Result exceeds the complete-message budget");
        return ToolResult.success(name, data.toString());
    }
    public static ToolResult error(String name, String code, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("code", code); data.addProperty("message", message);
        return ToolResult.error(name, data.toString());
    }
    public static ToolResult failure(String name, Exception error) {
        if (error instanceof FileAccessException e) return error(name, e.code(), e.getMessage());
        if (error instanceof IllegalArgumentException) return error(name, "INVALID_ARGUMENTS", "Invalid tool arguments");
        if (error instanceof NoSuchFileException) return error(name, "NOT_FOUND", "File or directory does not exist");
        if (error instanceof AccessDeniedException || error instanceof SecurityException)
            return error(name, "ACCESS_DENIED", "File access denied");
        return error(name, "IO_ERROR", "File operation failed");
    }
}

package com.demo.tools.support;

/** Shared limits for tool execution and model context. */
public final class ToolLimits {
    public static final int ARGUMENT_BYTES = 8 * 1024 * 1024;
    public static final int FILE_BYTES = 1024 * 1024;
    public static final int PATH_CHARS = 1024;
    public static final int COMMAND_CHARS = 4096;
    public static final int MESSAGE_CHARS = 8000;
    private ToolLimits() { }
}

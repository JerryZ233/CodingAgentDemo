package com.demo.tools.support;

/** Shared limits for tool execution and model context. */
public final class ToolLimits {
    public static final int ARGUMENT_BYTES = 8 * 1024 * 1024;
    public static final int FILE_BYTES = 1024 * 1024;
    public static final int PATH_CHARS = 1024;
    public static final int COMMAND_CHARS = 4096;
    public static final int MESSAGE_CHARS = 8000;
    public static final int READ_DEFAULT_LINES = 100;
    public static final int READ_MAX_LINES = 1000;
    public static final int SEARCH_QUERY_CHARS = 1024;
    public static final int SEARCH_DEFAULT_RESULTS = 50;
    public static final int SEARCH_MAX_RESULTS = 200;
    public static final int SEARCH_PREVIEW_CODEPOINTS = 500;
    public static final int SEARCH_SCAN_ENTRIES = 100_000;
    public static final long SEARCH_READ_BYTES = 32L * 1024 * 1024;
    public static final long SEARCH_TIMEOUT_MS = 5000;
    private ToolLimits() { }
}

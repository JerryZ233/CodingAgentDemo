package com.demo.tools.fs;
import java.io.IOException;
public final class FileAccessException extends IOException {
    private final String code;
    public FileAccessException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}

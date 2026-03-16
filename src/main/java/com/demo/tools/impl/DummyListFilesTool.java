package com.demo.tools.impl;

import com.demo.model.ToolResult;

/**
 * Compatibility wrapper for legacy path: com.demo.tools.impl.DummyListFilesTool.
 * Delegates to the existing ListFilesToolImpl implementation.
 */
public class DummyListFilesTool extends ListFilesToolImpl {
    @Override
    public ToolResult execute(String args) {
        return ToolResult.success(getName(), "file1.txt\nfile2.txt\ndir1/");
    }
}

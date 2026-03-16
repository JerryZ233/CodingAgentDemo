package com.demo.tools.impl;

import com.demo.model.ToolResult;

/**
 * Compatibility wrapper for legacy path: com.demo.tools.impl.DummyReadFileTool.
 * Delegates to the existing ReadFileToolImpl implementation.
 */
public class DummyReadFileTool extends ReadFileToolImpl {
    @Override
    public ToolResult execute(String args) {
        return ToolResult.success(getName(), "Dummy file content read successfully");
    }
}

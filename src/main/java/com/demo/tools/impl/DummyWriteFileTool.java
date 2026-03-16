package com.demo.tools.impl;

import com.demo.model.ToolResult;

/**
 * Compatibility wrapper for legacy path: com.demo.tools.impl.DummyWriteFileTool.
 * Delegates to the existing WriteFileToolImpl implementation.
 */
public class DummyWriteFileTool extends WriteFileToolImpl {
    @Override
    public ToolResult execute(String args) {
        return ToolResult.success(getName(), "Dummy file written successfully");
    }
}

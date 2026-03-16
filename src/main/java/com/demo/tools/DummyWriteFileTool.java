package com.demo.tools;

import com.demo.model.ToolResult;

/**
 * Dummy implementation moved to tools package.
 * Extends WriteFileTool and provides a dummy success execution.
 */
public class DummyWriteFileTool extends WriteFileTool {
    @Override
    public ToolResult execute(String args) {
        // Return a simple success result for dummy execution
        return ToolResult.success("DummyWriteFileTool executed", "Args: " + args);
    }
}

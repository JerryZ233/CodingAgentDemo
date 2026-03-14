package com.demo.tools.impl;

import com.demo.model.ToolResult;

/**
 * Dummy implementation of ReadFileTool for testing purposes.
 */
public class DummyReadFileTool extends ReadFileToolImpl {
    
    @Override
    public ToolResult execute(String args) {
        return ToolResult.success(getName(), "Dummy file content read successfully");
    }
}

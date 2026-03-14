package com.demo.tools.impl;

import com.demo.model.ToolResult;

/**
 * Dummy implementation of WriteFileTool for testing purposes.
 */
public class DummyWriteFileTool extends WriteFileToolImpl {
    
    @Override
    public ToolResult execute(String args) {
        return ToolResult.success(getName(), "Dummy file written successfully");
    }
}

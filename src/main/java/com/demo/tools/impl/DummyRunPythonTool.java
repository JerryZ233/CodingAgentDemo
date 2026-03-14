package com.demo.tools.impl;

import com.demo.model.ToolResult;

/**
 * Dummy implementation of RunPythonTool for testing purposes.
 */
public class DummyRunPythonTool extends RunPythonToolImpl {
    
    @Override
    public ToolResult execute(String args) {
        return ToolResult.success(getName(), "Dummy Python executed successfully");
    }
}

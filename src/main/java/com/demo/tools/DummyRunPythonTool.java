package com.demo.tools;

import com.demo.model.ToolResult;

/**
 * Dummy implementation moved to com.demo.tools package.
 */
public class DummyRunPythonTool extends RunPythonTool {

    @Override
    public ToolResult execute(String args) {
        // Return a dummy success result using the tool's name
        return ToolResult.success(getName(), "Dummy Python executed successfully");
    }
}
